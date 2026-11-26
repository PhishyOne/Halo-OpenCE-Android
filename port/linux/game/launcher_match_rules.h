#ifndef HALO_LAUNCHER_MATCH_RULES_H
#define HALO_LAUNCHER_MATCH_RULES_H

/* Native bridge for the launcher Workshop's integer overrides. Include after
 * the engine types. This operates on actual variants, not opaque memory
 * offsets, and publishes neither a partial variant nor unsupported rules. */
#include <string.h>

#define LAUNCHER_RULE_MAX_TEXT 4096
#define LAUNCHER_RULE_MAX_COUNT 96
#define LAUNCHER_RULE_MAX_KEY 48

static void launcher_rule_flag(struct game_variant *v, int bit, long enabled)
{
    unsigned long mask=1UL<<bit;
    if(enabled) v->universal_variant.flags|=mask;
    else v->universal_variant.flags&=~mask;
}

static boolean launcher_rule_apply_one(const char *key,long n,const char *base,
    struct game_variant *v,struct game_variant_options *o)
{
    if(!strcmp(key,"teams")) {
        if(n < 0 || n > 1 || !(v->game_engine_index != game_engine_ctf && o->mode == _game_mode_none && strcmp(base, "sticks_and_stones") != 0)) return FALSE;
        v->universal_variant.teams=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"score_to_win")) {
        if(n < 1 || n > 500 || !(o->mode != _game_mode_gun_game && o->mode != _game_mode_infection && o->mode != _game_mode_vip)) return FALSE;
        v->universal_variant.score_to_win=n; return TRUE;
    }
    if(!strcmp(key,"time_limit")) {
        if(n < 0 || n > 120 || !(o->mode != _game_mode_vip)) return FALSE;
        o->time_limit=(short)n; return TRUE;
    }
    if(!strcmp(key,"lives")) {
        if(n < 0 || n > 50) return FALSE;
        v->universal_variant.lives=n; return TRUE;
    }
    if(!strcmp(key,"odd_man_out")) {
        if(n < 0 || n > 1) return FALSE;
        v->universal_variant.odd_man_out=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"respawn_time")) {
        if(n < 0 || n > 60) return FALSE;
        v->universal_variant.respawn_time=n*TICKS_PER_SECOND; return TRUE;
    }
    if(!strcmp(key,"respawn_time_growth")) {
        if(n < 0 || n > 60) return FALSE;
        v->universal_variant.respawn_time_growth=n*TICKS_PER_SECOND; return TRUE;
    }
    if(!strcmp(key,"suicide_penalty")) {
        if(n < 0 || n > 60) return FALSE;
        v->universal_variant.suicide_penalty=n*TICKS_PER_SECOND; return TRUE;
    }
    if(!strcmp(key,"health")) {
        if(n < 25 || n > 400) return FALSE;
        v->universal_variant.health=(real)n/100.0f; return TRUE;
    }
    if(!strcmp(key,"no_shields")) {
        if(n < 0 || n > 1) return FALSE;
        launcher_rule_flag(v, _game_variant_no_shields_bit, n); return TRUE;
    }
    if(!strcmp(key,"invisible")) {
        if(n < 0 || n > 1) return FALSE;
        launcher_rule_flag(v, _game_variant_always_invisible_bit, n); return TRUE;
    }
    if(!strcmp(key,"friend_indicators")) {
        if(n < 0 || n > 1) return FALSE;
        launcher_rule_flag(v, _game_variant_allow_friendly_navpoints_bit, n); return TRUE;
    }
    if(!strcmp(key,"generic_equipment")) {
        if(n < 0 || n > 1) return FALSE;
        launcher_rule_flag(v, _game_variant_generic_starting_equipment_bit, n); return TRUE;
    }
    if(!strcmp(key,"infinite_grenades")) {
        if(n < 0 || n > 1) return FALSE;
        launcher_rule_flag(v, _game_variant_infinite_grenades_bit, n); return TRUE;
    }
    if(!strcmp(key,"hardcore")) {
        if(n < 0 || n > 1 || !(o->mode != _game_mode_vip)) return FALSE;
        o->hardcore=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"radar")) {
        if(n < 0 || n > 1) return FALSE;
        launcher_rule_flag(v, _game_variant_draw_object_in_motion_sensor_bit, n); if (!n) o->radar_players = _radar_players_none; else if (o->radar_players == _radar_players_none) o->radar_players = _radar_players_all; return TRUE;
    }
    if(!strcmp(key,"radar_players")) {
        if(n < 0 || n > 2) return FALSE;
        o->radar_players=(byte)n; launcher_rule_flag(v, _game_variant_draw_object_in_motion_sensor_bit, n != _radar_players_none); return TRUE;
    }
    if(!strcmp(key,"goal_radar")) {
        if(n < 0 || n > 2) return FALSE;
        v->universal_variant.goal_radar=n; return TRUE;
    }
    if(!strcmp(key,"weapon_set")) {
        if(n < 0 || n > 13) return FALSE;
        v->universal_variant.weapon_set=n; return TRUE;
    }
    if(!strcmp(key,"no_map_weapons")) {
        if(n < 0 || n > 1) return FALSE;
        o->no_map_weapons=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"loadout")) {
        if(n < 0 || n > 1) return FALSE;
        o->loadout=(byte)n; return TRUE;
    }
    if(!strcmp(key,"primary_weapon")) {
        if(n < 0 || n > 9) return FALSE;
        o->primary_weapon=(byte)n; return TRUE;
    }
    if(!strcmp(key,"secondary_weapon")) {
        if(n < 0 || n > 9) return FALSE;
        o->secondary_weapon=(byte)n; return TRUE;
    }
    if(!strcmp(key,"friendly_fire")) {
        if(n < 0 || n > 3 || !(o->mode != _game_mode_vip)) return FALSE;
        o->friendly_fire=(short)n; return TRUE;
    }
    if(!strcmp(key,"friendly_fire_penalty")) {
        if(n < 0 || n > 60 || !(o->mode != _game_mode_vip)) return FALSE;
        o->friendly_fire_penalty=(short)n; return TRUE;
    }
    if(!strcmp(key,"auto_team_balance")) {
        if(n < 0 || n > 1 || !(o->mode != _game_mode_vip)) return FALSE;
        o->auto_team_balance=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"gravity_percent")) {
        if(n < 25 || n > 200) return FALSE;
        o->gravity_percent=(word)n; return TRUE;
    }
    if(!strcmp(key,"damage_percent")) {
        if(n < 25 || n > 400) return FALSE;
        o->damage_percent=(word)n; return TRUE;
    }
    if(!strcmp(key,"force_team_spawns")) {
        if(n < 0 || n > 1 || !(o->mode != _game_mode_vip)) return FALSE;
        o->force_team_spawns=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"vehicle_respawn_time")) {
        if(n < 0 || n > 300) return FALSE;
        o->vehicle_respawn_time=(short)n; return TRUE;
    }
    if(!strcmp(key,"red_vehicle_set")) {
        if(n < 254 || n > 255) return FALSE;
        o->vehicle_set[0]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"red_warthogs")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[0][0]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"red_ghosts")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[0][1]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"red_scorpions")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[0][2]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"red_rocket_warthogs")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[0][3]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"red_banshees")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[0][4]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"red_gun_turrets")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[0][5]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_vehicle_set")) {
        if(n < 254 || n > 255) return FALSE;
        o->vehicle_set[1]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_warthogs")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[1][0]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_ghosts")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[1][1]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_scorpions")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[1][2]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_rocket_warthogs")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[1][3]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_banshees")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[1][4]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"blue_gun_turrets")) {
        if(n < 0 || n > 4) return FALSE;
        o->vehicle_counts[1][5]=(byte)n; return TRUE;
    }
    if(!strcmp(key,"assault")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.assault=n; return TRUE;
    }
    if(!strcmp(key,"reset_on_capture")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.reset_on_capture=n; return TRUE;
    }
    if(!strcmp(key,"flag_must_reset")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.flag_must_reset=n; return TRUE;
    }
    if(!strcmp(key,"flag_at_home_to_score")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.flag_at_home_to_score=n; return TRUE;
    }
    if(!strcmp(key,"kills_score")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.kills_score=n; return TRUE;
    }
    if(!strcmp(key,"single_flag_time")) {
        if(n < 0 || n > 600 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.single_flag_time=n*TICKS_PER_SECOND; return TRUE;
    }
    if(!strcmp(key,"points_per_capture")) {
        if(n < 5 || n > 100 || !(v->game_engine_index == game_engine_ctf)) return FALSE;
        v->game_engine_variant.ctf.points_per_capture=n; return TRUE;
    }
    if(!strcmp(key,"no_death_bonus")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_slayer && o->mode != _game_mode_infection && o->mode != _game_mode_gun_game && o->mode != _game_mode_vip)) return FALSE;
        v->game_engine_variant.slayer.no_death_bonus=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"no_kill_penalty")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_slayer && o->mode != _game_mode_infection && o->mode != _game_mode_gun_game && o->mode != _game_mode_vip)) return FALSE;
        v->game_engine_variant.slayer.no_kill_penalty=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"kill_in_order")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_slayer && o->mode != _game_mode_infection && o->mode != _game_mode_gun_game && o->mode != _game_mode_vip)) return FALSE;
        v->game_engine_variant.slayer.kill_in_order=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"moving_hill")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_king)) return FALSE;
        v->game_engine_variant.king.moving_hill=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"multi_hill")) {
        if(n < 0 || n > 5 || !(v->game_engine_index == game_engine_king && n != 1)) return FALSE;
        v->game_engine_variant.king.multi_hill=(byte)n; return TRUE;
    }
    if(!strcmp(key,"random_start")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.random_start=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"ball_spawn_delay")) {
        if(n < 0 || n > 1 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.ball_spawn_delay=(boolean)n; return TRUE;
    }
    if(!strcmp(key,"ball_spawn_count")) {
        if(n < 1 || n > 16 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.ball_spawn_count=n; return TRUE;
    }
    if(!strcmp(key,"speed_with_ball")) {
        if(n < 0 || n > 2 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.speed_with_ball=n; return TRUE;
    }
    if(!strcmp(key,"trait_with_ball")) {
        if(n < 0 || n > 3 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.trait_with_ball=n; return TRUE;
    }
    if(!strcmp(key,"trait_without_ball")) {
        if(n < 0 || n > 3 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.trait_without_ball=n; return TRUE;
    }
    if(!strcmp(key,"ball_type")) {
        if(n < 0 || n > 2 || !(v->game_engine_index == game_engine_oddball)) return FALSE;
        v->game_engine_variant.oddball.oddball_ball_type=n; return TRUE;
    }
    if(!strcmp(key,"race_type")) {
        if(n < 0 || n > 2 || !(v->game_engine_index == game_engine_race)) return FALSE;
        v->game_engine_variant.race.race_type=n; return TRUE;
    }
    if(!strcmp(key,"team_scoring")) {
        if(n < 0 || n > 2 || !(v->game_engine_index == game_engine_race)) return FALSE;
        v->game_engine_variant.race.team_scoring=n; return TRUE;
    }
    if(!strcmp(key,"infection_initial_zombies")) {
        if(n < 1 || n > 16 || !(o->mode == _game_mode_infection)) return FALSE;
        o->infection_initial_zombies=(byte)n; return TRUE;
    }
    if(!strcmp(key,"infection_zombie_speed")) {
        if(n < 100 || n > 200 || !(o->mode == _game_mode_infection)) return FALSE;
        o->infection_zombie_speed=(byte)n; return TRUE;
    }
    if(!strcmp(key,"infection_skull_damage")) {
        if(n < 100 || n > 250 || !(o->mode == _game_mode_infection)) return FALSE;
        o->infection_skull_damage=(byte)n; return TRUE;
    }
    if(!strcmp(key,"infection_respawn_time")) {
        if(n < 1 || n > 30 || !(o->mode == _game_mode_infection)) return FALSE;
        o->infection_respawn_time=(byte)n; return TRUE;
    }
    if(!strcmp(key,"infection_countdown")) {
        if(n < 0 || n > 30 || !(o->mode == _game_mode_infection)) return FALSE;
        o->infection_countdown=(byte)n; return TRUE;
    }
    if(!strcmp(key,"infection_zombie_shields")) {
        if(n < 0 || n > 1 || !(o->mode == _game_mode_infection)) return FALSE;
        o->infection_zombie_shields=(byte)n; return TRUE;
    }
    if(!strcmp(key,"gun_game_order")) {
        if(n < 0 || n > 2 || !(o->mode == _game_mode_gun_game)) return FALSE;
        o->gun_game_order=(byte)n; return TRUE;
    }
    if(!strcmp(key,"gun_game_melee_demotes")) {
        if(n < 0 || n > 1 || !(o->mode == _game_mode_gun_game)) return FALSE;
        o->gun_game_melee_demotes=(byte)n; return TRUE;
    }
    return FALSE;
}

static int launcher_rule_seen(char keys[][LAUNCHER_RULE_MAX_KEY+1],int count,const char *key)
{
    int i;
    for(i=0;i<count;i++) if(!strcmp(keys[i],key)) return i;
    return -1;
}

/* Strict bounded key=value;key=value format. All values are nonnegative
 * decimal integers in documented UI units. Reject duplicates and unknown
 * fields, including future physics modifiers until real native support exists. */
static boolean launcher_match_rules_apply(const char *rules,const char *base,
    struct game_variant *variant,struct game_variant_options *options)
{
    char keys[LAUNCHER_RULE_MAX_COUNT][LAUNCHER_RULE_MAX_KEY+1];
    long values[LAUNCHER_RULE_MAX_COUNT];
    int count=0,i,radar,radar_players,loadout;
    const char *p=rules;
    struct game_variant next=*variant;
    struct game_variant_options opts=*options;
    if(!rules||!*rules)return TRUE;
    if(!base||strlen(rules)>LAUNCHER_RULE_MAX_TEXT)return FALSE;
    while(*p){
        int len=0;
        long n=0;
        char key[LAUNCHER_RULE_MAX_KEY+1];
        if(count>=LAUNCHER_RULE_MAX_COUNT)return FALSE;
        while((*p>='a'&&*p<='z')||*p=='_'){
            if(len>=LAUNCHER_RULE_MAX_KEY)return FALSE;
            key[len++]=*p++;
        }
        key[len]=0;
        if(!len||*p++!='='||*p<'0'||*p>'9')return FALSE;
        do{
            n=n*10+(*p++-'0');
            if(n>10000)return FALSE;
        }while(*p>='0'&&*p<='9');
        if(launcher_rule_seen(keys,count,key)>=0 ||
            !launcher_rule_apply_one(key,n,base,&next,&opts))return FALSE;
        strcpy(keys[count],key);values[count++]=n;
        if(*p){if(*p++!=';'||!*p)return FALSE;}
    }
    radar=launcher_rule_seen(keys,count,"radar");
    radar_players=launcher_rule_seen(keys,count,"radar_players");
    if(radar>=0&&radar_players>=0&&
        (values[radar]!=0)!=(values[radar_players]!=_radar_players_none))return FALSE;
    loadout=launcher_rule_seen(keys,count,"loadout");
    if(launcher_rule_seen(keys,count,"primary_weapon")>=0||
       launcher_rule_seen(keys,count,"secondary_weapon")>=0){
        if(loadout>=0&&values[loadout]!=_loadout_custom)return FALSE;
        opts.loadout=_loadout_custom;
    }
    for(i=0;i<2;i++){
        static const char *const vehicle_keys[2][6]={
            {"red_warthogs","red_ghosts","red_scorpions","red_rocket_warthogs","red_banshees","red_gun_turrets"},
            {"blue_warthogs","blue_ghosts","blue_scorpions","blue_rocket_warthogs","blue_banshees","blue_gun_turrets"}
        };
        int j,has_counts=0;
        int set=launcher_rule_seen(keys,count,i==0?"red_vehicle_set":"blue_vehicle_set");
        for(j=0;j<6;j++)if(launcher_rule_seen(keys,count,vehicle_keys[i][j])>=0)has_counts=1;
        if(has_counts){
            if(set>=0&&values[set]!=VARIANT_VEHICLE_SET_CUSTOM)return FALSE;
            opts.vehicle_set[i]=VARIANT_VEHICLE_SET_CUSTOM;
        }
    }
    *variant=next;*options=opts;
    return TRUE;
}

#endif
