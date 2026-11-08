/* Test the source-extracted production vehicle-seat filter and Action input.
   All game state and object/seat tags here are fake; no game maps needed. */
#define TICKS_PER_SECOND 30
#include "harness.h"
typedef struct { float x, y, z; } point3;
struct tag_block { int count; };
struct unit_seat { unsigned long flags; char label[32]; };
struct unit_definition { struct { struct tag_block seats; } unit; };
struct unit_datum {
    struct { unsigned long damage_flags; } object;
    struct { unsigned long flags; long driver_object_index; } unit;
    int definition_index;
};
struct object_datum { struct { long parent_object_index; } object; };
struct player_datum {
    long action_object_index;
    short action_seat_index, action_result;
};
struct bots_bot {
    short goal_kind;
    long vehicle_index;
    short vehicle_seat_index;
    long vehicle_press_tick;
};
struct bots_tick {
    struct player_datum *player;
    struct object_datum *object;
    long now;
    unsigned long flags;
};
enum {
    _unit_not_enterable_by_player_bit = 1,
    _unit_seat_driver_bit = 2,
    _unit_seat_gunner_bit = 3,
    _object_dead_bit = 5,
    _object_mask_vehicle = 4,
    _bots_goal_vehicle = 6,
    _unit_control_action_bit = 9,
    _player_action_result_enter_vehicle = 12,
    UNIT_CONTROL_PORT_ACTION_ONLY_BIT = 15
};
#define TAG_BLOCK_GET_ELEMENT(block, index, type) (&test_seats[index])
#define real_point3d point3
static struct unit_seat test_seats[3];
static struct unit_definition test_definition;
static struct unit_datum test_vehicle;
static int animation_ok = 1, seat_available = 1;
static int seat_entry_calls = 0, direct_board_calls = 0;
static void *object_try_and_get_and_verify_type(long index, long type)
{
    return index == 33 && type == _object_mask_vehicle ? &test_vehicle : NULL;
}
static struct unit_definition *unit_definition_get(long index)
{
    (void)index;
    return &test_definition;
}
static boolean unit_has_animation_to_enter_seat(long u, long v, short s)
{
    (void)u; (void)v; (void)s;
    return animation_ok;
}
static boolean unit_can_enter_seat(long u, long v, short s, long *out)
{
    (void)u; (void)v; (void)s; (void)out;
    return seat_available;
}
static boolean unit_get_seat_entrance_point(long u, long v, short s, point3 *entry,
                                             point3 *exit, point3 *seat)
{
    (void)u; (void)v; (void)exit; (void)seat;
    seat_entry_calls++;
    entry->x = (float)s; entry->y = 2.0f; entry->z = 0.0f;
    return TRUE;
}
#include "under_test_seat.inc"
#include "under_test_action.inc"

int main(int argc, char **argv)
{
    char const *case_name = argc > 1 ? argv[1] : "";
    point3 entrance = {0};
    boolean gunner = FALSE, accepted;
    struct bots_bot bot = {0};
    struct object_datum object = {0};
    struct player_datum player = {0};
    struct bots_tick tick = {0};

    memset(test_seats, 0, sizeof(test_seats));
    strcpy(test_seats[0].label, "driver");
    strcpy(test_seats[1].label, "gunner");
    strcpy(test_seats[2].label, "passenger");
    test_seats[0].flags = FLAG(_unit_seat_driver_bit);
    test_seats[1].flags = FLAG(_unit_seat_gunner_bit);
    test_definition.unit.seats.count = 3;
    test_vehicle.unit.driver_object_index = NONE;
    object.object.parent_object_index = NONE;
    tick.object = &object;
    tick.player = &player;
    tick.now = 100;
    bot.goal_kind = _bots_goal_vehicle;
    bot.vehicle_index = 33;
    bot.vehicle_seat_index = 1;

    CASE("gunner-empty-vehicle")
    {
        accepted = bots_vehicle_seat_usable(7, 33, 1, &entrance, &gunner);
        CHECK(accepted && gunner && entrance.x == 1.0f && seat_entry_calls == 1,
              "vacant Warthog gunner seat was not accepted");
        return 0;
    }
    CASE("no-driver-control")
    {
        accepted = bots_vehicle_seat_usable(7, 33, 0, &entrance, &gunner);
        CHECK(!accepted && seat_entry_calls == 0 && !direct_board_calls,
              "bot tried to take a driver's seat");
        return 0;
    }
    CASE("passenger-needs-driver")
    {
        accepted = bots_vehicle_seat_usable(7, 33, 2, &entrance, &gunner);
        CHECK(!accepted, "bot tried to sit in an unoccupied vehicle");
        test_vehicle.unit.driver_object_index = 77;
        accepted = bots_vehicle_seat_usable(7, 33, 2, &entrance, &gunner);
        CHECK(accepted && !gunner, "passenger with a driver was refused");
        return 0;
    }
    CASE("occupied-seat")
    {
        seat_available = 0;
        CHECK(!bots_vehicle_seat_usable(7, 33, 1, &entrance, &gunner),
              "bot stole an already occupied turret");
        return 0;
    }
    CASE("disabled-vehicle")
    {
        test_vehicle.unit.flags = FLAG(_unit_not_enterable_by_player_bit);
        CHECK(!bots_vehicle_seat_usable(7, 33, 1, &entrance, &gunner),
              "entered a vehicle disabled by the engine");
        return 0;
    }
    CASE("dead-vehicle")
    {
        test_vehicle.object.damage_flags = FLAG(_object_dead_bit);
        CHECK(!bots_vehicle_seat_usable(7, 33, 1, &entrance, &gunner),
              "entered a destroyed vehicle");
        return 0;
    }
    CASE("invalid-vehicle")
    {
        CHECK(!bots_vehicle_seat_usable(7, 99, 1, &entrance, &gunner),
              "invalid vehicle reference was accepted");
        return 0;
    }
    CASE("no-animation")
    {
        animation_ok = 0;
        CHECK(!bots_vehicle_seat_usable(7, 33, 1, &entrance, &gunner),
              "entered a seat with no Spartan animation");
        return 0;
    }
    CASE("empty-seat-label")
    {
        test_seats[1].label[0] = 0;
        CHECK(!bots_vehicle_seat_usable(7, 33, 1, &entrance, &gunner),
              "accepted an unlabeled vehicle seat");
        return 0;
    }
    CASE("action-press")
    {
        player.action_object_index = 33;
        player.action_seat_index = 1;
        player.action_result = _player_action_result_enter_vehicle;
        bots_bot_vehicle_action(&bot, &tick);
        CHECK(TEST_FLAG(tick.flags, _unit_control_action_bit) &&
              TEST_FLAG(tick.flags, UNIT_CONTROL_PORT_ACTION_ONLY_BIT) &&
              bot.vehicle_press_tick == 100 + 30 && direct_board_calls == 0,
              "bot did not use the normal player Action key");
        return 0;
    }
    CASE("wrong-action")
    {
        player.action_object_index = 33;
        player.action_seat_index = 0;
        player.action_result = _player_action_result_enter_vehicle;
        bots_bot_vehicle_action(&bot, &tick);
        CHECK(tick.flags == 0, "pressed Action for the wrong seat");
        return 0;
    }
    CASE("already-seated")
    {
        object.object.parent_object_index = 33;
        player.action_object_index = 33;
        player.action_seat_index = 1;
        player.action_result = _player_action_result_enter_vehicle;
        bots_bot_vehicle_action(&bot, &tick);
        CHECK(tick.flags == 0, "pressed Action while already seated");
        return 0;
    }
    CASE("cooldown")
    {
        player.action_object_index = 33;
        player.action_seat_index = 1;
        player.action_result = _player_action_result_enter_vehicle;
        bot.vehicle_press_tick = 150;
        bots_bot_vehicle_action(&bot, &tick);
        CHECK(tick.flags == 0, "spammed Action inside cooldown");
        return 0;
    }
    fprintf(stderr, "Unknown test case: %s\n", case_name);
    return 2;
}
