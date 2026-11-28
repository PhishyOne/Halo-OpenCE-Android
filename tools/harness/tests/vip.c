/* Compile the exact VIP engine against a small fake native world.
 * This exercises its real round state, roster, equipment and packet logic,
 * not a second implementation of the mode. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#include <stdarg.h>
#include <stdlib.h>

typedef int boolean;
typedef unsigned char byte;
typedef unsigned short word;
typedef float real;
typedef union real_point3d { struct { real x,y,z; }; real xyz[3]; } real_point3d;
#define TRUE 1
#define FALSE 0
#define NONE (-1L)
#define MAX(a,b) ((a)>(b)?(a):(b))
#define VALID_INDEX(i,n) ((i)>=0&&(i)<(n))
#define NUMBEROF(a) ((long)(sizeof(a)/sizeof((a)[0])))
#define FLAG(i) (1u<<(i))
#define TEST_FLAG(f,i) ((f)&FLAG(i))
#define SET_FLAG(f,i,v) ((v)?((f)|=FLAG(i)):((f)&=~FLAG(i)))
#define DATUM_INDEX_TO_ABSOLUTE_INDEX(i) ((i)&65535L)
#define csmemset memset
#define csmemcpy memcpy
#define ustrncpy wcsncpy
#define HALO_PORT_MAXIMUM_NETWORK_PLAYERS 128
#define MAXIMUM_WEAPONS_PER_UNIT 4
#define NUMBER_OF_UNIT_GRENADE_TYPES 2
#define UNIT_CONTROL_GUNNER_MASK 0x7c00
#define game_engine_slayer 2
#define game_engine_ctf 1
#define _game_mode_vip 4
#define _netgame_flag_ctf_flag 0
#define _object_mask_projectile 1
enum { _object_dead_bit, _object_shield_depleted_bit, _object_shield_charging_bit,
       _object_shield_over_charging_bit, _unit_control_use_equipment_bit, _game_variant_no_shields_bit,
       _unit_seat_driver_bit, _unit_seat_gunner_bit };
enum get_score_type { _get_score_individual, _get_score_team };
struct network_player { wchar_t name[32]; int machine_index,controller_index,player_list_index; };
struct player_datum {
    struct network_player network_player_data;
    long unit_index,team_index,respawn_timer,respawn_penalty;
    int quit_out_of_game;
};
struct unit_datum {
    int definition_index;
    struct { real_point3d position; float maximum_shield_vitality,shield_vitality; unsigned int damage_flags; } object;
    struct {
        long player_index,weapon_object_indices[MAXIMUM_WEAPONS_PER_UNIT],equipment_object_index;
        char grenade_counts[2];
        float primary_trigger;
        unsigned int control_flags;
    } unit;
};
struct data_iterator { int pos; long datum_index; };
struct tag_block { int count; void *address; };
struct scenario_netgame_flag { real_point3d position; };
struct scenario { struct tag_block netgame_flags; };
struct player_starting_location { short team_index, game_types[4]; };
struct unit_seat { unsigned int flags; };
struct unit_definition { struct { struct tag_block seats; } unit; };
struct object_iterator { int pos; long index; };
struct game_variant {
    struct { long lives; int odd_man_out; unsigned int flags; } universal_variant;
    struct { struct { int kill_in_order; } slayer; } game_engine_variant;
};
struct game_engine {
    boolean (*initialize_for_new_map)(void);
    void (*update)(void);
    void (*player_killed_player)(long,long,long,boolean);
    boolean (*allow_pick_up)(long,long);
    long (*get_player_score)(long,enum get_score_type);
    wchar_t *(*format_player_score)(long,wchar_t*);
    wchar_t *(*format_team_name)(long,wchar_t*);
    wchar_t *(*format_score_name)(wchar_t*);
    void *format_message;
    long (*did_player_win)(long);
};
static struct game_variant variant;
static struct game_engine slayer;
static struct player_datum players[4];
static struct unit_datum units[4];
static long player_ids[4],unit_ids[4];
static struct scenario_netgame_flag flags[2];
static struct scenario map;
static struct player_starting_location spawns[4];
static struct unit_seat seats_def[3];
static struct unit_definition vehicle_def;
static int client, mode, map_bad, game_over, tick, game_number, kills, deletes, base_inits;
static int marker[3];
static int checks;
#define CHECK(x) do { checks++; if(!(x)){fprintf(stderr,"line %d: %s\n",__LINE__,#x);exit(1);} } while(0)
static short game_modes_mode(void) { return mode; }
static int network_game_distributed_client(void) { return client; }
static void *network_game_get_game(void) { return &variant; }
static long network_game_get_number_of_games_played(void) { return game_number; }
static long game_time_get(void) { return tick; }
static boolean game_engine_in_play(void) { return !game_over; }
static void game_engine_end_game(void) { game_over++; }
static struct game_variant *game_engine_get_variant(void) { return &variant; }
static struct player_datum *player_try_and_get(long index) {
    int i; for(i=0;i<4;i++) if(index!=NONE&&player_ids[i]==index)return &players[i];return NULL;
}
static struct unit_datum *unit_try_and_get(long index) {
    int i;for(i=0;i<4;i++)if(index!=NONE&&unit_ids[i]==index)return &units[i];return NULL;
}
static void *player_data;
static void data_iterator_new(struct data_iterator *it,void *data) {(void)data;it->pos=0;}
static void *data_iterator_next(struct data_iterator *it) {
    while(it->pos<4) {int i=it->pos++;if(player_ids[i]!=NONE){it->datum_index=player_ids[i];return &players[i];}}
    return NULL;
}
static struct scenario *global_scenario_get(void) {return &map;}
static long find_netgame_flag(real_point3d *p,real r,real h,short type,short team) {
    (void)p;(void)r;(void)h;(void)type;
    return map_bad&&team==1?NONE:team;
}
#define TAG_BLOCK_GET_ELEMENT(b,i,t) (&((t *)(b)->address)[i])
static short player_get_starting_location_count(void) {return 4;}
static struct player_starting_location *player_get_starting_location(short i) {return &spawns[i];}
static boolean match_game_type(long mode_index,long n,const short *types) {(void)n;return mode_index==types[0];}
static struct unit_definition *unit_definition_get(long index) {(void)index;return &vehicle_def;}
static int bots_machine_is_bot(long m) {return m>=64;}
static void game_engine_clear_goal_position(short i) {assert(VALID_INDEX(i,3));marker[i]=0;}
static void game_engine_set_goal_position(short i,const real_point3d *p,real h,const char *name,
        long player,short team,long ignore) {
    (void)p;(void)h;(void)name;(void)player;(void)team;(void)ignore;assert(VALID_INDEX(i,3));marker[i]=1;
}
static void unit_delete_all_weapons(long index) {
    int i;struct unit_datum *u=unit_try_and_get(index);assert(u);
    for(i=0;i<MAXIMUM_WEAPONS_PER_UNIT;i++)u->unit.weapon_object_indices[i]=NONE;
}
static void unit_delete_current_equipment(long index) {unit_try_and_get(index)->unit.equipment_object_index=NONE;}
static void unit_kill_no_statistics(long index) {kills++;unit_try_and_get(index)->object.damage_flags|=FLAG(_object_dead_bit);}
static void object_iterator_new(struct object_iterator *it,unsigned int mask,byte fl) {(void)mask;(void)fl;it->pos=0;}
static void *object_iterator_next(struct object_iterator *it) {if(it->pos>=2)return NULL;it->index=it->pos++;return it;}
static void object_delete(long index) {(void)index;deletes++;}
static void platform_log(const char *fmt,...) {(void)fmt;}
static int usnprintf(wchar_t *buf,int n,const wchar_t *fmt,...) {(void)fmt;wcsncpy(buf,L"TEST",n);return 4;}
static int usprintf(wchar_t *buf,const wchar_t *fmt,...) {va_list ap;int n;va_start(ap,fmt);n=vswprintf(buf,32,fmt,ap);va_end(ap);return n;}
static boolean base_init(void) {base_inits++;return TRUE;}
static boolean normal_pickup(long u,long w) {(void)u;(void)w;return TRUE;}
#include "vip_policy.h"
static struct { unsigned char reserved[1]; } test_options;
#define game_variant_options_get() (&test_options)
#include "vip_impl.inc"

static void arm(int i) {
    int j;
    units[i].object.damage_flags=0;units[i].object.maximum_shield_vitality=1;
    units[i].object.shield_vitality=1;
    units[i].unit.player_index=player_ids[i];units[i].unit.equipment_object_index=88;
    units[i].unit.primary_trigger=1.0f;units[i].unit.control_flags=~0u;
    units[i].unit.grenade_counts[0]=units[i].unit.grenade_counts[1]=4;
    for(j=0;j<MAXIMUM_WEAPONS_PER_UNIT;j++) units[i].unit.weapon_object_indices[j]=88+j;
    players[i].unit_index=unit_ids[i];
}
static void setup(int number) {
    int i;
    memset(players,0,sizeof(players));memset(units,0,sizeof(units));
    memset(&variant,0,sizeof(variant));memset(&slayer,0,sizeof(slayer));
    memset(flags,0,sizeof(flags));memset(spawns,0,sizeof(spawns));
    memset(marker,0,sizeof(marker));memset(seats_def,0,sizeof(seats_def));
    client=map_bad=game_over=tick=kills=deletes=base_inits=0;game_number=number;mode=_game_mode_vip;
    flags[1].position.x=20.0f;
    map.netgame_flags.count=2;map.netgame_flags.address=flags;
    seats_def[0].flags=FLAG(_unit_seat_driver_bit);seats_def[1].flags=FLAG(_unit_seat_gunner_bit);
    vehicle_def.unit.seats.count=3;vehicle_def.unit.seats.address=seats_def;
    for(i=0;i<4;i++) {
        player_ids[i]=0x10000+i;unit_ids[i]=200+i;
        players[i].team_index=i%2;players[i].network_player_data.machine_index=i;
        players[i].network_player_data.player_list_index=i;
        swprintf(players[i].network_player_data.name,32,L"Spartan%d",i);
        units[i].object.position.x=(i%2)*20.0f;
        spawns[i].team_index=i%2;spawns[i].game_types[0]=game_engine_ctf;
        arm(i);
    }
    slayer.initialize_for_new_map=base_init;slayer.allow_pick_up=normal_pickup;
    CHECK(vip_engine_get(&slayer)->initialize_for_new_map());
    CHECK(base_inits==1&&state.phase==VIP_WAITING&&vip_staging());
}
static void advance(int n) {tick=n;engine.update();}
static void start_first(void) {advance(1);advance(91);CHECK(state.phase==VIP_ACTIVE);}
static void new_lives(void) {int i;for(i=0;i<4;i++){unit_ids[i]+=100;arm(i);units[i].object.position.x=(i%2)*20.0f;}}

int main(void) {
    struct distributed_vip_state first, later, bad;
    real_point3d goal;
    setup(0);
    CHECK(engine.allow_pick_up(unit_ids[0],99)); /* ordinary initial loadout still permitted */
    start_first();
    CHECK(state.vip_slot==0&&state.escort_team==0);
    CHECK(vip_player_is_vip(player_ids[0])&&!vip_player_is_vip(player_ids[1]));
    CHECK(units[0].object.shield_vitality==3.0f&&units[0].unit.grenade_counts[0]==0&&units[0].unit.grenade_counts[1]==0);
    CHECK(units[0].unit.weapon_object_indices[0]==NONE&&units[0].unit.equipment_object_index==NONE);
    CHECK(units[1].unit.weapon_object_indices[0]!=NONE&&units[1].unit.grenade_counts[0]==4);
    CHECK(!engine.allow_pick_up(unit_ids[0],99)&&engine.allow_pick_up(unit_ids[1],99));
    CHECK(!vip_allow_passenger(unit_ids[0],unit_ids[1],0));
    CHECK(!vip_allow_passenger(unit_ids[0],unit_ids[1],1));
    CHECK(vip_allow_passenger(unit_ids[0],unit_ids[1],2));
    CHECK(!vip_allow_passenger(unit_ids[0],unit_ids[1],99));
    test_options.reserved[0]=1;goals();CHECK(marker[1]&&!marker[2]);
    test_options.reserved[0]=0;goals();CHECK(marker[1]&&marker[2]);
    CHECK(vip_bot_goal(player_ids[0],&goal)&&goal.x==20.0f);
    CHECK(vip_bot_goal(player_ids[1],&goal)&&goal.x==0.0f);
    CHECK(marker[0]&&marker[1]&&marker[2]);
    units[0].object.shield_vitality=.4f;units[0].unit.grenade_counts[1]=2;advance(92);
    CHECK(units[0].object.shield_vitality==.4f&&units[0].unit.grenade_counts[1]==0);
    CHECK(vip_write_network_state((byte*)&first,sizeof(first))==44);
    CHECK(vip_write_network_state((byte*)&first,43)==0);
    CHECK(vip_state_valid(&first,0,0,128));
    CHECK(!vip_state_valid(&first,1,0,128));
    bad=first;bad.vip_slot=128;CHECK(!vip_state_valid(&bad,0,0,128));
    bad=first;bad.vip_identity=0;CHECK(!vip_state_valid(&bad,0,0,128));
    bad=first;bad.score[0]=2;CHECK(!vip_state_valid(&bad,0,0,128));
    bad=first;bad.escort_team=1;CHECK(!vip_state_valid(&bad,0,0,128));
    /* Extraction is a real 3D zone, not just the XY coordinate. */
    units[0].object.position=flags[1].position;units[0].object.position.z=3.0f;advance(93);
    CHECK(state.phase==VIP_ACTIVE);
    units[0].object.position.z=0;advance(94);
    CHECK(state.phase==VIP_INTERMISSION&&state.score[0]==1&&state.score[1]==0&&!game_over);
    engine.player_killed_player(player_ids[1],0,player_ids[0],0);
    CHECK(state.score[0]==1&&state.score[1]==0); /* late death cannot double award */
    advance(184);CHECK(state.phase==VIP_WAITING&&state.round_index==1&&state.escort_team==1);
    CHECK(kills==4&&deletes==2&&vip_staging());
    advance(280);CHECK(state.phase==VIP_WAITING); /* old bodies are not valid spawns */
    new_lives();advance(281);advance(371);
    CHECK(state.phase==VIP_ACTIVE&&state.vip_slot==1);
    CHECK(units[0].unit.weapon_object_indices[0]!=NONE&&units[1].unit.weapon_object_indices[0]==NONE);
    engine.player_killed_player(player_ids[0],0,player_ids[1],0);
    CHECK(state.phase==VIP_INTERMISSION&&state.score[0]==2&&!game_over);
    advance(470);new_lives();advance(471);advance(561);
    CHECK(state.phase==VIP_ACTIVE&&state.escort_team==0);
    units[state.vip_slot].object.position=flags[1].position;advance(562);
    CHECK(state.phase==VIP_FINISHED&&state.score[0]==3&&state.score[1]==0&&game_over==1);
    CHECK(engine.did_player_win(player_ids[0])==TRUE&&engine.did_player_win(player_ids[1])==FALSE);
    CHECK(engine.get_player_score(player_ids[0],_get_score_team)==3);
    CHECK(vip_write_network_state((byte*)&later,sizeof(later))==44);
    CHECK(vip_state_valid(&later,0,first.revision,128));
    /* Client cannot score locally, accepts only the host's valid state. */
    setup(0);client=1;
    CHECK(vip_read_network_state((byte*)&first,sizeof(first),TRUE));
    CHECK(vip_player_is_vip(player_ids[0]));
    units[0].object.position=flags[1].position;advance(8000);
    CHECK(state.phase==VIP_ACTIVE&&!game_over);
    CHECK(vip_read_network_state((byte*)&later,sizeof(later),FALSE));
    CHECK(!vip_read_network_state((byte*)&first,sizeof(first),FALSE)); /* stale */
    bad=later;bad.score[0]=0;bad.score[1]=2;
    CHECK(!vip_read_network_state((byte*)&bad,sizeof(bad),FALSE)); /* conflicting revision */
    CHECK(!vip_read_network_state((byte*)&later,43,FALSE));
    /* Departure, even after replacement of the seat, loses this round. */
    setup(0);start_first();players[0].quit_out_of_game=1;advance(100);
    CHECK(state.phase==VIP_INTERMISSION&&state.score[1]==1);
    setup(0);start_first();unit_ids[0]+=100;arm(0);advance(100);
    CHECK(state.phase==VIP_INTERMISSION&&state.score[1]==1);
    setup(0);start_first();advance(91+VIP_ROUND_TICKS);
    CHECK(state.phase==VIP_INTERMISSION&&state.reason==VIP_TIMEOUT&&state.score[1]==1);
    setup(0);start_first();players[1].quit_out_of_game=players[3].quit_out_of_game=1;advance(100);
    CHECK(state.reason==VIP_DEFENDERS_LEFT&&state.score[0]==1);
    setup(0);map_bad=1;advance(1);
    CHECK(state.phase==VIP_FINISHED&&state.reason==VIP_ABORTED&&game_over==1);
    CHECK(engine.did_player_win(player_ids[0])==NONE);
    setup(0);players[1].quit_out_of_game=players[3].quit_out_of_game=1;advance(1);advance(VIP_WAIT_LIMIT);
    CHECK(state.phase==VIP_FINISHED&&state.reason==VIP_ABORTED);
    setup(1);start_first();CHECK(state.escort_team==1&&state.vip_slot==1);
    setup(0);players[0].network_player_data.machine_index=64;start_first();CHECK(state.vip_slot==2);
    setup(0);players[0].network_player_data.machine_index=64;players[2].network_player_data.machine_index=65;
    start_first();CHECK(state.vip_slot==0&&vip_bot_goal(player_ids[0],&goal));
    CHECK(vip_round_outcome(0,1,1,VIP_ROUND_TICKS)==VIP_LOST);
    CHECK(vip_round_outcome(1,1,1,VIP_ROUND_TICKS)==VIP_EXTRACTED);
    CHECK(vip_round_outcome(1,0,1,1)==VIP_NONE);
    /* Every possible five-round winner sequence, including blue sweeps. */
    for(int mask=0;mask<32;mask++){
        struct distributed_vip_state series={0};
        series.vip_slot=-1;
        for(int round=0;round<VIP_ROUNDS;round++){
            int winner=(mask>>round)&1;
            CHECK(series.round_index==round&&series.escort_team==(round&1));
            CHECK(vip_start_round(&series,round,0,1));
            CHECK(vip_end_round(&series,round,winner==series.escort_team?VIP_EXTRACTED:VIP_LOST));
            CHECK(vip_state_valid(&series,0,0,128));
            if(series.score[winner]==3){
                CHECK(series.phase==VIP_FINISHED&&!vip_next_round(&series,round));
                break;
            }
            CHECK(vip_next_round(&series,round)&&vip_state_valid(&series,0,0,128));
        }
        CHECK(series.phase==VIP_FINISHED);
    }
    printf("PASS VIP native engine: %d checks\n",checks);
    return 0;
}
