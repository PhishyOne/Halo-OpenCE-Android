/* Low-speed, ground-only vehicle driver policy for simulated Halo players.
 * The engine still owns real vehicle physics, collision, seats and scoring.
 * Pure C: also compiled and tested independently with real steering inputs. */
#ifndef HALO_BOT_VEHICLE_DRIVER_POLICY_H
#define HALO_BOT_VEHICLE_DRIVER_POLICY_H

#include <math.h>
#include <string.h>

struct bot_driver_controls {
    float desired_yaw;
    float forward;
    float lateral;
};

/* Reject unknown/flight-only vehicles. A custom map with an unusual
 * definition is intentionally left unmanned rather than flown blindly. */
static int bot_driver_supported(char const *tag_name)
{
    return tag_name != NULL &&
        (strstr(tag_name, "vehicles\\") == tag_name ||
         strstr(tag_name, "vehicles/") == tag_name) &&
        (strstr(tag_name, "warthog") != NULL ||
         strstr(tag_name, "ghost") != NULL ||
         strstr(tag_name, "scorpion") != NULL);
}

/* Neutral input for invalid or reached goals. The vehicle's own controller
 * turns toward a desired facing vector, using throttle.i for forward drive.
 * Never alter a vehicle datum or inject physics forces directly. */
static struct bot_driver_controls bot_driver_steering(
    float x, float y, float heading,
    float target_x, float target_y, int reversing)
{
    struct bot_driver_controls result = { 0.0f, 0.0f, 0.0f };
    float dx, dy, distance, desired, error;
    /* No NaN, infinity or wildly invalid destinations in user controls. */
    if (!(fabs(x) < 1000000.0f && fabs(y) < 1000000.0f &&
          fabs(heading) < 1000000.0f &&
          fabs(target_x) < 1000000.0f && fabs(target_y) < 1000000.0f))
        return result;
    dx = target_x - x;
    dy = target_y - y;
    distance = sqrt(dx*dx + dy*dy);
    result.desired_yaw = heading;
    if (distance < 2.5f)
        return result;
    desired = atan2(dy, dx);
    error = atan2(sin(desired-heading), cos(desired-heading));
    if (reversing) {
        /* Reverse with a modest steering offset to break out of a wall.
         * A reversed car is still subject to the real physics and damage. */
        result.desired_yaw = heading + (error < 0.0f ? 0.65f : -0.65f);
        result.forward = -0.28f;
    } else {
        result.desired_yaw = desired;
        /* Slow bends sharply; never full throttle on busy LAN maps. */
        result.forward = fabs(error) > 1.25f ? 0.17f :
            fabs(error) > 0.65f ? 0.33f : 0.55f;
        if (distance < 5.0f)
            result.forward *= 0.6f;
    }
    return result;
}

#endif
