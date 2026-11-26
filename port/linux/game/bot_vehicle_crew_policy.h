/* Multiplayer bot vehicle crew decisions, without engine dependencies.
 * No seat teleportation, player team reassignment, or physics writes.
 * Used alongside Halo's native unit_can_enter_seat/Action prompt.
 */
#ifndef HALO_BOT_VEHICLE_CREW_POLICY_H
#define HALO_BOT_VEHICLE_CREW_POLICY_H

#include <float.h>
#include <math.h>

/* Only a subset can leave objective duties to crew vehicles. Driver
 * candidates retain the existing 1-in-5 policy; additional gunner
 * candidates are distributed through the roster. */
static int bot_crew_objective_candidate(long index, int driving)
{
    if (index < 0)
        return 0;
    return (driving && index % 5 == 0) || (index % 4 == 1);
}

/* Do not send an unqualified bot to the driver seat. Existing gunner
 * and passenger availability still depends on Halo's seat APIs. */
static int bot_crew_seat_role_allowed(int driver, int gunner, int driver_candidate)
{
    if (driver)
        return driver_candidate;
    return gunner || !driver;
}

/* v30 refused vehicles whose engine velocity magnitude exceeded
 * 0.02, which means it almost never approached a human-driven Warthog.
 * Allow a low-speed human-driven
 * turret only, not risky entry into fast cars or moving driver seats.
 * A real engine action prompt still decides if the vehicle can be entered. */
static int bot_crew_speed_safe(float speed_squared, int gunner,
    int human_driver)
{
    const float safe = gunner && human_driver ? 0.12f : 0.02f;
    return speed_squared >= 0.0f &&
        speed_squared <= safe * safe;
}

/* Score is low-is-preferred. A friendly human with an empty turret
 * deserves priority over an unmanned vehicle or a passenger seat.
 * Don't let an occupied enemy team's Warthog recruit a gunner. */
static float bot_crew_seat_score(float distance, int driver,
    int gunner, int friendly_human_driver)
{
    if (!(distance >= 0.0f && distance <= FLT_MAX))
        return 1e30f;
    if (gunner && friendly_human_driver)
        return distance - 8.0f;
    if (driver)
        return distance - 3.0f;
    return distance + (gunner ? 0.0f : 5.0f);
}

/* Only the actual driver+gunner in a ground combat vehicle can send
 * trigger controls. Require a recent, front-facing visible enemy at
 * a useful range; don't lob tank shells point-blank into teammates.
 * Final LOS/target hostility is already checked by bots_find_target. */
static int bot_crew_tank_fire_allowed(int is_driver_gunner, int armed,
    float distance, float yaw_error, float pitch_error, int age_ticks)
{
    return is_driver_gunner && armed && age_ticks >= 0 && age_ticks <= 4 &&
        distance >= 10.0f && distance <= 75.0f &&
        fabsf(yaw_error) <= 0.32f &&
        fabsf(pitch_error) <= 0.35f;
}

#endif
