/* Bounded event markers, not video. Hosts select each player's moments;
 * Android writes only its own durable match's sidecar. */
#ifndef HALO_HIGHLIGHT_MOMENTS_H
#define HALO_HIGHLIGHT_MOMENTS_H
#include <stdint.h>
enum { HIGHLIGHT_EVENT_CAP = 256 };
enum highlight_code {
    highlight_bot = 1, highlight_human, highlight_spree3, highlight_spree5,
    highlight_spree10, highlight_capture, highlight_win
};
struct halo_highlight_event { int32_t tick, code, score; };
struct halo_highlight_buffer {
    struct halo_highlight_event events[HIGHLIGHT_EVENT_CAP];
    int count, finalized;
};
void halo_highlight_reset(struct halo_highlight_buffer *buffer);
void halo_highlight_kill(struct halo_highlight_buffer *buffer,
    int bot_victim, long streak, long tick);
void halo_highlight_capture(struct halo_highlight_buffer *buffer, long tick);
int halo_highlight_select(struct halo_highlight_buffer *buffer,
    long end_tick, int won, struct halo_highlight_event out[3]);
int halo_highlight_valid(const struct halo_highlight_event *event, long end_tick);
#ifdef HALO_ANDROID
void halo_highlights_start(void);
void halo_highlights_kill(int bot_victim, long streak, long tick);
void halo_highlights_ctf_capture(long tick);
void halo_highlights_import(const struct halo_highlight_event *events, int count);
void halo_highlights_finish(const char *root, const char *match_id,
    const char *map, long end_tick, int won);
#endif
#endif
