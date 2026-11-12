/* Asset-free fake world; production logic and engine helpers are generated
 * directly from their source files by test_killstreak_rewards.py. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#define NONE (-1)
#define TRUE 1
#define FALSE 0
#define MAX(a,b) ((a) > (b) ? (a) : (b))
#define FLAG(b) (1u << (b))
#define TEST_FLAG(a,b) ((a) & FLAG(b))
#define SET_FLAG(a,b,v) ((v) ? ((a) |= FLAG(b)) : ((a) &= ~FLAG(b)))
#define match_assert(file,line,condition) assert(condition)
#define REAL_MIN (-1.0e30f)
#define MAXIMUM_ATTACKERS_PER_UNIT 4
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"line %d: %s\n",__LINE__,#x); return 1; } } while (0)
typedef int boolean;
typedef float real;
static int recon_awards, recon_last_award, recon_revokes, orbital_awards;
void killstreak_orbital_start(void) { orbital_awards = 0; }
void killstreak_orbital_stop(void) { }
void killstreak_orbital_update(void) { }
void killstreak_orbital_reset_player(long id) { (void)id; }
void killstreak_orbital_award(long id, long unit) { (void)id; (void)unit; orbital_awards++; }
void killstreak_recon_reset_player(long id) { (void)id; }
void killstreak_recon_revoke(long id, long unit) { (void)id; (void)unit; recon_revokes++; }
void killstreak_recon_reset(void) { recon_awards = recon_last_award = recon_revokes = 0; }
void killstreak_recon_stop(void) { }
void killstreak_recon_update(void) { }
void killstreak_recon_award(long id, long unit, unsigned short award) {
    (void)id; (void)unit; recon_awards++; recon_last_award = award;
}
enum { _object_dead_bit, _object_shield_over_charging_bit };
enum { _unit_active_camouflaged_bit, _unit_super_camouflaged_bit };
#include "constants.inc"
#include "commander_feature_policy.h"
#include "killstreak_rewards.h"
struct game_variant { int game_engine_index; struct { int teams; } universal_variant; };
struct statistics {
    int kills[4], assists[4], deaths, suicides, kills_in_a_row;
    int last_kill_time, multiple_kills, friendly_fire_kills, seconds_online, games_played, games_won;
};
struct player_datum {
    long unit_index, team_index;
    int quit_out_of_game;
    struct { int machine_index; } network_player_data;
    short powerup_durations[NUMBER_OF_PLAYER_POWERUPS];
    struct statistics statistics;
};
struct object_data { unsigned int damage_flags; float shield_vitality, maximum_shield_vitality; int shield_stun_ticks; };
struct object_datum { struct object_data object; };
struct unit_attacker { long player_index; unsigned long game_time_stamp; float damage_inflicted; };
struct unit_datum {
    struct object_data object;
    struct { long player_index; unsigned int flags; int cause_for_camo_regrowth;
        struct unit_attacker attackers[MAXIMUM_ATTACKERS_PER_UNIT]; } unit;
};
static struct player_datum players[4];
static struct unit_datum units[4];
static long ids[4], unit_ids[4];
static int connection, multiplayer, coop, enabled, now, lookups, in_play;
static int cause_for_camo_regrowth_default;
static unsigned int commander_client_mask;
static struct game_variant variant;
static boolean game_statistics_active;
static struct player_datum *player_try_and_get(long id) {
    int i; lookups++;
    for(i=0;i<4;i++) if(ids[i] == id && id != NONE) return &players[i];
    return NULL;
}
static struct player_datum *player_get(long id) {
    struct player_datum *p = player_try_and_get(id); assert(p); return p;
}
static struct unit_datum *unit_try_and_get(long id) {
    int i; assert(id != NONE);
    for(i=0;i<4;i++) if(unit_ids[i] == id) return &units[i];
    return NULL;
}
static struct unit_datum *unit_get(long id) {
    struct unit_datum *u = unit_try_and_get(id); assert(u); return u;
}
static struct object_datum *object_get(long id) { return (struct object_datum *)unit_get(id); }
static long player_index_from_unit_index(long id) { return unit_get(id)->unit.player_index; }
static int game_connection(void) { return connection; }
static int game_in_progress(void) { return multiplayer; }
static int game_engine_running(void) { return variant.game_engine_index != game_engine_none; }
static int game_engine_in_play(void) { return in_play; }
static int network_coop_active(void) { return coop; }
static int network_game_is_splitscreen_local(void) { return connection == _game_connection_local; }
static struct game_variant *game_engine_get_variant(void) { return &variant; }
static int game_team_is_enemy(long a, short b) { return a != b; }
static int config_boolean(char const *name) { return !strcmp(name,"killstreak.enabled") && enabled; }
static int game_time_get(void) { return now; }
static void game_engine_player_killed(long a,long b,long c,int d) { (void)a; (void)b; (void)c; (void)d; }
static void *player_data;
struct data_iterator { int next; };
static void data_iterator_new(struct data_iterator *i, void *data) { (void)data; i->next=0; }
static void *data_iterator_next(struct data_iterator *i) { return i->next<4 ? &players[i->next++] : NULL; }
void player_handle_powerup_minor(long, short, short);
boolean object_double_charge_shield(long);
#include "production.inc"

static void setup(void) {
    int i,j;
    memset(players,0,sizeof(players)); memset(units,0,sizeof(units));
    connection=_game_connection_local; multiplayer=enabled=in_play=1; coop=0; now=300;
    variant.game_engine_index=game_engine_slayer; variant.universal_variant.teams=1;
    for(i=0;i<4;i++) {
        ids[i]=0x10000+i; unit_ids[i]=0x20000+i;
        players[i].unit_index=unit_ids[i]; players[i].team_index=i;
        units[i].unit.player_index=ids[i]; units[i].object.shield_vitality=units[i].object.maximum_shield_vitality=1;
        for(j=0;j<MAXIMUM_ATTACKERS_PER_UNIT;j++) units[i].unit.attackers[j].player_index=NONE;
    }
    game_statistics_start(); lookups=0;
}
static void kill(int killer,int victim) {
    struct unit_attacker *a=&units[victim].unit.attackers[0];
    a->player_index=ids[killer]; a->game_time_stamp=now; a->damage_inflicted=100;
    game_statistics_record_kill(unit_ids[victim],ids[killer],NONE,(short)players[killer].team_index);
    now++;
}
static void kills(int count) { int i; for(i=0;i<count;i++) kill(0,1); }
static int camo(void) { return players[0].powerup_durations[_player_powerup_active_camouflage]; }
static int streak(void) { return killstreak_players[0].kills; }
static void tick(int count) {
    int i; for(i=0;i<count;i++) { player_update_powerups(ids[0]); now++; killstreak_rewards_update(); }
}

int main(int argc,char **argv) {
    int i,j,mode; float shield;
    assert(argc==2); setup();
    if(!strcmp(argv[1],"thresholds")) {
        kills(2); CHECK(camo()==0 && streak()==2);
        kill(0,1); CHECK(camo()==900 && TEST_FLAG(units[0].unit.flags,_unit_active_camouflaged_bit));
        CHECK(recon_awards==1 && recon_last_award==3);
        CHECK(TEST_FLAG(units[0].object.damage_flags,_object_shield_over_charging_bit));
        tick(300); kill(0,1); CHECK(camo()==600);
        kill(0,1); CHECK(camo()==900);
        CHECK(recon_awards==2 && recon_last_award==5);
        tick(100); kills(260); CHECK(camo()==800 && streak()==10 && orbital_awards==1);
        CHECK(recon_awards==2 && orbital_awards==1);
        CHECK(players[0].statistics.kills_in_a_row==265);
        units[0].object.shield_vitality=2.2f; shield=units[0].object.shield_vitality;
        tick(800); CHECK(camo()==0 && !TEST_FLAG(units[0].unit.flags,_unit_active_camouflaged_bit));
        CHECK(units[0].object.shield_vitality==shield);
    } else if(!strcmp(argv[1],"life-streak-not-multikill")) {
        /* Three kills many minutes apart still constitute a life streak.
         * Only dying resets it; multikill timing never enters the reward. */
        kill(0,1); now+=9000;
        kill(0,1); now+=9000;
        CHECK(streak()==2 && recon_awards==0 && camo()==0);
        kill(0,1);
        CHECK(streak()==3 && recon_awards==1 && recon_last_award==3 && camo()==900);
        now+=9000; kill(0,1); now+=9000; kill(0,1);
        CHECK(streak()==5 && recon_awards==2 && recon_last_award==5);
    } else if(!strcmp(argv[1],"authority-modes")) {
        for(mode=game_engine_ctf;mode<=game_engine_stub;mode++) for(j=0;j<2;j++) {
            setup(); connection=_game_connection_network_server;
            variant.game_engine_index=mode; variant.universal_variant.teams=j;
            players[1].network_player_data.machine_index=1;
            kills(3); CHECK(camo()==900);
        }
        for(i=0;i<6;i++) {
            setup();
            if(i==0) connection=_game_connection_network_client;
            if(i==1) connection=_game_connection_film_playback;
            if(i==2) enabled=0;
            if(i==3) variant.game_engine_index=game_engine_none;
            if(i==4) coop=1;
            if(i==5) in_play=0;
            commander_client_mask=KILLSTREAK_ENABLED;
            killstreak_rewards_record_kill(ids[0],ids[1]); CHECK(lookups==0);
            kills(5); CHECK(camo()==0 && streak()==0);
        }
    } else if(!strcmp(argv[1],"excluded")) {
        players[1].team_index=0; kills(5);
        CHECK(camo()==0 && players[0].statistics.friendly_fire_kills==5);
        setup(); for(i=0;i<5;i++) kill(0,0); CHECK(streak()==0 && camo()==0);
        setup(); players[1].network_player_data.machine_index=1; kills(3);
        CHECK(streak()==3 && camo()==900 && players[0].statistics.kills_in_a_row==3);
        setup(); players[0].network_player_data.machine_index=1; kills(5); CHECK(camo()==0);
        setup(); connection=_game_connection_network_server;
        players[1].network_player_data.machine_index=112; kills(3); CHECK(camo()==900);
        setup(); connection=_game_connection_network_server;
        players[1].network_player_data.machine_index=1; kills(3); CHECK(camo()==900);
        setup(); players[0].unit_index=NONE; kills(3); CHECK(camo()==0);
        setup(); units[0].object.damage_flags=FLAG(_object_dead_bit); kills(3); CHECK(camo()==0);
        setup(); players[0].quit_out_of_game=1; kills(3); CHECK(camo()==0);
        setup(); units[0].unit.player_index=ids[2]; kills(3); CHECK(camo()==0);
        setup(); killstreak_rewards_record_kill(NONE,ids[1]);
        killstreak_rewards_record_kill(0x1ffff,ids[1]); CHECK(streak()==0);
    } else if(!strcmp(argv[1],"credit")) {
        /* Attacker 0 is stale; the resolved killer is player 2. */
        for(i=0;i<3;i++) {
            units[1].unit.attackers[0].player_index=0x70000;
            units[1].unit.attackers[0].game_time_stamp=now;
            units[1].unit.attackers[0].damage_inflicted=100;
            game_statistics_record_kill(unit_ids[1],ids[2],NONE,2);
        }
        CHECK(players[2].powerup_durations[0]==900 && camo()==0);
        CHECK(players[2].statistics.kills_in_a_row==3);
        /* An assist is not a reward kill. */
        setup(); units[1].unit.attackers[1].player_index=ids[2];
        units[1].unit.attackers[1].game_time_stamp=now;
        units[1].unit.attackers[1].damage_inflicted=60;
        kills(3); CHECK(camo()==900 && killstreak_players[2].kills==0);
        CHECK(players[2].statistics.assists[0]==3);
        /* Enemy credit for a suicide or team kill still cannot earn rewards. */
        setup(); units[1].unit.attackers[1].player_index=ids[0];
        units[1].unit.attackers[1].game_time_stamp=now;
        units[1].unit.attackers[1].damage_inflicted=200;
        for(i=0;i<3;i++) kill(1,1);
        CHECK(camo()==0 && streak()==0);
        setup(); players[2].team_index=players[1].team_index;
        units[1].unit.attackers[1].player_index=ids[0];
        units[1].unit.attackers[1].game_time_stamp=now;
        units[1].unit.attackers[1].damage_inflicted=200;
        for(i=0;i<3;i++) kill(2,1);
        CHECK(camo()==0 && streak()==0);
    } else if(!strcmp(argv[1],"resets")) {
        kills(3); units[0].object.shield_vitality=2.5f;
        kill(1,0); CHECK(camo()==0 && streak()==0 && units[0].object.shield_vitality==2.5f);
        kills(2); CHECK(camo()==0); kill(0,1); CHECK(camo()==900);
        enabled=0; killstreak_rewards_update(); CHECK(camo()==0 && streak()==0);
        enabled=1; kills(2); CHECK(camo()==0); kill(0,1); CHECK(camo()==900);
        game_statistics_stop(0); CHECK(camo()==0); kills(3); CHECK(camo()==0);
        game_statistics_start(); kills(3); CHECK(camo()==900);
        killstreak_rewards_reset_player(ids[0]); CHECK(camo()==0);
        kills(2); CHECK(camo()==0); kill(0,1); CHECK(camo()==900);
        players[0].quit_out_of_game=1; killstreak_rewards_update(); CHECK(camo()==0);
        setup(); kills(3); in_play=0; kills(2); CHECK(camo()==900);
        killstreak_rewards_update(); CHECK(camo()==0 && streak()==0);
        kills(3); CHECK(camo()==0);
    } else if(!strcmp(argv[1],"identities")) {
        long old;
        kills(2); old=ids[0]; ids[0]+=0x10000; units[0].unit.player_index=ids[0];
        kill(0,1); CHECK(streak()==1 && camo()==0);
        killstreak_rewards_reset_player(old); CHECK(streak()==1);
        kills(2); CHECK(camo()==900);
        /* Reused unit slot has a different salt and starts a new life. */
        unit_ids[0]+=0x10000; players[0].unit_index=unit_ids[0];
        players[0].powerup_durations[0]=1400;
        killstreak_rewards_update(); CHECK(camo()==1400 && streak()==0);
        kill(0,1); CHECK(streak()==1);
        setup(); kills(2); unit_ids[0]+=0x10000; players[0].unit_index=unit_ids[0];
        kill(0,1); CHECK(streak()==1 && camo()==0);
        game_statistics_start(); CHECK(streak()==0);
        /* Reconnect on the same machine cannot inherit the departing life. */
        kills(2); killstreak_rewards_reset_player(ids[0]);
        ids[0]+=0x10000; units[0].unit.player_index=ids[0];
        players[0].powerup_durations[0]=0; kill(0,1); CHECK(streak()==1 && camo()==0);
    } else if(!strcmp(argv[1],"postgame")) {
        kills(3); CHECK(camo()==900 && streak()==3);
        in_play=0; killstreak_rewards_update(); CHECK(camo()==0 && streak()==0);
        kills(3); CHECK(camo()==0 && streak()==0);
        in_play=1; kills(3); CHECK(camo()==900 && streak()==3);
    } else if(!strcmp(argv[1],"pickups")) {
        player_handle_powerup_minor(ids[0],0,1400); kills(3); CHECK(camo()==1400);
        enabled=0; killstreak_rewards_update(); CHECK(camo()==1400);
        setup(); kills(3); player_handle_powerup_minor(ids[0],0,1600);
        enabled=0; killstreak_rewards_update(); CHECK(camo()==1600);
        setup(); kills(3); CHECK(!player_handle_powerup(ids[0],0,900));
        enabled=0; killstreak_rewards_update(); CHECK(camo()==0); /* refused pickup */
        setup(); kills(3); tick(900); CHECK(player_handle_powerup(ids[0],0,1400));
        enabled=0; killstreak_rewards_update(); CHECK(camo()==1400);
        setup(); units[0].object.shield_vitality=2.5f; kills(3);
        CHECK(units[0].object.shield_vitality==2.5f);
        CHECK(!TEST_FLAG(units[0].object.damage_flags,_object_shield_over_charging_bit));
        units[0].object.shield_vitality=0; kills(2);
        CHECK(units[0].object.shield_vitality>0 && TEST_FLAG(units[0].object.damage_flags,_object_shield_over_charging_bit));
        setup(); units[0].object.maximum_shield_vitality=0;
        units[0].object.shield_vitality=0; kills(5); CHECK(camo()==900);
        CHECK(units[0].object.shield_vitality==0);
        CHECK(!TEST_FLAG(units[0].object.damage_flags,_object_shield_over_charging_bit));
        setup(); SET_FLAG(units[0].unit.flags,_unit_active_camouflaged_bit,TRUE);
        kills(3); enabled=0; killstreak_rewards_update();
        CHECK(TEST_FLAG(units[0].unit.flags,_unit_active_camouflaged_bit));
        setup(); kills(3); connection=_game_connection_network_client;
        killstreak_rewards_stop(); CHECK(camo()==900); /* cleanup cannot override host */
    } else return 2;
    return 0;
}
