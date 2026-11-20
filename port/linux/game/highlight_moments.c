/* Bounded host-side highlight selection. Android alone writes the journal. */
#include "highlight_moments.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>

enum { HIGHLIGHT_FILE_MAX = 4 * 1024 * 1024 };

#ifdef HALO_ANDROID
static struct halo_highlight_buffer local;
static int name_valid(const char *name, int max_length, int hex_only)
{
    int count = 0;
    if (!name) return 0;
    while (name[count]) {
        char c;
        if (count >= max_length) return 0;
        c = name[count++];
        if (hex_only) {
            if (!((c >= '0' && c <= '9') ||
                  (c >= 'a' && c <= 'f'))) return 0;
        } else if (!((c >= 'a' && c <= 'z') ||
                     (c >= '0' && c <= '9') ||
                     c == '_' || c == '-')) return 0;
    }
    return hex_only ? count == 32 : count > 0;
}

#endif

void halo_highlight_reset(struct halo_highlight_buffer *buffer)
{
    memset(buffer, 0, sizeof(*buffer));
}

static void add(struct halo_highlight_buffer *buffer, long tick, int code, int score)
{
    int i, minimum = 0;
    if (buffer->finalized || tick < 0 || tick > 8640000 ||
        code < highlight_bot || code > highlight_win)
        return;
    if (buffer->count < HIGHLIGHT_EVENT_CAP) {
        minimum = buffer->count++;
    } else {
        for (i = 1; i < buffer->count; i++) {
            if (buffer->events[i].score < buffer->events[minimum].score)
                minimum = i;
        }
        if (score <= buffer->events[minimum].score) return;
    }
    buffer->events[minimum].tick = tick;
    buffer->events[minimum].code = code;
    buffer->events[minimum].score = score;
}

void halo_highlight_kill(struct halo_highlight_buffer *buffer,
    int bot_victim, long streak, long game_tick)
{
    int score, code;
    if (streak == 10) { code = highlight_spree10; score = 100; }
    else if (streak == 5) { code = highlight_spree5; score = 76; }
    else if (streak == 3) { code = highlight_spree3; score = 52; }
    else if (bot_victim) { code = highlight_bot; score = 10; }
    else { code = highlight_human; score = 28; }
    add(buffer, game_tick, code, score);
}

void halo_highlight_capture(struct halo_highlight_buffer *buffer, long game_tick)
{
    add(buffer, game_tick, highlight_capture, 93);
}

static int distance_ok(struct halo_highlight_event *candidate,
    struct halo_highlight_event *chosen[], int selected)
{
    int i;
    for (i = 0; i < selected; i++) {
        long dt = candidate->tick - chosen[i]->tick;
        if (dt < 0) dt = -dt;
        /* Four-second spacing avoids three clips of the same gunfight. */
        if (dt < 120) return 0;
    }
    return 1;
}

static void select_best(struct halo_highlight_buffer *buffer,
    struct halo_highlight_event *chosen[3], int *selected)
{
    int n, i;
    *selected = 0;
    for (n = 0; n < 3 && n < buffer->count; n++) {
        struct halo_highlight_event *best = NULL;
        for (i = 0; i < buffer->count; i++) {
            struct halo_highlight_event *e = &buffer->events[i];
            int used = 0, j;
            for (j = 0; j < *selected; j++)
                if (chosen[j] == e) used = 1;
            if (used || !distance_ok(e, chosen, *selected)) continue;
            if (!best || e->score > best->score ||
                (e->score == best->score && e->tick < best->tick))
                best = e;
        }
        if (!best) break;
        chosen[(*selected)++] = best;
    }
    /* Playback order is chronological, not score order. */
    for (i = 0; i < *selected; i++) {
        int j;
        for (j = i + 1; j < *selected; j++) {
            if (chosen[j]->tick < chosen[i]->tick) {
                struct halo_highlight_event *temp = chosen[i];
                chosen[i] = chosen[j];
                chosen[j] = temp;
            }
        }
    }
}

int halo_highlight_select(struct halo_highlight_buffer *buffer,
    long end_tick, int won, struct halo_highlight_event out[3])
{
    struct halo_highlight_event *chosen[3];
    int count, i;
    if (buffer->finalized) return 0;
    if (won) add(buffer, end_tick, highlight_win, 43);
    buffer->finalized = 1;
    select_best(buffer, chosen, &count);
    for (i = 0; i < count; i++) out[i] = *chosen[i];
    return count;
}

int halo_highlight_valid(const struct halo_highlight_event *event, long end_tick)
{
    static const int scores[] = {0, 10, 28, 52, 76, 100, 93, 43};
    return event->tick >= 0 && event->tick <= end_tick &&
        event->code >= highlight_bot && event->code <= highlight_win &&
        event->score == scores[event->code];
}

#ifdef HALO_ANDROID
/* root is the absolute Android app directory supplied by the recorder. */
#undef fopen
static const char *kind(int code)
{
    switch (code) {
        case highlight_bot: return "BOT_KILL";
        case highlight_human: return "HUMAN_KILL";
        case highlight_spree3: return "STREAK_3";
        case highlight_spree5: return "STREAK_5";
        case highlight_spree10: return "STREAK_10";
        case highlight_capture: return "FLAG_CAPTURE";
        case highlight_win: return "VICTORY";
        default: return "";
    }
}

void halo_highlights_start(void) { halo_highlight_reset(&local); }
void halo_highlights_kill(int bot_victim, long streak, long tick)
{
    halo_highlight_kill(&local, bot_victim, streak, tick);
}
void halo_highlights_ctf_capture(long tick) { halo_highlight_capture(&local, tick); }
void halo_highlights_import(const struct halo_highlight_event *events, int count)
{
    halo_highlights_start();
    if (count < 0 || count > 3) return;
    memcpy(local.events, events, count * sizeof(*events));
    local.count = count;
}

void halo_highlights_finish(const char *root, const char *match_id,
    const char *map, long game_tick, int won)
{
    char path[1024], entry[200];
    struct halo_highlight_event chosen[3];
    FILE *previous, *out;
    long old_bytes = 0;
    int i, selected = 0, needs_newline = 0, failed = 0;
    size_t output_bytes = 0;

    if (local.finalized) return;
    if (!root || !root[0] ||
        !name_valid(match_id, 32, 1) ||
        !name_valid(map, 47, 0) || game_tick < 0 || game_tick > 8640000) {
        local.finalized = 1;
        return;
    }
    selected = halo_highlight_select(&local, game_tick, won, chosen);
    if (selected == 0) return;
    if (snprintf(path, sizeof(path), "%s/%s", root,
                 "career-highlights-v1.log") >= (int)sizeof(path))
        return;
    previous = fopen(path, "rb");
    if (previous) {
        if (fseek(previous, 0, SEEK_END) != 0 ||
            (old_bytes = ftell(previous)) < 0) {
            fclose(previous);
            return;
        }
        if (old_bytes > 0 && fseek(previous, -1, SEEK_END) == 0)
            needs_newline = fgetc(previous) != '\n';
        fclose(previous);
    }
    if (old_bytes > HIGHLIGHT_FILE_MAX - (long)(sizeof(entry) * 3 + 1))
        return;
    out = fopen(path, "ab");
    if (!out) return;
    if (needs_newline && fwrite("\n", 1, 1, out) != 1)
        failed = 1;
    for (i = 0; i < selected && !failed; i++) {
        long tick = chosen[i].tick;
        long start = tick >= 150 ? tick - 150 : 0;
        long end = tick <= game_tick - 90 ? tick + 90 : game_tick;
        int size;
        if (end < tick) end = tick;
        size = snprintf(entry, sizeof(entry),
            "HLM1|%s|%s|%ld|%s|%d|%ld|%ld\n",
            match_id, map, tick, kind(chosen[i].code),
            chosen[i].score, start, end);
        if (size <= 0 || size >= (int)sizeof(entry) ||
            fwrite(entry, 1, (size_t)size, out) != (size_t)size)
            failed = 1;
        else output_bytes += (size_t)size;
    }
    {
        int synced = fflush(out) == 0 && fsync(fileno(out)) == 0;
        int closed = fclose(out) == 0;
        if (!synced || !closed) failed = 1;
    }
    /* This sidecar contains optional moments; failed recording never
     * invalidates the already-durable career match receipt. */
    (void)failed; (void)output_bytes;
}
#endif /* HALO_ANDROID */
