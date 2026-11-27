/* VIP: two host-authoritative escort attempts, one for each team.
 * Slayer supplies spawn/scoreboard infrastructure but not victory logic. */
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/game_engine_playlist.h"
#include "game/players.h"
#include "game/players_runtime.h"
#include "memory/data.h"
#include "networking/network_game_globals.h"
#include "networking/network_game_manager.h"
#include "objects/objects.h"
#include "objects/object_types.h"
#include "scenario/scenario.h"
#include "scenario/scenario_definitions.h"
#include "text/unicode.h"
#include "units/units.h"
#include "units/unit_definitions.h"
#include "game_modes.h"
#include "bots.h"
#include "vip.h"
#include "vip_policy.h"
#include <string.h>

void platform_log(char const *format,...);
static struct game_engine engine, base;
static struct distributed_vip_state state;
static real_point3d anchors[2];
static int map_checked, map_valid;
static long host_vip=NONE, host_vip_unit=NONE, ready_since=NONE;
static long old_units[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];

boolean vip_enabled(void)
{
    return game_modes_mode()==_game_mode_vip;
}

/* Same cross-machine player key used by Infection; never compare local
 * salted datum indices across devices. Host additionally binds the original
 * salted player and unit for the entire round. */
static uint32_t vip_identity(long index)
{
    struct player_datum *p=player_try_and_get(index);
    struct network_player const *n;
    uint32_t hash=2166136261U;
    short i;
    if(!p) return 0;
    n=&p->network_player_data;
    for(i=0;i<NUMBEROF(n->name);i++) hash=(hash^(word)n->name[i])*16777619U;
    hash=(hash^(byte)n->machine_index)*16777619U;
    hash=(hash^(byte)n->controller_index)*16777619U;
    hash=(hash^(byte)n->player_list_index)*16777619U;
    return hash?hash:1;
}

boolean vip_staging(void)
{
    return vip_enabled() && (state.phase==VIP_WAITING||state.phase==VIP_INTERMISSION);
}
boolean vip_player_is_vip(long index)
{
    struct player_datum *p;
    if(!vip_enabled()||state.phase!=VIP_ACTIVE||index==NONE||
       DATUM_INDEX_TO_ABSOLUTE_INDEX(index)!=state.vip_slot) return FALSE;
    p=player_try_and_get(index);
    return p && p->team_index==state.escort_team && !p->quit_out_of_game &&
        vip_identity(index)==state.vip_identity;
}
boolean vip_unit_is_vip(long index)
{
    struct unit_datum *u;
    if(!vip_enabled()||index==NONE) return FALSE;
    u=unit_try_and_get(index);
    return u && vip_player_is_vip(u->unit.player_index);
}
boolean vip_allow_passenger(long unit_index,long vehicle_index,short seat_index)
{
    struct unit_datum *vehicle;
    struct unit_definition *def;
    struct unit_seat *seat;
    if(!vip_unit_is_vip(unit_index)) return TRUE;
    vehicle=unit_try_and_get(vehicle_index);
    if(!vehicle) return FALSE;
    def=unit_definition_get(vehicle->definition_index);
    if(!def||seat_index<0||seat_index>=def->unit.seats.count) return FALSE;
    seat=TAG_BLOCK_GET_ELEMENT(&def->unit.seats,seat_index,struct unit_seat);
    return !TEST_FLAG(seat->flags,_unit_seat_driver_bit)&&!TEST_FLAG(seat->flags,_unit_seat_gunner_bit);
}

static struct unit_datum *living(struct player_datum *p)
{
    struct unit_datum *u;
    if(!p||p->quit_out_of_game||p->unit_index==NONE) return NULL;
    u=unit_try_and_get(p->unit_index);
    return u&&!TEST_FLAG(u->object.damage_flags,_object_dead_bit)?u:NULL;
}

static boolean map_check(void)
{
    struct scenario *scenario;
    short side, i;
    boolean spawn[2]={FALSE,FALSE};
    if(map_checked) return map_valid;
    scenario=global_scenario_get();
    if(!scenario) return FALSE;
    map_checked=1;
    for(side=0;side<2;side++) {
        long index=find_netgame_flag(NULL,0.0f,0.0f,_netgame_flag_ctf_flag,side);
        struct scenario_netgame_flag *flag;
        if(index==NONE||index<0||index>=scenario->netgame_flags.count) return FALSE;
        flag=TAG_BLOCK_GET_ELEMENT(&scenario->netgame_flags,index,struct scenario_netgame_flag);
        anchors[side]=flag->position;
    }
    for(i=0;i<player_get_starting_location_count();i++) {
        struct player_starting_location const *location=player_get_starting_location(i);
        if(location && VALID_INDEX(location->team_index,2) &&
            match_game_type(game_engine_ctf,4,location->game_types)) spawn[location->team_index]=TRUE;
    }
    if(!spawn[0]||!spawn[1]) return FALSE;
    {
        real dx=anchors[0].x-anchors[1].x,dy=anchors[0].y-anchors[1].y,dz=anchors[0].z-anchors[1].z;
        real distance=dx*dx+dy*dy+dz*dz;
        /* Also rejects NaN and coincident anchors. */
        if(!(distance>36.0f && distance<100000000.0f)) return FALSE;
    }
    map_valid=1;
    return TRUE;
}

static long player_for_vip(void)
{
    struct data_iterator it;
    struct player_datum *p;
    if(state.phase!=VIP_ACTIVE) return NONE;
    data_iterator_new(&it,player_data);
    while((p=(struct player_datum*)data_iterator_next(&it))!=NULL)
        if(vip_player_is_vip(it.datum_index)) return it.datum_index;
    return NONE;
}
static void goals(void)
{
    long index;
    struct player_datum *p;
    struct unit_datum *u;
    if(state.phase!=VIP_ACTIVE||!map_check()) {
        game_engine_clear_goal_position(0);game_engine_clear_goal_position(1);game_engine_clear_goal_position(2);
        return;
    }
    game_engine_set_goal_position(0,&anchors[1-state.escort_team],0.0f,"default",NONE,state.escort_team,NONE);
    index=player_for_vip();
    p=index==NONE?NULL:player_try_and_get(index);u=living(p);
    if(u) {
        /* Both teams can track the same living target. The VIP's own
         * marker is suppressed, leaving their extraction marker. */
        game_engine_set_goal_position(1,&u->object.position,0.5f,"default",NONE,state.escort_team,index);
        game_engine_set_goal_position(2,&u->object.position,0.5f,"flag_blue",NONE,1-state.escort_team,index);
    } else {
        game_engine_clear_goal_position(1);game_engine_clear_goal_position(2);
    }
}
boolean vip_bot_goal(long index,real_point3d *point)
{
    long target;
    struct unit_datum *unit;
    if(!vip_enabled()||state.phase!=VIP_ACTIVE||!map_check()) return FALSE;
    if(vip_player_is_vip(index)) { *point=anchors[1-state.escort_team];return TRUE; }
    target=player_for_vip();
    unit=target==NONE?NULL:living(player_try_and_get(target));
    if(!unit) return FALSE;
    *point=unit->object.position;
    /* Escort and defenders pursue the VIP, rather than standing at an
     * unrelated Slayer waypoint. Normal combat still governs their attacks. */
    return TRUE;
}

static void disarm(struct player_datum *p,struct unit_datum *u)
{
    short i;
    if(!p||!u||network_game_distributed_client()) return;
    for(i=0;i<MAXIMUM_WEAPONS_PER_UNIT;i++)
        if(u->unit.weapon_object_indices[i]!=NONE) {unit_delete_all_weapons(p->unit_index);break;}
    if(u->unit.equipment_object_index!=NONE) unit_delete_current_equipment(p->unit_index);
    for(i=0;i<NUMBER_OF_UNIT_GRENADE_TYPES;i++) u->unit.grenade_counts[i]=0;
    u->unit.primary_trigger=0.0f;
    u->unit.control_flags &= ~(UNIT_CONTROL_GUNNER_MASK|FLAG(_unit_control_use_equipment_bit));
}

static void stage_reset(long now)
{
    struct data_iterator it;
    struct player_datum *p;
    struct object_iterator objects;
    csmemset(old_units,0xff,sizeof(old_units));
    /* Remove old grenades/projectiles before the next attempt. Do not
     * count deliberate round-reset deaths in career totals. */
    object_iterator_new(&objects,_object_mask_projectile,0);
    while(object_iterator_next(&objects)) object_delete(objects.index);
    data_iterator_new(&it,player_data);
    while((p=(struct player_datum*)data_iterator_next(&it))!=NULL) {
        long slot=DATUM_INDEX_TO_ABSOLUTE_INDEX(it.datum_index);
        if(p->quit_out_of_game) continue;
        if(living(p)) {
            if(VALID_INDEX(slot,HALO_PORT_MAXIMUM_NETWORK_PLAYERS)) old_units[slot]=p->unit_index;
            unit_kill_no_statistics(p->unit_index);
        }
        p->respawn_timer=1;
        p->respawn_penalty=0;
    }
    host_vip=host_vip_unit=NONE;ready_since=NONE;
    vip_next_round(&state,(int)now);
}

static void finish_round(long now,int reason)
{
    if(!vip_end_round(&state,(int)now,reason)) return;
    host_vip=host_vip_unit=NONE;
    goals();
    platform_log("vip: round %ld result %d score %ld-%ld",(long)state.round_index+1,
        reason,(long)state.score[0],(long)state.score[1]);
    if(state.phase==VIP_FINISHED) game_engine_end_game();
}

static void update(void)
{
    long now, candidate=NONE, bot=NONE;
    int count[2]={0,0}, ready=1, reason;
    struct data_iterator it;
    struct player_datum *p;
    struct unit_datum *u;
    if(!game_engine_in_play()) return;
    goals();
    if(network_game_distributed_client()||state.phase==VIP_FINISHED) return;
    now=game_time_get();
    if(now<0) return;
    if(!map_check()) {
        vip_abort(&state,(int)now);
        platform_log("vip: unavailable (requires distinct CTF bases and both teams' spawns)");
        game_engine_end_game();return;
    }
    if(state.phase==VIP_INTERMISSION) {
        if(now-state.phase_tick>=VIP_STAGE_TICKS) stage_reset(now);
        return;
    }
    data_iterator_new(&it,player_data);
    while((p=(struct player_datum*)data_iterator_next(&it))!=NULL) {
        long slot=DATUM_INDEX_TO_ABSOLUTE_INDEX(it.datum_index);
        if(p->quit_out_of_game||!VALID_INDEX(p->team_index,2)) continue;
        count[p->team_index]++;
        u=living(p);
        if(!u||!VALID_INDEX(slot,HALO_PORT_MAXIMUM_NETWORK_PLAYERS)||old_units[slot]==p->unit_index) {
            ready=0;continue;
        }
        if(p->team_index==state.escort_team) {
            if(bots_machine_is_bot(p->network_player_data.machine_index)) {
                if(bot==NONE) bot=it.datum_index;
            } else if(candidate==NONE) candidate=it.datum_index;
        }
    }
    if(state.phase==VIP_WAITING) {
        if(now-state.phase_tick>=VIP_WAIT_LIMIT) {
            vip_abort(&state,(int)now);game_engine_end_game();return;
        }
        if(!ready||!count[0]||!count[1]) {ready_since=NONE;return;}
        if(ready_since==NONE) ready_since=now;
        if(now-ready_since<VIP_STAGE_TICKS) return;
        if(candidate==NONE) candidate=bot;
        if(candidate==NONE) return;
        p=player_try_and_get(candidate);u=living(p);
        if(!u||u->object.maximum_shield_vitality<=0.0f) {
            vip_abort(&state,(int)now);game_engine_end_game();return;
        }
        if(!vip_start_round(&state,(int)now,DATUM_INDEX_TO_ABSOLUTE_INDEX(candidate),vip_identity(candidate))) return;
        host_vip=candidate;host_vip_unit=p->unit_index;
        disarm(p,u);
        /* Fully charged stock overshield, once, with stock depletion and
         * decay. Never grant the invulnerable overcharging phase. */
        u->object.shield_vitality=3.0f;
        SET_FLAG(u->object.damage_flags,_object_shield_depleted_bit,FALSE);
        SET_FLAG(u->object.damage_flags,_object_shield_charging_bit,FALSE);
        SET_FLAG(u->object.damage_flags,_object_shield_over_charging_bit,FALSE);
        goals();
        return;
    }
    if(state.phase==VIP_ACTIVE) {
        int alive,arrived=0;
        p=player_try_and_get(host_vip);u=living(p);
        alive=u&&vip_player_is_vip(host_vip)&&p->unit_index==host_vip_unit;
        if(alive) {
            real dx=u->object.position.x-anchors[1-state.escort_team].x;
            real dy=u->object.position.y-anchors[1-state.escort_team].y;
            real dz=u->object.position.z-anchors[1-state.escort_team].z;
            disarm(p,u);
            arrived=dx*dx+dy*dy<=4.0f && dz*dz<=2.25f;
        }
        reason=vip_round_outcome(alive,arrived,count[1-state.escort_team],(int)(now-state.phase_tick));
        if(reason!=VIP_NONE) finish_round(now,reason);
    }
}
static void killed(long killer,long object,long dead,boolean friendly)
{
    (void)killer;(void)object;(void)friendly;
    if(!network_game_distributed_client()&&state.phase==VIP_ACTIVE&&dead==host_vip)
        finish_round(game_time_get(),VIP_LOST);
}
static long score(long index,enum get_score_type type)
{
    struct player_datum *p=player_try_and_get(index);
    (void)type;
    return p&&VALID_INDEX(p->team_index,2)?state.score[p->team_index]:0;
}
static wchar_t *score_text(long index,wchar_t *out)
{
    usprintf(out,L"%ld",score(index,_get_score_individual));return out;
}
static wchar_t *team_text(long team,wchar_t *out)
{
    usprintf(out,L"%ld",VALID_INDEX(team,2)?(long)state.score[team]:0L);return out;
}
static wchar_t *score_name(wchar_t *out)
{
    ustrncpy(out,L"ROUNDS",7);return out;
}
static long winner(long index)
{
    struct player_datum *p=player_try_and_get(index);
    if(!p||state.phase!=VIP_FINISHED||state.reason==VIP_ABORTED||
        state.score[0]==state.score[1]||!VALID_INDEX(p->team_index,2)) return NONE;
    return state.score[p->team_index]>state.score[1-p->team_index];
}
static boolean allow_pickup(long unit,long weapon)
{
    if(vip_unit_is_vip(unit)) return FALSE;
    return base.allow_pick_up?base.allow_pick_up(unit,weapon):TRUE;
}
static boolean init(void)
{
    struct game_variant *v=game_engine_get_variant();
    csmemset(&state,0,sizeof(state));
    state.game_number=network_game_get_game()?network_game_get_number_of_games_played():0;
    state.escort_team=state.game_number&1;state.vip_slot=-1;
    map_checked=map_valid=0;host_vip=host_vip_unit=ready_since=NONE;
    csmemset(old_units,0xff,sizeof(old_units));
    v->universal_variant.lives=0;v->universal_variant.odd_man_out=FALSE;
    v->game_engine_variant.slayer.kill_in_order=FALSE;
    SET_FLAG(v->universal_variant.flags,_game_variant_no_shields_bit,FALSE);
    return base.initialize_for_new_map?base.initialize_for_new_map():TRUE;
}
struct game_engine *vip_engine_get(struct game_engine const *slayer)
{
    base=*slayer;engine=*slayer;
    engine.initialize_for_new_map=init;
    engine.update=update;
    engine.player_killed_player=killed;
    engine.allow_pick_up=allow_pickup;
    engine.get_player_score=score;
    engine.format_player_score=score_text;
    engine.format_team_name=team_text;
    engine.format_score_name=score_name;
    engine.format_message=NULL;
    engine.did_player_win=winner;
    return &engine;
}
void vip_format_title(long index,wchar_t *title)
{
    struct player_datum *p=player_try_and_get(index);
    long ticks=MAX(0,VIP_ROUND_TICKS-(game_time_get()-state.phase_tick));
    if(state.phase==VIP_FINISHED) {
        if(state.reason==VIP_ABORTED) usnprintf(title,80,L"VIP unavailable: needs two teams and CTF bases");
        else usnprintf(title,80,L"VIP finished  |  Red %d - Blue %d",state.score[0],state.score[1]);
    } else if(state.phase==VIP_INTERMISSION)
        usnprintf(title,80,L"Round complete  |  Red %d - Blue %d  |  Switching",state.score[0],state.score[1]);
    else if(state.phase==VIP_WAITING)
        usnprintf(title,80,L"VIP round %d/%d  |  Preparing teams",state.round_index+1,VIP_ROUNDS);
    else
        usnprintf(title,80,L"VIP %d/%d  |  %s  |  %ld:%02ld  |  R%d B%d",state.round_index+1,VIP_ROUNDS,
            vip_player_is_vip(index)?L"YOU ARE VIP":p&&p->team_index==state.escort_team?L"ESCORT VIP":L"STOP VIP",
            ticks/1800,(ticks/30)%60,state.score[0],state.score[1]);
    title[79]=0;
}
long vip_write_network_state(byte *buffer,long size)
{
    if(size<(long)sizeof(state)) return 0;
    csmemcpy(buffer,&state,sizeof(state));return sizeof(state);
}
boolean vip_read_network_state(byte const *buffer,long size,boolean first)
{
    struct distributed_vip_state next;
    if(!network_game_distributed_client()||size!=(long)sizeof(next)) return FALSE;
    csmemcpy(&next,buffer,sizeof(next));
    if(!vip_state_valid(&next,network_game_get_number_of_games_played(),first?0:state.revision,
        HALO_PORT_MAXIMUM_NETWORK_PLAYERS)) return FALSE;
    if(!first&&next.revision==state.revision&&memcmp(&next,&state,sizeof(next))) return FALSE;
    state=next;goals();return TRUE;
}
