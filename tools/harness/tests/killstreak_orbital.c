/* Full production modules, real input chord and damage helper, fake world. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <wchar.h>
#define NONE (-1)
#define TRUE 1
#define FALSE 0
#define MAXIMUM_LOCAL_PLAYERS 4
#define MAXIMUM_GAMEPADS 4
#define FLAG(b) (1u << (b))
#define TEST_FLAG(v,b) ((v) & FLAG(b))
#define SET_FLAG(v,b,x) ((x) ? ((v) |= FLAG(b)) : ((v) &= ~FLAG(b)))
#define DATUM_INDEX_TO_ABSOLUTE_INDEX(id) ((id)&0xffff)
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"line %d: %s\n",__LINE__,#x); return 1; } } while (0)
typedef int boolean;
typedef float real;
typedef unsigned short word;
typedef unsigned char byte;
typedef struct { float x,y,z; } real_point3d;
typedef struct { float i,j,k; } real_vector3d;
#include "message_header.h"
#include "constants.inc"
#include "commander_feature_policy.h"
#include "killstreak_rewards.h"
#include "killstreak_orbital.h"
enum { _object_dead_bit, _unit_active_camouflaged_bit, _damage_kill_instantly_bit };
enum { _player_powerup_active_camouflage, _collision_test_structure_bit,
    _collision_test_front_facing_surfaces_bit, _collision_result_structure,
    _collision_test_for_line_of_sight_flags=0xC2AD, _director_perspective_first_person=0,
    _distributed_to_host_reliably=1, _distributed_to_clients_reliably=2 };
enum { _game_control_flashlight=5, _game_control_zoom=11 };
struct game_input_state { byte buttons[12]; };
static struct { struct game_input_state input_states[4]; int initialized, controller_available[4]; } input_abstraction_globals;
struct player_datum { long unit_index, team_index; int quit_out_of_game; short local_player_index;
    struct { int machine_index, controller_index; } network_player_data; short powerup_durations[2]; };
struct unit_datum { struct { unsigned int damage_flags; float maximum_shield_vitality; real_point3d position; } object;
    struct { long player_index; unsigned int flags; } unit; };
struct observer_result { real_point3d position; real_vector3d forward; };
struct collision_result { short type; real_point3d point; struct { real_vector3d n; } plane; };
struct tag_block { int count; void *address; };
struct game_globals { struct tag_block falling_damage; };
struct game_globals_falling_damage { struct { int index; } falling_damage; };
struct damage_data { float scale; unsigned int flags; long owner_player_index,owner_object_index; short owner_team_index; };
static struct game_globals globals;
static struct game_globals_falling_damage falling;
#define TAG_BLOCK_GET_ELEMENT(b,i,t) (&((t *)(b)->address)[i])
static struct game_globals *scenario_get_game_globals(void) { return &globals; }
static void damage_data_new(struct damage_data *d,long tag) { assert(tag==123); memset(d,0,sizeof(*d)); }
static struct player_datum players[128];
static struct unit_datum units[128];
static long ids[128], unit_ids[128], locals[4];
static int connection,in_game,in_play,now,menu,cinematic,unreliable,perspective,inhibited;
static unsigned int policy;
static boolean scripted_value, *director_camera_scripted=&scripted_value;
static struct observer_result camera;
static int sent,private_sent,public_sent,requests,last_type,last_machine,texts,sounds,last_sound,camo,shields,recon;
static int geometry_calls,ground_missing,wall,steep,wrong_floor,ownership,damage_calls,damage_owner,last_damage;
static int damage_kills_owner;
static byte packet[128];
static struct player_datum *player_try_and_get(long id) {
    int i; for(i=0;i<128;i++) if(ids[i]==id && id!=NONE) return &players[i]; return NULL;
}
static struct unit_datum *unit_try_and_get(long id) {
    int i; for(i=0;i<128;i++) if(unit_ids[i]==id && id!=NONE) return &units[i]; return NULL;
}
static long distributed_player_from_byte(byte slot) { return slot<128 ? ids[slot] : NONE; }
static int distributed_machine_has_player(long machine,short slot) { (void)machine; (void)slot; return ownership; }
static long local_player_get_player_index(short local) { assert(local>=0 && local<4); return locals[local]; }
static short local_player_get_next(short local) {
    while (++local < 4) if (locals[local] != NONE) return local;
    return NONE;
}
static long player_control_get_unit_index(short local) {
    struct player_datum *p=player_try_and_get(locals[local]); return p ? p->unit_index : NONE;
}
static int game_connection(void) { return connection; }
static int game_in_progress(void) { return in_game; }
static int game_engine_in_play(void) { return in_play; }
static int main_menu_is_active(void) { return menu; }
static int cinematic_in_progress(void) { return cinematic; }
static long game_time_get(void) { assert(in_game); return now; }
static int bots_machine_is_bot(long machine) { return machine>=112; }
static int game_team_is_enemy(long a, short b) { return a!=b; }
static int killstreak_feature_allowed(unsigned int f) { return killstreak_policy_allows(policy,f); }
static int network_connection_last_read_was_unreliable(void) { return unreliable; }
static void packet_header(void *data,byte type,word size) {
    struct distributed_message_header *h=data;
    h->type=type; h->count=1; h->game_time=now; h->header=(word)((size<<4)|(2<<2));
}
static void distributed_send_to_machine_reliably(long machine,void *data,byte type,short n,word size) {
    assert(n==1 && size<=sizeof(packet)); packet_header(data,type,size);
    memcpy(packet,data,size); sent++; private_sent++; last_machine=(int)machine; last_type=type;
}
static void distributed_send(void *data,byte type,short n,word size,short dest) {
    assert(n==1 && size<=sizeof(packet)); packet_header(data,type,size); memcpy(packet,data,size);
    sent++; last_type=type;
    if(dest==_distributed_to_clients_reliably) public_sent++; else requests++;
}
static void hud_print_message(short local,wchar_t const *text) { (void)local; assert(text && *text); texts++; }
static void game_engine_play_killstreak_sound(short local,unsigned short award) { (void)local; sounds++; last_sound=award; }
static void vector_from_points3d(real_point3d const *a,real_point3d const *b,real_vector3d *v) {
    v->i=b->x-a->x; v->j=b->y-a->y; v->k=b->z-a->z;
}
static void scale_vector3d(real_vector3d const *a,real f,real_vector3d *v) { v->i=a->i*f; v->j=a->j*f; v->k=a->k*f; }
static void unit_get_head_position(long id,real_point3d *p) { struct unit_datum *u=unit_try_and_get(id); assert(u); *p=u->object.position; p->z+=1; }
static struct observer_result const *observer_get_camera(short local) { (void)local; return &camera; }
static int director_get_perspective(short local) { (void)local; return perspective; }
static int director_inhibited_input(short local) { (void)local; return inhibited; }
static int collision_test_vector(unsigned long flags,real_point3d const *p,real_vector3d const *ray,long ignore,struct collision_result *hit) {
    (void)ignore; geometry_calls++;
    if(flags==_collision_test_for_line_of_sight_flags) return wall;
    if(ray->k<0 && ray->i==0 && ray->j==0) {
        memset(hit,0,sizeof(*hit)); hit->type=_collision_result_structure;
        hit->point=*p; hit->point.z=0; hit->plane.n.k=steep?0:1;
        if(wrong_floor) hit->point.x+=1;
        return !ground_missing;
    }
    return wall;
}
static void object_cause_damage(struct damage_data *d,long object,long a,long b,long c,void *v) {
    struct unit_datum *u=unit_try_and_get(object);
    (void)a; (void)b; (void)c; (void)v;
    assert(connection!=_game_connection_network_client && u && TEST_FLAG(d->flags,_damage_kill_instantly_bit));
    damage_calls++; damage_owner=(int)d->owner_player_index; last_damage=(int)object;
    u->object.damage_flags |= FLAG(_object_dead_bit);
    if (damage_kills_owner) {
        unit_try_and_get(d->owner_object_index)->object.damage_flags |= FLAG(_object_dead_bit);
        killstreak_rewards_reset_player(d->owner_player_index);
    }
}
static void player_handle_powerup_minor(long id,short type,short ticks) {
    struct player_datum *p=player_try_and_get(id); assert(p && type==0); p->powerup_durations[0]=ticks; camo++;
}
static void object_double_charge_shield(long unit) { assert(unit_try_and_get(unit)); shields++; }
static void killstreak_recon_award(long id,long unit,unsigned short award) { (void)id; (void)unit; assert(award==3 || award==5); recon++; }
static void killstreak_recon_reset_player(long id) { (void)id; }
static void killstreak_recon_revoke(long id,long unit) { (void)id; (void)unit; }
static void killstreak_recon_reset(void) { }
static void killstreak_recon_stop(void) { }
static void killstreak_recon_update(void) { }
#include "production.inc"
static void setup(void) {
    int i;
    memset(players,0,sizeof(players)); memset(units,0,sizeof(units));
    memset(&input_abstraction_globals,0,sizeof(input_abstraction_globals));
    for(i=0;i<4;i++) { input_abstraction_orbital_chord((short)i); locals[i]=NONE; }
    input_abstraction_globals.initialized=1;
    for(i=0;i<128;i++) {
        ids[i]=0x10000+i; unit_ids[i]=0x20000+i;
        players[i].unit_index=unit_ids[i]; players[i].team_index=i?1:0;
        players[i].local_player_index=NONE; players[i].network_player_data.machine_index=i?112:1;
        units[i].unit.player_index=ids[i]; units[i].object.maximum_shield_vitality=1;
        units[i].object.position.x=500;
    }
    units[0].object.position.x=0;
    in_game=in_play=ownership=1; now=300; connection=_game_connection_network_server;
    menu=cinematic=unreliable=scripted_value=perspective=inhibited=0;
    policy=KILLSTREAK_ENABLED|KILLSTREAK_ORBITAL|KILLSTREAK_ANNOUNCEMENTS;
    sent=private_sent=public_sent=requests=texts=sounds=camo=shields=recon=0;
    geometry_calls=ground_missing=wall=steep=wrong_floor=damage_calls=damage_kills_owner=0;
    globals.falling_damage.count=1; globals.falling_damage.address=&falling; falling.falling_damage.index=123;
    camera.position=units[0].object.position; camera.position.z=1;
    camera.forward.i=camera.forward.j=0; camera.forward.k=-1;
    killstreak_rewards_start();
}
static void kills(int n) { int i; for(i=0;i<n;i++) killstreak_rewards_record_kill(ids[0],ids[1]); }
static struct orbital_message request(void) {
    struct orbital_message m;
    memset(&m,0,sizeof(m)); m.player_slot=0; m.unit_index=(int)unit_ids[0];
    m.serial=orbital_host[0].serial; m.kind=2; m.target.x=10;
    packet_header(&m,_distributed_message_orbital_request,sizeof(m)); return m;
}
static void receive(long machine,struct orbital_message *m) { killstreak_orbital_handle_message(machine,(word *)m,sizeof(*m)); }
static void localize(void) {
    locals[0]=ids[0]; players[0].local_player_index=0;
    input_abstraction_globals.controller_available[0]=1;
}
int main(int argc,char **argv) {
    struct orbital_message m,old;
    unsigned int token;
    int i,original_calls;
    unsigned int badbits;
    assert(argc==2); setup();
    if(!strcmp(argv[1],"award")) {
        killstreak_orbital_award(ids[0],unit_ids[0]); CHECK(!orbital_host[0].active);
        kills(9); CHECK(!orbital_host[0].active && private_sent==0 && camo==2 && shields==2 && recon==2);
        kills(1); CHECK(orbital_host[0].active && private_sent==1 && last_machine==1);
        CHECK(!public_sent && !damage_calls && !sounds && killstreak_players[0].kills==10);
        kills(400); CHECK(private_sent==1 && camo==2 && shields==2 && recon==2);
        setup(); policy &= ~KILLSTREAK_ORBITAL; kills(10); CHECK(!orbital_host[0].active && camo==2);
        setup(); policy &= ~KILLSTREAK_ENABLED; kills(10); CHECK(!orbital_host[0].active && camo==0);
        setup(); players[0].network_player_data.machine_index=112; kills(10); CHECK(!orbital_host[0].active);
        setup(); players[1].team_index=0; kills(10); CHECK(!orbital_host[0].active);
        setup(); for(i=0;i<10;i++) killstreak_rewards_record_kill(ids[0],ids[0]); CHECK(!orbital_host[0].active);
        setup(); connection=_game_connection_network_client; kills(10); CHECK(!orbital_host[0].active);
    } else if(!strcmp(argv[1],"wire")) {
        for(i=0;i<12;i++) {
            setup(); kills(10); m=request();
            switch(i) {
                case 0: unreliable=1; break;
                case 1: m.reserved=1; break;
                case 2: m.header.count=2; break;
                case 3: m.header.header^=4; break;
                case 4: m.header.header^=16; break;
                case 5: m.header.header|=1; break;
                case 6: m.header.type=_distributed_message_orbital_award; break;
                case 7: m.kind=3; break;
                case 8: m.player_slot=128; break;
                case 9: m.player_slot=-1; break;
                case 10: m.serial=0; break;
                case 11: m.header.game_time=-1; break;
            }
            receive(1,&m); CHECK(!public_sent && !orbital_host[0].consumed);
        }
        setup(); kills(10); m=request();
        for(i=0;i<36;i++) killstreak_orbital_handle_message(1,(word *)&m,(word)i);
        killstreak_orbital_handle_message(1,(word *)&m,37); CHECK(!public_sent);
        receive(1,&m); CHECK(public_sent==1 && orbital_host[0].consumed);
    } else if(!strcmp(argv[1],"authority")) {
        kills(10); m=request(); receive(2,&m); CHECK(!public_sent);
        killstreak_players[0].kills=9; receive(1,&m); CHECK(!public_sent);
        killstreak_players[0].kills=10;
        units[0].unit.player_index=ids[1]; receive(1,&m); CHECK(!public_sent);
        units[0].unit.player_index=ids[0];
        ownership=0; receive(1,&m); CHECK(!public_sent); ownership=1;
        receive(NONE,&m); CHECK(!public_sent);
        old=m; m.unit_index++; receive(1,&m); CHECK(!public_sent); m=old;
        connection=_game_connection_network_client; receive(1,&m); receive(NONE,&m); CHECK(!public_sent);
        m.kind=1; packet_header(&m,_distributed_message_orbital_award,sizeof(m)); m.target.x=0;
        localize(); receive(1,&m); CHECK(!orbital_local[0].ready);
        m.kind=3; packet_header(&m,_distributed_message_orbital_incoming,sizeof(m)); receive(1,&m);
        CHECK(!orbital_warnings[0].pending && !damage_calls);
    } else if(!strcmp(argv[1],"replay")) {
        kills(10); m=request(); receive(1,&m); CHECK(public_sent==1);
        now++; receive(1,&m); CHECK(public_sent==1);
        old=m; killstreak_rewards_reset_player(ids[0]); kills(10); m=request(); token=m.serial;
        CHECK(token!=old.serial); receive(1,&old); CHECK(public_sent==1);
        now++; receive(1,&m); CHECK(public_sent==2);
    } else if(!strcmp(argv[1],"lifecycle")) {
        for(i=0;i<9;i++) {
            setup(); kills(10); m=request(); receive(1,&m);
            switch(i) {
                case 0: units[0].object.damage_flags=FLAG(_object_dead_bit); break;
                case 1: players[0].quit_out_of_game=1; break;
                case 2: ids[0]+=0x10000; units[0].unit.player_index=ids[0]; break;
                case 3: unit_ids[0]+=0x10000; players[0].unit_index=unit_ids[0]; break;
                case 4: in_play=0; break;
                case 5: policy &= ~KILLSTREAK_ORBITAL; break;
                case 6: policy &= ~KILLSTREAK_ENABLED; break;
                case 7: players[0].team_index=2; break;
                case 8: players[0].network_player_data.machine_index=2; break;
            }
            killstreak_rewards_update(); CHECK(!orbital_host[0].active);
            now+=100; receive(1,&m); killstreak_rewards_update(); CHECK(!damage_calls && public_sent==1);
        }
        setup(); kills(10); killstreak_rewards_stop(); CHECK(!orbital_running && !orbital_host[0].active);
    } else if(!strcmp(argv[1],"geometry")) {
        for(i=0;i<8;i++) {
            setup(); kills(10); m=request();
            switch(i) {
                case 0: badbits=0x7fc00000; memcpy(&m.target.x,&badbits,4); break;
                case 1: badbits=0x7f800000; memcpy(&m.target.y,&badbits,4); break;
                case 2: m.target.z=-40000; break;
                case 3: m.target.x=43; break;
                case 4: ground_missing=1; break;
                case 5: wall=1; break;
                case 6: steep=1; break;
                case 7: wrong_floor=1; break;
            }
            receive(1,&m); CHECK(!public_sent && !orbital_host[0].consumed);
            original_calls=geometry_calls; receive(1,&m); CHECK(geometry_calls==original_calls);
        }
        setup(); kills(10); m=request(); m.target.x=40; receive(1,&m); CHECK(public_sent==1);
    } else if(!strcmp(argv[1],"impact")) {
        kills(10); m=request(); receive(1,&m);
        units[1].object.position=m.target; units[2].object.position=m.target; players[2].team_index=0;
        units[3].object.position=m.target; units[3].object.position.x+=15;
        now+=99; killstreak_rewards_update(); CHECK(!damage_calls);
        now++; killstreak_rewards_update(); CHECK(damage_calls==1 && damage_owner==ids[0] && last_damage==unit_ids[1]);
        CHECK(!TEST_FLAG(units[2].object.damage_flags,_object_dead_bit) && !TEST_FLAG(units[3].object.damage_flags,_object_dead_bit));
        now++; killstreak_rewards_update(); CHECK(damage_calls==1);
        setup(); kills(10); m=request(); receive(1,&m); units[1].object.position=m.target;
        connection=_game_connection_network_client; now+=100; killstreak_rewards_update(); CHECK(!damage_calls);
        setup(); kills(10); m=request(); receive(1,&m); units[1].object.position=m.target;
        wall=1; now+=100; killstreak_rewards_update(); CHECK(!damage_calls);
        setup(); kills(10); m=request(); receive(1,&m);
        units[1].object.position=m.target; units[2].object.position=m.target;
        damage_kills_owner=1; now+=100; killstreak_rewards_update(); CHECK(damage_calls==1);
    } else if(!strcmp(argv[1],"input")) {
        localize(); kills(10); CHECK(orbital_local[0].ready);
        input_abstraction_globals.input_states[0].buttons[5]=1;
        CHECK(!input_abstraction_orbital_chord(0));
        input_abstraction_globals.input_states[0].buttons[11]=1;
        CHECK(!input_abstraction_orbital_chord(0));
        input_abstraction_globals.input_states[0].buttons[5]=0; CHECK(!input_abstraction_orbital_chord(0));
        input_abstraction_globals.input_states[0].buttons[5]=1; CHECK(input_abstraction_orbital_chord(0));
        CHECK(!input_abstraction_orbital_chord(0) && !input_abstraction_orbital_chord(-1) && !input_abstraction_orbital_chord(4));
        for(i=0;i<6;i++) {
            setup(); localize(); kills(10);
            input_abstraction_globals.input_states[0].buttons[5]=1;
            input_abstraction_globals.input_states[0].buttons[11]=1;
            switch(i) {
                case 0: ground_missing=1; break;
                case 1: camera.forward.k=1; break;
                case 2: camera.position.x=20; break;
                case 3: perspective=1; break;
                case 4: scripted_value=1; break;
                case 5: inhibited=1; break;
            }
            killstreak_rewards_update(); CHECK(!public_sent && !orbital_local[0].requested);
        }
        setup(); localize(); kills(10);
        input_abstraction_globals.input_states[0].buttons[5]=input_abstraction_globals.input_states[0].buttons[11]=1;
        killstreak_rewards_update(); CHECK(public_sent==1 && orbital_host[0].consumed);
        killstreak_rewards_update(); CHECK(public_sent==1);
    } else if(!strcmp(argv[1],"retry")) {
        /* Simulate a client whose original ground designation never
         * reached the host. It may deliberately retry after the timeout,
         * never resend automatically or without another press edge. */
        kills(10); memcpy(&m,packet,sizeof(m));
        connection=_game_connection_network_client; localize();
        memset(orbital_host,0,sizeof(orbital_host));
        receive(NONE,&m);
        CHECK(orbital_local[0].ready && !orbital_local[0].requested);
        input_abstraction_globals.input_states[0].buttons[5]=1;
        input_abstraction_globals.input_states[0].buttons[11]=1;
        killstreak_orbital_update();
        CHECK(requests==1 && orbital_local[0].requested);
        now+=89;
        input_abstraction_globals.input_states[0].buttons[5]=0;
        killstreak_orbital_update();
        CHECK(requests==1 && orbital_local[0].requested);
        now++;
        killstreak_orbital_update();
        CHECK(requests==1 && !orbital_local[0].requested);
        input_abstraction_globals.input_states[0].buttons[5]=1;
        killstreak_orbital_update();
        CHECK(requests==2 && orbital_local[0].requested);
        killstreak_orbital_update();
        CHECK(requests==2);
    } else if(!strcmp(argv[1],"presentation")) {
        kills(10); memcpy(&m,packet,sizeof(m));
        connection=_game_connection_network_client; localize(); receive(NONE,&m);
        CHECK(orbital_local[0].ready && !sounds && !texts);
        killstreak_orbital_present(0); CHECK(sounds==1 && last_sound==10 && texts==1);
        receive(NONE,&m); killstreak_orbital_present(0); CHECK(sounds==1 && texts==1);
        m.kind=3; packet_header(&m,_distributed_message_orbital_incoming,sizeof(m)); m.target.x=10;
        receive(NONE,&m); CHECK(sounds==1 && !orbital_local[0].ready);
        killstreak_orbital_present(0); CHECK(sounds==2 && last_sound==11 && texts==2);
        receive(NONE,&m); killstreak_orbital_present(0); CHECK(sounds==2 && texts==2);
        now+=100; killstreak_rewards_update(); CHECK(!damage_calls);
        /* Different client-local player salt maps from the host's slot. */
        setup(); kills(10); memcpy(&m,packet,sizeof(m));
        connection=_game_connection_network_client;
        ids[0]+=0x10000; units[0].unit.player_index=ids[0]; localize();
        receive(NONE,&m); CHECK(orbital_local[0].ready && orbital_local[0].player_index==ids[0]);
        units[0].object.damage_flags=FLAG(_object_dead_bit);
        killstreak_rewards_update(); CHECK(!orbital_local[0].ready);
        m.kind=3; packet_header(&m,_distributed_message_orbital_incoming,sizeof(m));
        receive(NONE,&m); killstreak_orbital_present(0); CHECK(sounds==1 && last_sound==11);
    } else if(!strcmp(argv[1],"damage")) {
        globals.falling_damage.count=0; damage_kill_object_for_player(unit_ids[1],ids[0]); CHECK(!damage_calls);
        globals.falling_damage.count=1; globals.falling_damage.address=NULL;
        damage_kill_object_for_player(unit_ids[1],ids[0]); CHECK(!damage_calls);
        globals.falling_damage.address=&falling; falling.falling_damage.index=NONE;
        damage_kill_object_for_player(unit_ids[1],ids[0]); CHECK(!damage_calls);
        falling.falling_damage.index=123; damage_kill_object_for_player(unit_ids[1],NONE); CHECK(!damage_calls);
        damage_kill_object_for_player(unit_ids[1],ids[0]); CHECK(damage_calls==1 && damage_owner==ids[0]);
    } else if(!strcmp(argv[1],"capacity")) {
        kills(10); m=request(); receive(1,&m);
        for(i=1;i<128;i++) units[i].object.position=m.target;
        now+=100; killstreak_rewards_update(); CHECK(damage_calls==127);
    } else return 2;
    printf("PASS orbital %s\n",argv[1]); return 0;
}
