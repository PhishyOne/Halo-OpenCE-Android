/* Bounded, Android-only per-match highlight *markers*. These are timestamps
 * and events, NOT video and NOT a reconstructed HREC replay. */
#ifndef HALO_HIGHLIGHT_MOMENTS_H
#define HALO_HIGHLIGHT_MOMENTS_H

void halo_highlights_start(void);
/* A kill credited to the local player by Halo's existing scoreboard. */
void halo_highlights_kill(int bot_victim, long streak, long game_tick);
/* An actual flag capture credited to the local player in the CTF engine. */
void halo_highlights_ctf_capture(long game_tick);
/* Called only after a durable local HC2 career record was saved. */
void halo_highlights_finish(const char *root, const char *match_id,
    const char *map, long game_tick, int won);

#endif
