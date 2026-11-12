#ifndef HALO_COMMANDER_TACTICAL_POLICY_H
#define HALO_COMMANDER_TACTICAL_POLICY_H

#include "commander_feature_policy.h"

/* v22 Android proof of concept: the host's first viewport only.
 * A role picker and authenticated role replication are NOT present yet. */
static int commander_tactical_host_can_view(
    unsigned int effective_mask, int team_game, int host, int local_index,
    int player_is_human)
{
    return team_game && host && local_index == 0 && player_is_human &&
        (effective_mask & (COMMANDER_ENABLED | COMMANDER_SATELLITE)) ==
            (COMMANDER_ENABLED | COMMANDER_SATELLITE);
}

/* Project X/Y of a real structure BSP, not guessed per-map constants.
 * Refuse malformed/outside points, including NaNs and infinities, rather
 * than placing hostile or stale coordinates at the map's edge. */
static int commander_tactical_project(float x, float y,
    float min_x, float max_x, float min_y, float max_y,
    float *u, float *v)
{
    if (!u || !v || !(min_x > -32768.0f && min_x < 32768.0f) ||
        !(max_x > -32768.0f && max_x < 32768.0f) ||
        !(min_y > -32768.0f && min_y < 32768.0f) ||
        !(max_y > -32768.0f && max_y < 32768.0f) ||
        !(x >= min_x && x <= max_x && y >= min_y && y <= max_y) ||
        !(max_x - min_x > 0.001f && max_y - min_y > 0.001f))
        return 0;
    *u = (x - min_x) / (max_x - min_x);
    *v = (max_y - y) / (max_y - min_y); /* world north is screen up */
    return *u >= 0.0f && *u <= 1.0f && *v >= 0.0f && *v <= 1.0f;
}

/* No enemy positions. UAV and spotting require separate host-authenticated
 * intelligence; a client already replicating an enemy is NOT authorization. */
static int commander_tactical_ally(int viewer_team, int other_team)
{
    return (viewer_team == 0 || viewer_team == 1) &&
        other_team == viewer_team;
}

#endif /* HALO_COMMANDER_TACTICAL_POLICY_H */
