/* Test source-extracted production driver reservation and capacity gates.
 * A simulated driver cannot steal a human seat or exceed our six-driver cap.
 */
#include <stdio.h>
#include <string.h>
#define NONE (-1)
#define TRUE 1
#define FALSE 0
#define BOTS_MAXIMUM 8
#define BOTS_DRIVER_MAX_ACTIVE 6
#define _bots_goal_vehicle 4
typedef int boolean;
struct bots_bot {
    long player_index, vehicle_index, driver_vehicle_index;
    short goal_kind;
    boolean driver_seat_reserved;
};
static struct { struct bots_bot bots[BOTS_MAXIMUM]; } bots_globals;
#include "claim.inc"
#include "capacity.inc"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "line %d: %s\n",__LINE__,#x);return 1;} } while(0)

int main(void) {
    struct bots_bot *me=&bots_globals.bots[0];
    int i;
    for (i=0; i<BOTS_MAXIMUM; i++) {
        bots_globals.bots[i].player_index=NONE;
        bots_globals.bots[i].vehicle_index=NONE;
        bots_globals.bots[i].driver_vehicle_index=NONE;
    }
    me->player_index=42;
    CHECK(!bots_vehicle_driver_claimed(me,100));
    CHECK(!bots_vehicle_driver_limit_reached(me));
    /* A gunner's seat reservation must not block a driver. */
    bots_globals.bots[1].player_index=1;
    bots_globals.bots[1].goal_kind=_bots_goal_vehicle;
    bots_globals.bots[1].vehicle_index=100;
    bots_globals.bots[1].driver_seat_reserved=0;
    CHECK(!bots_vehicle_driver_claimed(me,100));
    CHECK(!bots_vehicle_driver_limit_reached(me));
    /* One valid driver claim blocks competing bots only on that car. */
    bots_globals.bots[1].driver_seat_reserved=1;
    CHECK(bots_vehicle_driver_claimed(me,100));
    CHECK(!bots_vehicle_driver_claimed(me,200));
    CHECK(!bots_vehicle_driver_limit_reached(me));
    bots_globals.bots[1].driver_seat_reserved=0;
    bots_globals.bots[1].driver_vehicle_index=100;
    CHECK(bots_vehicle_driver_claimed(me,100));
    bots_globals.bots[1].player_index=NONE;
    CHECK(!bots_vehicle_driver_claimed(me,100));

    for (i=1;i<=6;i++) {
        bots_globals.bots[i].player_index=i;
        bots_globals.bots[i].driver_vehicle_index=100+i;
    }
    CHECK(bots_vehicle_driver_limit_reached(me));
    bots_globals.bots[6].player_index=NONE;
    CHECK(!bots_vehicle_driver_limit_reached(me));
    /* An occupant is never counted twice when both fields are set. */
    bots_globals.bots[2].goal_kind=_bots_goal_vehicle;
    bots_globals.bots[2].vehicle_index=102;
    bots_globals.bots[2].driver_seat_reserved=1;
    CHECK(!bots_vehicle_driver_limit_reached(me));
    puts("PASS source-extracted driver seat reservation and capacity");
    return 0;
}
