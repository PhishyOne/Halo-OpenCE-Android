/* Exact-source standalone test for the Scorpion/Ghost driver-gunner
 * action writer. Production function is extracted into under_test_gun.inc.
 * None of the game's vehicle objects or player data are modified. */
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <stddef.h>
#include "bot_vehicle_crew_policy.h"
#define TRUE 1
#define FALSE 0
#define NONE (-1L)
#define TICKS_PER_SECOND 30
#define MAX(a,b) ((a)>(b)?(a):(b))
#define FLAG(b) (1UL<<(b))
#define TEST_FLAG(flags,bit) (((flags)&FLAG(bit))!=0)
#define DATUM_INDEX_TO_ABSOLUTE_INDEX(value) (value)
typedef int boolean;
typedef float real;
typedef struct { real x,y,z; } real_point3d;
struct object_datum {
    struct {
        int damage_flags;
        real_point3d position, bounding_sphere_center;
        struct { real i,j,k; } forward, up;
    } object;
};
struct unit_datum {
    struct {
        int damage_flags;
        real_point3d position, bounding_sphere_center;
        struct { real i,j,k; } forward, up;
    } object;
    struct {
        long driver_object_index;
        long gunner_object_index;
    } unit;
    int definition_index;
};
struct player_datum { long unit_index; };
struct player_action {
    struct { float yaw,pitch; } desired_facing;
    struct { float i,j; } throttle;
    unsigned long control_flags;
    float primary_trigger;
};
struct bots_bot {
    long target_player_index, target_seen_tick, burst_until_tick;
    long player_index;
    float yaw,pitch;
};
struct bots_tick {
    struct player_datum *player;
    struct player_action *action;
    long now;
    long weapon_index;
};
enum { _object_dead_bit=3, _unit_control_weapon_primary_trigger_bit=11 };
static int enabled=1, armed=1, hostile=1, seen=1, looks=0, log_count=0;
static struct player_datum enemy_player={33};
static struct object_datum enemy;
static char const *car_name="vehicles\\scorpion\\scorpion";
static boolean config_boolean(char const *name) {
    return !strcmp(name,"bots.vehicle_driving") && enabled;
}
static char const *tag_get_name(int definition) {
    (void)definition;
    return car_name;
}
static long bots_current_weapon(struct unit_datum *vehicle) {
    (void)vehicle;
    return armed?66:NONE;
}
static real bots_angle_difference(real a, real b) {
    return atan2f(sinf(a-b),cosf(a-b));
}
static struct player_datum *player_try_and_get(long index) {
    return index==22 ? &enemy_player : NULL;
}
static struct object_datum *object_try_and_get(long index) {
    return index==33 ? &enemy : NULL;
}
static boolean bots_is_enemy(struct player_datum *bot,struct player_datum *target) {
    (void)bot;(void)target;
    return hostile;
}
static void bots_bot_look(struct bots_bot *bot,struct bots_tick *tick) {
    looks++;
    if (seen) {
        bot->target_player_index=22;
        bot->target_seen_tick=tick->now;
    }
}
static void platform_log(char const *format,...) {
    (void)format;
    log_count++;
}
#include "under_test_gun.inc"
#define CHECK(x) do { if(!(x)){fprintf(stderr,"FAILED line %d: %s\n",__LINE__,#x);return 1;} }while(0)
static void prepare(struct unit_datum *car,struct bots_tick *tick,
                    struct bots_bot *bot,struct player_action *action,
                    struct player_datum *self)
{
    memset(car,0,sizeof(*car));memset(tick,0,sizeof(*tick));
    memset(bot,0,sizeof(*bot));memset(action,0,sizeof(*action));
    self->unit_index=7;
    car->unit.driver_object_index=7;
    car->unit.gunner_object_index=7;
    car->object.up.k=1;
    car->object.forward.i=1;
    car->object.bounding_sphere_center.z=1;
    bot->player_index=99;
    bot->target_player_index=NONE;
    bot->target_seen_tick=NONE;
    action->throttle.i=0.4f;
    tick->player=self;
    tick->action=action;
    tick->now=100;
    enabled=armed=hostile=seen=1;
    enemy.object.position.x=30;
    enemy.object.bounding_sphere_center.x=30;
    enemy.object.bounding_sphere_center.y=0;
    enemy.object.bounding_sphere_center.z=1;
    enemy.object.damage_flags=0;
    car_name="vehicles\\scorpion\\scorpion";
}

int main(void) {
    struct unit_datum car;
    struct player_datum self;
    struct bots_tick tick;
    struct bots_bot bot;
    struct player_action action;
    prepare(&car,&tick,&bot,&action,&self);
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(looks==1 && log_count==1);
    CHECK(TEST_FLAG(action.control_flags,_unit_control_weapon_primary_trigger_bit));
    CHECK(action.primary_trigger==1.0f);
    CHECK(fabsf(action.desired_facing.yaw)<0.01f);
    CHECK(action.throttle.i==0.4f);
    CHECK(bot.burst_until_tick==130);

    /* A friend operating the tank's gun takes precedence. */
    prepare(&car,&tick,&bot,&action,&self);
    car.unit.gunner_object_index=8;
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0 && action.control_flags==0);

    prepare(&car,&tick,&bot,&action,&self);
    armed=0;
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0);

    prepare(&car,&tick,&bot,&action,&self);
    hostile=0;
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0);

    prepare(&car,&tick,&bot,&action,&self);
    enemy.object.bounding_sphere_center.x=3;
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0); /* no point-blank tank shell */

    prepare(&car,&tick,&bot,&action,&self);
    enemy.object.bounding_sphere_center.x=0;
    enemy.object.bounding_sphere_center.y=30;
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0);
    CHECK(action.desired_facing.yaw>1.55f && action.desired_facing.yaw<1.60f);

    prepare(&car,&tick,&bot,&action,&self);
    bot.burst_until_tick=105;
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0);

    prepare(&car,&tick,&bot,&action,&self);
    car.object.damage_flags=FLAG(_object_dead_bit);
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0);

    prepare(&car,&tick,&bot,&action,&self);
    car_name="vehicles\\warthog\\warthog";
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==0);

    prepare(&car,&tick,&bot,&action,&self);
    car_name="vehicles\\ghost\\ghost";
    bots_bot_ground_gun_engage(&bot,&tick,&car);
    CHECK(action.primary_trigger==1.0f);
    puts("Vehicle gun action PASS (hostile, range, heading, driver+gunner, cooldown)");
    return 0;
}
