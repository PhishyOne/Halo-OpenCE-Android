#ifndef HALO_LOBBY_BOT_COUNT_POLICY_H
#define HALO_LOBBY_BOT_COUNT_POLICY_H

/* Choosing bots while WAITING in the lobby. No mid-match player changes.
 * Offline permits 127 simulated bots, LAN permits up to 16. The host's
 * existing pregame reconciler adds/removes the bots on its next idle tick. */
static int halo_lobby_next_bot_count(int current, int delta, int lan)
{
    static const int offline[] = {0,4,8,12,16,24,32,48,64,96,127};
    static const int network[] = {0,2,4,6,8,12,16};
    const int *counts = lan ? network : offline;
    int maximum = lan ? (int)(sizeof(network)/sizeof(network[0])) :
        (int)(sizeof(offline)/sizeof(offline[0]));
    int i;

    if (delta > 0) {
        for (i=0;i<maximum;i++)
            if (counts[i] > current)
                return counts[i];
        return counts[maximum-1];
    }
    if (delta < 0) {
        for (i=maximum-1;i>=0;i--)
            if (counts[i] < current)
                return counts[i];
        return counts[0];
    }
    return current;
}
#endif
