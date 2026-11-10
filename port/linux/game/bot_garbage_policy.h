#ifndef HALO_BOT_GARBAGE_POLICY_H
#define HALO_BOT_GARBAGE_POLICY_H

/*
 * Offline stress matches can contain 127 simulated players. Those bots
 * have no rendered camera, but the original garbage collector counts
 * every player's viewport when preserving visible bodies and scales its
 * corpse threshold linearly with the number of players.
 *
 * Only large, local multiplayer bot matches use this policy. Campaign,
 * co-op, real network games and smaller matches retain existing behavior.
 * We never change the eligibility of living units or bypass the human
 * player's visibility protection.
 */
static int halo_stress_bot_garbage_mode(
    int is_multiplayer, int local_splitscreen, long players)
{
    return is_multiplayer && local_splitscreen && players >= 48;
}

static long halo_active_garbage_scale_players(long players, int stress)
{
    long count = players < 16 ? 16 : players;

    /* Keep bounded dead-body budgets in 48..128-bot stress matches;
     * the original 128-player scale could defer active GC until ~400. */
    if (stress && count > 32)
        count = 32;
    return count;
}

static int halo_bot_is_nonviewing_garbage_player(
    int stress, long machine_index, long local_bot_first_machine)
{
    return stress && machine_index >= local_bot_first_machine;
}
#endif
