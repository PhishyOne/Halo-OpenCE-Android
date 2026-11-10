/* A feature gate for Battlefield-2142-style tactical command support.
 * This is deliberately independent of the game engine: both host packet
 * validation and gameplay call sites use the same fail-closed policy. */
#ifndef HALO_COMMANDER_FEATURE_POLICY_H
#define HALO_COMMANDER_FEATURE_POLICY_H

enum {
    COMMANDER_ENABLED = 1u << 0,
    COMMANDER_SATELLITE = 1u << 1,
    COMMANDER_UAV = 1u << 2,
    COMMANDER_SPOTTING = 1u << 3,
    COMMANDER_ORDERS = 1u << 4,
    COMMANDER_KILLSTREAKS = 1u << 5,
    COMMANDER_ORBITAL = 1u << 6,
    COMMANDER_ANNOUNCEMENTS = 1u << 7,
    COMMANDER_KNOWN_MASK = 0xffu
};

/* Team multiplayer only. The master bit must be set. Orbital rewards are
 * a killstreak: disabling killstreaks must disable orbital even if its own
 * checkbox is on. Keep the preferences so they can be enabled later. */
static unsigned int commander_policy_effective(
    unsigned int selected, int team_multiplayer)
{
    if (!team_multiplayer || !(selected & COMMANDER_ENABLED))
        return 0;
    selected &= COMMANDER_KNOWN_MASK;
    if (!(selected & COMMANDER_KILLSTREAKS))
        selected &= ~COMMANDER_ORBITAL;
    return selected;
}

static int commander_policy_allows(unsigned int effective, unsigned int feature)
{
    return feature != 0 && (feature & (feature - 1)) == 0 &&
        (feature & COMMANDER_KNOWN_MASK) != 0 &&
        (effective & COMMANDER_ENABLED) != 0 &&
        (effective & feature) == feature;
}
#endif
