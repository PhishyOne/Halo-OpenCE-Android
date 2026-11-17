/* A host-frozen result, independent of the client's mutable scoreboard. */
#ifndef HALO_CAREER_RESULTS_H
#define HALO_CAREER_RESULTS_H
#include "game/game.h"
#include "highlight_moments.h"
#include <stdint.h>
struct career_result {
    int32_t player_index, game_type, end_tick, won;
    int32_t humans, bots, best_streak, moment_count;
    int32_t incomplete; /* checkpoint: earned stats, no completion or victory */
    struct game_statistics statistics;
    struct halo_highlight_event moments[3];
};
void career_results_start(void);
void career_results_kill(long player_index, int bot_victim, long streak);
void career_results_capture(long player_index);
/* After end-of-match cleanup, before switching the network to postgame. */
void career_results_finish(long game_type);
void career_results_update(long game_type);
const struct career_result *career_results_get(long player_index, long game_type);
void career_results_send_start(long machine_index);
/* Returns true for an owned message kind, even if malformed and rejected. */
int career_results_handle(long machine_index, const void *data, unsigned short size,
    int reliable);
#endif
