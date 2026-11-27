#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
typedef unsigned char boolean,byte;
typedef unsigned short word;
typedef float real;
#define TRUE 1
#define FALSE 0
#define TICKS_PER_SECOND 30
#define NUMBEROF(a) ((long)(sizeof(a)/sizeof((a)[0])))
#define VALID_INDEX(n,c) ((n)>=0&&(n)<(c))
#define FLAG(b) (1UL<<(b))
#define SET_FLAG(f,b,v) ((v)?((f)|=FLAG(b)):((f)&=~FLAG(b)))
#define TEST_FLAG(f,b) (((f)&FLAG(b))!=0)
#define csmemset memset
#define ustrncpy wcsncpy
#include "../../../port/linux/game/vip.h"
#include "declarations.inc"

static struct game_variant variant;
static struct game_variant_options options;
static boolean running;
static struct { boolean (*allow_pick_up)(long,long); } engine;
static typeof(engine) *game_engine=&engine;
static int checks;
#define CHECK(x) do { checks++; assert(x); } while(0)

static struct game_variant *make_variant(struct game_variant *v,long type,boolean teams) {
    memset(v,0,sizeof(*v)); v->game_engine_index=type; v->universal_variant.teams=teams;
    v->universal_variant.score_to_win=25;
    v->universal_variant.weapon_set=0;
    v->universal_variant.vehicle_set=4;
    return v;
}
static struct game_variant *build_game_variant_slayer(struct game_variant *v) {return make_variant(v,game_engine_slayer,FALSE);}
static struct game_variant *build_game_variant_team_slayer(struct game_variant *v) {return make_variant(v,game_engine_slayer,TRUE);}
static struct game_variant *build_game_variant_ctf(struct game_variant *v) {return make_variant(v,game_engine_ctf,TRUE);}
static struct game_variant *build_game_variant_team_oddball(struct game_variant *v) {return make_variant(v,game_engine_oddball,TRUE);}
static struct game_variant *build_game_variant_team_king(struct game_variant *v) {return make_variant(v,game_engine_king,TRUE);}
static void game_variant_options_default(struct game_variant *v,struct game_variant_options *o) {
    memset(o,0,sizeof(*o));o->vehicle_set[0]=o->vehicle_set[1]=(byte)v->universal_variant.vehicle_set;
    memset(o->vehicle_counts,4,sizeof(o->vehicle_counts));
}
static boolean game_engine_running(void) {return running;}
static struct game_variant *game_engine_get_variant(void) {return &variant;}
static struct game_variant_options *game_variant_options_get(void) {return &options;}
static boolean ordinary_pickup(long u,long w) {return u==1&&w==2;}

#include "rules.inc"
int main(void) {
    short i;
    CHECK(game_modes_gametype_count()==9);
    CHECK(!wcscmp(game_modes_gametype_name(0),L"SWAT"));
    CHECK(!wcscmp(game_modes_gametype_name(6),L"Hardcore King"));
    CHECK(!wcscmp(game_modes_gametype_name(7),L"Sticks & Stones"));
    CHECK(!wcscmp(game_modes_gametype_name(8),L"VIP Escort"));
    CHECK(game_modes_gametype_by_name("sticks_and_stones",&variant,&options));
    CHECK(variant.game_engine_index==game_engine_slayer&&!variant.universal_variant.teams);
    CHECK(variant.universal_variant.score_to_win==25);
    CHECK(TEST_FLAG(variant.universal_variant.flags,_game_variant_infinite_grenades_bit));
    CHECK(variant.universal_variant.weapon_set==0);
    CHECK(variant.universal_variant.vehicle_set==1&&options.vehicle_set[0]==1&&options.vehicle_set[1]==1);
    CHECK(options.loadout==_loadout_custom&&options.primary_weapon==_loadout_weapon_none&&options.secondary_weapon==_loadout_weapon_none);
    CHECK(options.no_map_weapons&&!options.hardcore&&options.mode==_game_mode_none);
    for(i=0;i<NUMBER_OF_VARIANT_VEHICLES;i++)
        CHECK(options.vehicle_counts[0][i]==0&&options.vehicle_counts[1][i]==0);
    CHECK(game_modes_sticks_and_stones(&variant,&options));
    CHECK(!game_modes_sticks_and_stones_active()); /* no active game */
    running=TRUE;
    CHECK(game_modes_sticks_and_stones_active());
    engine.allow_pick_up=ordinary_pickup;
    CHECK(!game_engine_allow_pick_up(1,2)); /* valid normal pickup denied */
    options.secondary_weapon=_loadout_weapon_pistol;
    CHECK(!game_modes_sticks_and_stones_active()&&game_engine_allow_pick_up(1,2));
    options.secondary_weapon=_loadout_weapon_none;
    CHECK(game_modes_sticks_and_stones_active());
    options.vehicle_set[1]=4;
    CHECK(!game_modes_sticks_and_stones_active());
    options.vehicle_set[1]=1;
    SET_FLAG(variant.universal_variant.flags,_game_variant_infinite_grenades_bit,FALSE);
    CHECK(!game_modes_sticks_and_stones_active());
    for(i=0;i<9;i++) {
        if(i==7) continue;
        game_modes_gametype(i,&variant,&options);
        CHECK(!game_modes_sticks_and_stones(&variant,&options));
    }
    CHECK(game_modes_gametype_by_name("vip",&variant,&options));
    CHECK(variant.game_engine_index==game_engine_slayer&&variant.universal_variant.teams);
    CHECK(options.mode==_game_mode_vip&&options.force_team_spawns&&!options.auto_team_balance);
    CHECK(options.friendly_fire==_friendly_fire_off&&options.time_limit==0);
    CHECK(!TEST_FLAG(variant.universal_variant.flags,_game_variant_no_shields_bit));
    CHECK(options.vehicle_set[0]==1&&options.vehicle_set[1]==1);
    printf("PASS Sticks & Stones and VIP presets: %d native checks\n",checks);
    return 0;
}
