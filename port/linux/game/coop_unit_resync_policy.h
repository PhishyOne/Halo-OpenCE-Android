#ifndef HALO_COOP_UNIT_RESYNC_POLICY_H
#define HALO_COOP_UNIT_RESYNC_POLICY_H

/*
 * Pure policy: when a co-op client repeatedly hears that the host has its
 * living player unit, but has no usable local copy, request the host's
 * reliable object snapshot again. Debounce the normal create-message race
 * and cap retries to one per host-side 10-second resend window.
 *
 * Does not create, kill, spawn, or attach a unit. Those operations remain
 * with the game's existing object and player replication code.
 */
struct coop_unit_resync_watch {
    long unit_index;
    long missing_since;
    long last_request;
    int active;
    int requested;
};

static int coop_unit_resync_should_request(struct coop_unit_resync_watch *watch,
    long unit_index, long now, int missing,
    long debounce_ticks, long retry_ticks)
{
    if (!watch)
        return 0;
    if (!missing) {
        watch->active = 0;
        watch->requested = 0;
        return 0;
    }
    if (!watch->active || watch->unit_index != unit_index ||
        now < watch->missing_since) {
        watch->active = 1;
        watch->unit_index = unit_index;
        watch->missing_since = now;
        watch->requested = 0;
        return 0;
    }
    if (now - watch->missing_since < debounce_ticks)
        return 0;
    if (watch->requested && now >= watch->last_request &&
        now - watch->last_request < retry_ticks)
        return 0;
    watch->last_request = now;
    watch->requested = 1;
    return 1;
}
#endif
