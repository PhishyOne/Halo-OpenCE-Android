/* Tests of the exact production flipped-vehicle filter and per-vehicle
   recovery reservation. No maps, renderer or network are needed. */
#include "harness.h"
#include <math.h>

#define BOTS_MAXIMUM 16
#define TICKS_PER_SECOND 30
#define BOTS_VEHICLE_SEEK_RADIUS 16.0f
#define BOTS_VEHICLE_FLIP_APPROACH_RADIUS 1.4f
#define BOTS_VEHICLE_FLIP_RESERVATION_TICKS (TICKS_PER_SECOND * 8)
#define BOTS_VEHICLE_RETRY_TICKS (TICKS_PER_SECOND * 12)
#define REAL_MAX 10000000.0f
enum {
    _object_dead_bit = 5,
    _unit_not_enterable_by_player_bit = 6,
    _vehicle_upending_bit = 7,
    _object_mask_vehicle = 8,
    _bots_goal_none = 0,
    _bots_goal_vehicle_flip = 9
};
struct vehicle_datum {
    struct {
        unsigned long damage_flags;
        real_point3d position;
        real_vector3d translational_velocity;
        real_vector3d up;
        real_vector3d angular_velocity;
    } object;
    struct {
        unsigned long flags;
        long driver_object_index;
    } unit;
    struct {
        unsigned long flags;
    } vehicle;
};
struct bots_bot {
    long player_index, vehicle_index;
    short goal_kind, vehicle_seat_index;
    long vehicle_ignore_index, vehicle_ignore_until_tick, next_vehicle_scan_tick;
    long goal_tick, goal_surface_index;
    real_point3d goal_point;
    struct { boolean valid; } path;
};
static struct vehicle_datum vehicle;
static struct { struct bots_bot bots[BOTS_MAXIMUM]; } bots_globals;

static struct vehicle_datum *vehicle_datum_try_and_get(long index)
{
    return index == 33 ? &vehicle : NULL;
}

static long current_tick=100;
static boolean ground_available=TRUE;
static long game_time_get(void) { return current_tick; }
static real bots_distance3d(real_point3d const *a, real_point3d const *b)
{
    real dx=a->x-b->x, dy=a->y-b->y, dz=a->z-b->z;
    return sqrtf(dx*dx+dy*dy+dz*dz);
}
static long bots_surface_below(real_point3d const *point, real_point3d *ground)
{
    if (!ground_available)
        return NONE;
    *ground=*point;
    return 42;
}
static void bots_set_goal(struct bots_bot *bot, short goal, real_point3d const *point, long surface)
{
    bot->goal_kind=goal;
    bot->goal_point=*point;
    bot->goal_surface_index=surface;
    bot->goal_tick=current_tick;
}
static void platform_log(char const *format, ...)
{
    (void)format;
}
struct object_iterator { long index; int next; };
static void object_iterator_new(struct object_iterator *it, long mask, long flags)
{
    (void)mask; (void)flags;
    it->index=0;
    it->next=0;
}
static boolean object_iterator_next(struct object_iterator *it)
{
    if (it->next++)
        return FALSE;
    it->index=33;
    return TRUE;
}
static struct vehicle_datum *vehicle_datum_get(long index)
{
    return index==33 ? &vehicle : NULL;
}

#include "under_test_flip_eligible.inc"
#include "under_test_flip_reserved.inc"
#include "under_test_flip_pick.inc"
#include "under_test_flip_keep.inc"
#include "under_test_flip_stop.inc"

int main(int argc, char **argv)
{
    char const *case_name = argc > 1 ? argv[1] : "";
    struct bots_bot *bot = &bots_globals.bots[0], *other = &bots_globals.bots[1];
    int n;

    memset(&vehicle, 0, sizeof(vehicle));
    vehicle.object.up.k = -1.0f;
    vehicle.unit.driver_object_index = NONE;
    for (n = 0; n < BOTS_MAXIMUM; n++)
        bots_globals.bots[n].player_index = NONE;
    bot->player_index = 1;
    bot->goal_kind = _bots_goal_vehicle_flip;
    bot->vehicle_index = 33;
    bot->goal_tick = current_tick;
    bot->vehicle_ignore_index = NONE;
    bot->goal_surface_index = NONE;
    vehicle.object.position.x = 3.0f;

    CASE("overturned-eligible") {
        CHECK(bots_vehicle_can_flip(33), "stationary flipped car refused");
        return 0;
    }
    CASE("upright-rejected") {
        vehicle.object.up.k = 1.0f;
        CHECK(!bots_vehicle_can_flip(33), "upright car accepted");
        return 0;
    }
    CASE("driver-rejected") {
        vehicle.unit.driver_object_index = 400;
        CHECK(!bots_vehicle_can_flip(33), "occupied driver's seat accepted");
        return 0;
    }
    CASE("upending-rejected") {
        vehicle.vehicle.flags = FLAG(_vehicle_upending_bit);
        CHECK(!bots_vehicle_can_flip(33), "already recovering car accepted");
        return 0;
    }
    CASE("destroyed-rejected") {
        vehicle.object.damage_flags = FLAG(_object_dead_bit);
        CHECK(!bots_vehicle_can_flip(33), "destroyed car accepted");
        return 0;
    }
    CASE("disabled-rejected") {
        vehicle.unit.flags = FLAG(_unit_not_enterable_by_player_bit);
        CHECK(!bots_vehicle_can_flip(33), "non-enterable car accepted");
        return 0;
    }
    CASE("rotating-rejected") {
        vehicle.object.angular_velocity.i = 1.0f;
        CHECK(!bots_vehicle_can_flip(33), "rolling vehicle accepted");
        return 0;
    }
    CASE("invalid-rejected") {
        CHECK(!bots_vehicle_can_flip(99), "invalid datum accepted");
        return 0;
    }
    CASE("one-rescuer") {
        other->player_index = 2;
        other->goal_kind = _bots_goal_vehicle_flip;
        other->vehicle_index = 33;
        CHECK(bots_vehicle_flip_reserved(bot, 33), "second bot did not yield");
        return 0;
    }
    CASE("other-vehicle-not-reserved") {
        other->player_index = 2;
        other->goal_kind = _bots_goal_vehicle_flip;
        other->vehicle_index = 44;
        CHECK(!bots_vehicle_flip_reserved(bot, 33), "different wreck blocked");
        return 0;
    }
    CASE("ignore-unspawned-reservation") {
        other->player_index = NONE;
        other->goal_kind = _bots_goal_vehicle_flip;
        other->vehicle_index = 33;
        CHECK(!bots_vehicle_flip_reserved(bot, 33), "inactive bot blocked recovery");
        return 0;
    }
    CASE("ignore-other-goal") {
        other->player_index = 2;
        other->goal_kind = 0;
        other->vehicle_index = 33;
        CHECK(!bots_vehicle_flip_reserved(bot, 33), "roaming bot reserved vehicle");
        return 0;
    }
    CASE("ignore-own-reservation") {
        CHECK(!bots_vehicle_flip_reserved(bot, 33), "bot blocked its own flip");
        return 0;
    }
    CASE("approach-flipped") {
        bot->goal_kind=0; bot->vehicle_index=NONE;
        real_point3d position={ 0.0f, 0.0f, 0.0f };
        CHECK(bots_vehicle_pick_flip(bot, &position), "did not choose nearby flipped vehicle");
        CHECK(bot->goal_kind==_bots_goal_vehicle_flip && bot->vehicle_index==33 &&
              bot->vehicle_seat_index==NONE && bot->goal_surface_index==42 &&
              bot->goal_tick==current_tick && bot->goal_point.x<3.0f,
              "invalid flipped vehicle approach goal");
        return 0;
    }
    CASE("do-not-approach-upright") {
        real_point3d position={ 0.0f, 0.0f, 0.0f };
        vehicle.object.up.k=1.0f;
        CHECK(!bots_vehicle_pick_flip(bot, &position), "chased upright Warthog as wreck");
        return 0;
    }
    CASE("do-not-crowd-reserved") {
        real_point3d position={ 0.0f, 0.0f, 0.0f };
        other->player_index=2;
        other->goal_kind=_bots_goal_vehicle_flip;
        other->vehicle_index=33;
        CHECK(!bots_vehicle_pick_flip(bot, &position), "two bots chased same wreck");
        return 0;
    }
    CASE("do-not-approach-moving") {
        real_point3d position={ 0.0f, 0.0f, 0.0f };
        vehicle.object.translational_velocity.i=1.0f;
        CHECK(!bots_vehicle_pick_flip(bot, &position), "chased moving wreck");
        return 0;
    }
    CASE("do-not-approach-too-far") {
        real_point3d position={ 100.0f, 0.0f, 0.0f };
        CHECK(!bots_vehicle_pick_flip(bot, &position), "chased distant wreck");
        return 0;
    }
    CASE("do-not-approach-without-ground") {
        real_point3d position={ 0.0f, 0.0f, 0.0f };
        ground_available=FALSE;
        CHECK(!bots_vehicle_pick_flip(bot, &position), "chased unreachable vehicle");
        return 0;
    }
    CASE("do-not-retry-ignored-wreck") {
        real_point3d position={ 0.0f, 0.0f, 0.0f };
        bot->vehicle_ignore_index=33;
        bot->vehicle_ignore_until_tick=current_tick+10;
        CHECK(!bots_vehicle_pick_flip(bot, &position), "repeated failed flip immediately");
        return 0;
    }
    CASE("recovery-reservation-expires") {
        bot->goal_tick=current_tick - BOTS_VEHICLE_FLIP_RESERVATION_TICKS;
        CHECK(!bots_vehicle_keep_flip_goal(bot), "indefinite wait around wreck");
        return 0;
    }
    CASE("recovery-reservation-valid") {
        CHECK(bots_vehicle_keep_flip_goal(bot), "early abandonment");
        return 0;
    }
    CASE("recovery-after-righting-ends") {
        vehicle.object.up.k=1.0f;
        CHECK(!bots_vehicle_keep_flip_goal(bot), "kept chasing righted Warthog");
        return 0;
    }
    CASE("release-reservation-cooldown") {
        bot->path.valid=TRUE;
        bots_vehicle_stop_seeking(bot, current_tick);
        CHECK(bot->vehicle_index==NONE && bot->goal_kind==0 &&
              !bot->path.valid && bot->vehicle_ignore_index==33 &&
              bot->vehicle_ignore_until_tick>current_tick,
              "failed to leave stalled flipped Warthog");
        return 0;
    }
    fprintf(stderr, "Unknown scenario: %s\n", case_name);
    return 2;
}
