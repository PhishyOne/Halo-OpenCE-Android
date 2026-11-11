/* The Commander role and individual Killstreak Rewards are independent.
 * The host controls both feature groups; a team commander is not required
 * for the 3/5/10-kill rewards. Neither group applies in campaign/co-op. */
#ifndef HALO_COMMANDER_FEATURE_POLICY_H
#define HALO_COMMANDER_FEATURE_POLICY_H

enum {
    COMMANDER_ENABLED = 1u << 0,
    COMMANDER_SATELLITE = 1u << 1,
    COMMANDER_UAV = 1u << 2,
    COMMANDER_SPOTTING = 1u << 3,
    COMMANDER_ORDERS = 1u << 4,
    KILLSTREAK_ENABLED = 1u << 5,
    KILLSTREAK_ORBITAL = 1u << 6,
    COMMANDER_ANNOUNCEMENTS = 1u << 7,
    KILLSTREAK_ANNOUNCEMENTS = 1u << 8,
    COMMANDER_FEATURE_MASK =
        COMMANDER_ENABLED | COMMANDER_SATELLITE | COMMANDER_UAV |
        COMMANDER_SPOTTING | COMMANDER_ORDERS | COMMANDER_ANNOUNCEMENTS,
    KILLSTREAK_FEATURE_MASK =
        KILLSTREAK_ENABLED | KILLSTREAK_ORBITAL | KILLSTREAK_ANNOUNCEMENTS,
    COMMANDER_KNOWN_MASK =
        COMMANDER_FEATURE_MASK | KILLSTREAK_FEATURE_MASK
};

/* Host-authoritative effective policy: Commander requires a team match;
 * independent human killstreaks may also be used in free-for-all. Neither
 * works in campaign, co-op or menus. Unselected masters clear children,
 * without erasing their saved settings. */
static unsigned int commander_policy_effective(
    unsigned int selected, int team_multiplayer, int multiplayer)
{
    if (!multiplayer)
        return 0;
    selected &= COMMANDER_KNOWN_MASK;
    if (!team_multiplayer || !(selected & COMMANDER_ENABLED))
        selected &= ~COMMANDER_FEATURE_MASK;
    if (!(selected & KILLSTREAK_ENABLED))
        selected &= ~KILLSTREAK_FEATURE_MASK;
    return selected;
}

static int commander_policy_allows(unsigned int effective, unsigned int feature)
{
    if (feature == 0 || (feature & (feature - 1)) != 0 ||
        !(COMMANDER_KNOWN_MASK & feature) || !(effective & feature))
        return 0;
    if (feature & COMMANDER_FEATURE_MASK)
        return (effective & COMMANDER_ENABLED) != 0;
    return (effective & KILLSTREAK_ENABLED) != 0;
}

static int killstreak_policy_allows(unsigned int effective, unsigned int feature)
{
    return (feature & KILLSTREAK_FEATURE_MASK) != 0 &&
        commander_policy_allows(effective, feature);
}
#endif
