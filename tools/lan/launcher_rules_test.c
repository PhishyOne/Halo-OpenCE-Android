#include <assert.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#include "launcher_rule_structs.inc"
#include "launcher_match_rules.h"

#define NONE (-1)
#define PIN(x,lo,hi) ((x)<(lo)?(lo):(x)>(hi)?(hi):(x))
#define csmemset memset
static struct game_variant global_variant;
static struct game_variant_options active_options;
static boolean game_engine;
static const struct game_variant_options *game_variant_options_get(void){return &active_options;}
static boolean game_engine_test_trait(long player,int trait){return player==trait;}
#include "workshop_runtime.inc"

static void base(struct game_variant *v,struct game_variant_options *o,int engine,int mode,int teams){
    memset(v,0,sizeof(*v));memset(o,0,sizeof(*o));
    v->game_engine_index=engine;v->universal_variant.teams=teams;
    v->universal_variant.health=1.0f;v->universal_variant.score_to_win=25;
    o->mode=(byte)mode;o->radar_players=_radar_players_all;
    v->universal_variant.flags=1UL<<_game_variant_draw_object_in_motion_sensor_bit;
}
static void reject(const char *text,const char *name,struct game_variant *v,struct game_variant_options *o){
    struct game_variant previous=*v;
    struct game_variant_options opts=*o;
    assert(!launcher_match_rules_apply(text,name,v,o));
    assert(!memcmp(&previous,v,sizeof(*v))&&!memcmp(&opts,o,sizeof(*o)));
}
int main(void){
    struct game_variant v;
    struct game_variant_options o;
    base(&v,&o,game_engine_slayer,_game_mode_none,1);
    assert(sizeof(o)==44 && offsetof(struct game_variant_options,gravity_percent)==40 &&
        offsetof(struct game_variant_options,damage_percent)==42);
    assert(launcher_match_rules_apply("gravity_percent=25;damage_percent=400","team_slayer",&v,&o));
    assert(o.gravity_percent==25 && o.damage_percent==400);
    assert(launcher_match_rules_apply("gravity_percent=200;damage_percent=25","team_slayer",&v,&o));
    assert(o.gravity_percent==200 && o.damage_percent==25);
    reject("damage_percent=250;gravity_percent=24","team_slayer",&v,&o);
    reject("gravity_percent=201","team_slayer",&v,&o);
    reject("damage_percent=24","team_slayer",&v,&o);
    reject("damage_percent=401","team_slayer",&v,&o);
    game_modes_options_default(&o);
    assert(o.gravity_percent==100 && o.damage_percent==100);
    memset(&o,0,sizeof(o)); /* options from a pre-v76 save */
    game_modes_options_sanitize(&o);
    assert(o.gravity_percent==100 && o.damage_percent==100);
    o.gravity_percent=201;o.damage_percent=401;game_modes_options_sanitize(&o);
    assert(o.gravity_percent==100 && o.damage_percent==100);
    assert(launcher_match_rules_apply("gravity_percent=50;damage_percent=200","team_slayer",&v,&o));
    memcpy(&active_options,&o,sizeof(o));game_modes_options_sanitize(&active_options);
    physics_set_gravity_percent(active_options.gravity_percent);
    assert(global_gravity==STOCK_GRAVITY/2);
    physics_set_gravity_percent(active_options.gravity_percent);
    assert(global_gravity==STOCK_GRAVITY/2);
    physics_set_gravity_percent(100);assert(global_gravity==STOCK_GRAVITY);
    game_engine=TRUE;global_variant=v;
    assert(game_engine_get_damage_multiplier(NONE,NONE)==2.0f);
    global_variant.universal_variant.health=2.0f;
    assert(game_engine_get_damage_multiplier(NONE,NONE)==1.0f);
    assert(game_engine_get_damage_multiplier(2,3)==0.75f);
    game_engine=FALSE;assert(game_engine_get_damage_multiplier(NONE,NONE)==1.0f);
    base(&v,&o,game_engine_slayer,_game_mode_none,1);
    assert(launcher_match_rules_apply("health=200;respawn_time=7;score_to_win=75;friendly_fire=1;no_shields=1;time_limit=15","team_slayer",&v,&o));
    assert(v.universal_variant.health==2.0f&&v.universal_variant.respawn_time==210&&v.universal_variant.score_to_win==75);
    assert(o.friendly_fire==_friendly_fire_off&&o.time_limit==15);
    assert(v.universal_variant.flags&(1UL<<_game_variant_no_shields_bit));
    assert(launcher_match_rules_apply("radar_players=2;primary_weapon=5;red_warthogs=3;blue_ghosts=2","team_slayer",&v,&o));
    assert(o.radar_players==_radar_players_none&&!(v.universal_variant.flags&(1UL<<_game_variant_draw_object_in_motion_sensor_bit)));
    assert(o.loadout==_loadout_custom&&o.primary_weapon==_loadout_weapon_sniper_rifle);
    assert(o.vehicle_set[0]==255&&o.vehicle_counts[0][0]==3&&o.vehicle_set[1]==255&&o.vehicle_counts[1][1]==2);
    reject("health=400;gravity=20","team_slayer",&v,&o);
    reject("health=200;health=300","team_slayer",&v,&o);
    reject("health=401","team_slayer",&v,&o);
    reject("health=-1","team_slayer",&v,&o);
    reject("health=99999999999999","team_slayer",&v,&o);
    reject("health=100;","team_slayer",&v,&o);
    reject("radar=0;radar_players=0","team_slayer",&v,&o);
    reject("loadout=0;primary_weapon=2","team_slayer",&v,&o);
    reject("red_vehicle_set=254;red_warthogs=1","team_slayer",&v,&o);
    reject("multi_hill=3","team_slayer",&v,&o);
    reject("infection_zombie_speed=200","team_slayer",&v,&o);
    reject("gun_game_order=1","team_slayer",&v,&o);
    reject("killstreak_radio=1","team_slayer",&v,&o);
    base(&v,&o,game_engine_ctf,_game_mode_none,1);
    assert(launcher_match_rules_apply("flag_at_home_to_score=1;points_per_capture=50;kills_score=1;single_flag_time=60","ctf",&v,&o));
    assert(v.game_engine_variant.ctf.flag_at_home_to_score==1&&v.game_engine_variant.ctf.points_per_capture==50&&v.game_engine_variant.ctf.kills_score==1&&v.game_engine_variant.ctf.single_flag_time==1800);
    reject("teams=0","ctf",&v,&o);
    base(&v,&o,game_engine_king,_game_mode_none,0);
    assert(launcher_match_rules_apply("multi_hill=3;moving_hill=1","king",&v,&o));
    assert(v.game_engine_variant.king.multi_hill==3&&v.game_engine_variant.king.moving_hill==1);
    reject("multi_hill=1","king",&v,&o);
    base(&v,&o,game_engine_oddball,_game_mode_none,0);
    assert(launcher_match_rules_apply("ball_spawn_count=4;ball_type=2;trait_with_ball=3","oddball",&v,&o));
    assert(v.game_engine_variant.oddball.ball_spawn_count==4&&v.game_engine_variant.oddball.oddball_ball_type==2&&v.game_engine_variant.oddball.trait_with_ball==3);
    base(&v,&o,game_engine_race,_game_mode_none,1);
    assert(launcher_match_rules_apply("race_type=2;team_scoring=1","team_race",&v,&o));
    assert(v.game_engine_variant.race.race_type==2&&v.game_engine_variant.race.team_scoring==1);
    base(&v,&o,game_engine_slayer,_game_mode_infection,1);
    assert(launcher_match_rules_apply("infection_initial_zombies=3;infection_countdown=0;infection_zombie_speed=175;infection_skull_damage=250","infection",&v,&o));
    assert(o.infection_initial_zombies==3&&o.infection_countdown==0&&o.infection_zombie_speed==175&&o.infection_skull_damage==250);
    reject("infection_zombie_speed=201","infection",&v,&o);
    reject("teams=0","infection",&v,&o);
    base(&v,&o,game_engine_slayer,_game_mode_gun_game,0);
    assert(launcher_match_rules_apply("gun_game_order=2;gun_game_melee_demotes=0","gun_game",&v,&o));
    assert(o.gun_game_order==2&&o.gun_game_melee_demotes==0);
    reject("score_to_win=75","gun_game",&v,&o);
    base(&v,&o,game_engine_slayer,_game_mode_vip,1);
    reject("time_limit=15","vip",&v,&o);
    reject("friendly_fire=0","vip",&v,&o);
    puts("Native rule bridge PASS (real engine field definitions, atomic invalid-rule rejection)");
    return 0;
}
