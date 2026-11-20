/*
 * Android v37 career recorder.
 * The multiplayer engine remains authoritative for its existing scoreboard;
 * this writes a local player's own postgame result to app-owned storage.
 * The record is deliberately NOT a verified multiplayer leaderboard result.
 */
#ifdef HALO_ANDROID
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/players.h"
#include "memory/data.h"
#include "career_record.h"
#include "career_results.h"
#include "networking/network_game_globals.h"
#include "highlight_moments.h"
/* The guest game build does not expose the platform layer's private
 * platform.h include directory. Declare the logging entry point here. */
extern void platform_log(const char *format, ...);

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { CAREER_MAX_BYTES = 2 * 1024 * 1024 };
/* All paths in this recorder are already Android paths. The game CRT's
 * Xbox drive translation would prefix even /dev/urandom with the data root. */
#undef fopen
#undef rename
static int recorded;
static char career_id[33];
static long career_attempted_tick;
static int career_recover_pending(void);

void career_record_start(void)
{
    halo_highlights_start();
    recorded = !career_recover_pending();
    if (recorded) platform_log("career: could not recover pending record");
    career_id[0] = 0;
    career_attempted_tick = -1;
}

/* A random 128-bit match ID prevents double counting on re-import.
 * If Android's kernel random source is inaccessible, skip the record. */
static int career_match_id(char *hex, size_t capacity)
{
    unsigned char random_bytes[16];
    static char const digits[] = "0123456789abcdef";
    FILE *random;
    int i;
    if (capacity < 33)
        return 0;
    random = fopen("/dev/urandom", "rb");
    if (!random)
        return 0;
    i = fread(random_bytes, 1, sizeof(random_bytes), random) ==
        sizeof(random_bytes);
    fclose(random);
    if (!i)
        return 0;
    for (i = 0; i < (int)sizeof(random_bytes); ++i)
    {
        hex[i * 2] = digits[random_bytes[i] >> 4];
        hex[i * 2 + 1] = digits[random_bytes[i] & 15];
    }
    hex[32] = 0;
    return 1;
}

static int career_recover_pending(void)
{
    char pending[1024], journal[1024], row[480];
    const char *root = getenv("HALO_DATA_ROOT");
    FILE *in, *out;
    size_t n;
    long length;
    int ok, read_failed, last = '\n';
    if (!root || !*root || snprintf(pending, sizeof(pending), "%s/career-pending-v1.log", root) >= (int)sizeof(pending) ||
        snprintf(journal, sizeof(journal), "%s/career-matches-v1.log", root) >= (int)sizeof(journal)) return 0;
    in = fopen(pending, "rb");
    if (!in) return 1;
    n = fread(row, 1, sizeof(row), in);
    read_failed = ferror(in);
    ok = feof(in) && n > 0 && n < sizeof(row) && row[n - 1] == '\n';
    fclose(in);
    if (read_failed) return 0;
    if (!ok) {
        char id[33], backup[1024];
        if (!career_match_id(id, sizeof(id)) ||
            snprintf(backup, sizeof(backup), "%s/career-damaged-%s.log", root, id) >= (int)sizeof(backup) ||
            rename(pending, backup) != 0) return 0;
        platform_log("career: saved damaged checkpoint for inspection");
        return 1;
    }
    out = fopen(journal, "ab+");
    if (!out) return 0;
    ok = fseek(out, 0, SEEK_END) == 0 && (length = ftell(out)) >= 0 &&
        length <= CAREER_MAX_BYTES - (long)n - 1;
    if (ok && length > 0) {
        ok = fseek(out, -1, SEEK_END) == 0;
        if (ok) last = fgetc(out);
        ok = ok && fseek(out, 0, SEEK_END) == 0;
    }
    if (ok && last != '\n') ok = fwrite("\n", 1, 1, out) == 1;
    if (ok) ok = fwrite(row, 1, n, out) == n && fflush(out) == 0 && fsync(fileno(out)) == 0;
    if (fclose(out) != 0) ok = 0;
    return ok && unlink(pending) == 0;
}

static int career_checkpoint(const char *root, const char *row, int size)
{
    char path[1024], temporary[1024];
    FILE *out;
    int ok;
    if (snprintf(path, sizeof(path), "%s/career-pending-v1.log", root) >= (int)sizeof(path) ||
        snprintf(temporary, sizeof(temporary), "%s/career-pending-v1.tmp", root) >= (int)sizeof(temporary)) return 0;
    out = fopen(temporary, "wb");
    if (!out) return 0;
    ok = fwrite(row, 1, size, out) == (size_t)size && fflush(out) == 0 && fsync(fileno(out)) == 0;
    if (fclose(out) != 0) ok = 0;
    if (ok) ok = rename(temporary, path) == 0;
    if (ok) {
        /* Persist the directory entry as well as the checkpoint bytes. */
        FILE *directory = fopen(root, "r");
        if (directory) { (void)fsync(fileno(directory)); fclose(directory); }
    }
    if (!ok) unlink(temporary);
    return ok;
}

static void career_map_token(const char *input, char *output, size_t capacity)
{
    const char *cursor, *start = input ? input : "";
    size_t n = 0;
    for (cursor = start; *cursor; ++cursor)
        if (*cursor == '/' || *cursor == '\\')
            start = cursor + 1;
    for (cursor = start; *cursor && n + 1 < capacity; ++cursor)
    {
        unsigned char c = (unsigned char)*cursor;
        if (c >= 'A' && c <= 'Z') c = (unsigned char)(c + ('a' - 'A'));
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
            c == '_' || c == '-')
            output[n++] = (char)c;
    }
    if (!n && capacity > 1) {
        memcpy(output, "unknown", 7);
        n = 7;
    }
    output[n] = 0;
}

static long career_nonnegative(long n)
{
    return n < 0 ? 0 : n;
}

static void career_record_write(long game_type, const char *map_name)
{
    struct data_iterator iterator;
    struct player_datum *player;
    const struct career_result *result;
    const char *root;
    char path[1024], map[48], line[480];
    const char *id = career_id;
    FILE *ledger;
    long timestamp, total, humans, bots, caps = 0, returns = 0, hill = 0;
    long grabs = 0, oddball_ticks = 0, carrier_kills = 0, kills_as_carrier = 0;
    long laps = 0, best_lap_ticks = 0, last_lap_ticks = 0;
    long local_slot, won, seconds, length;
    int size;

    if (recorded || game_type < game_engine_none ||
        game_type > game_engine_race)
        return;
    data_iterator_new(&iterator, player_data);
    while ((player = (struct player_datum *)data_iterator_next(&iterator)) != NULL)
    {
        local_slot = player->local_player_index;
        if (local_slot != 0 || player->quit_out_of_game)
            continue; /* Android has one local controller; never save bots/guests. */
        result = career_results_get(iterator.datum_index, game_type);
        if (!result) return; /* missing final receipt: never invent a result */
        if (result->incomplete && result->end_tick <= career_attempted_tick) return;
        if (!career_id[0] && !career_match_id(career_id, sizeof(career_id))) {
            if (career_attempted_tick < 0) platform_log("career: could not read match ID entropy");
            career_attempted_tick = result->end_tick;
            return;
        }
        root = getenv("HALO_DATA_ROOT");
        if (!root || !root[0] ||
            snprintf(path, sizeof(path), "%s/%s", root,
                "career-matches-v1.log") >= (int)sizeof(path))
            return;
        total = career_nonnegative(result->statistics.kills[0]);
        humans = result->humans;
        bots = result->bots;
        /* Preserve the host's total if its classification is unknown. */
        if (humans + bots != total)
            humans = bots = -1;
        if (game_type == game_engine_ctf) {
            caps = career_nonnegative(result->statistics.multiplayer_statistics.ctf_statistics.flag_scores);
            returns = career_nonnegative(result->statistics.multiplayer_statistics.ctf_statistics.flag_returns);
            grabs = career_nonnegative(result->statistics.multiplayer_statistics.ctf_statistics.flag_grabs);
        }
        if (game_type == game_engine_king)
            hill = career_nonnegative(result->statistics.multiplayer_statistics.king_statistics.time_on_hill);
        if (game_type == game_engine_oddball) {
            oddball_ticks = career_nonnegative(result->statistics.multiplayer_statistics.oddball_statistics.time_with_the_ball);
            carrier_kills = career_nonnegative(result->statistics.multiplayer_statistics.oddball_statistics.ball_carrier_kills);
            kills_as_carrier = career_nonnegative(result->statistics.multiplayer_statistics.oddball_statistics.kills_as_ball_carrier);
        }
        if (game_type == game_engine_race) {
            laps = career_nonnegative(result->statistics.multiplayer_statistics.race_statistics.laps);
            best_lap_ticks = career_nonnegative(result->statistics.multiplayer_statistics.race_statistics.best_lap_time);
            last_lap_ticks = career_nonnegative(result->statistics.multiplayer_statistics.race_statistics.last_lap_time);
        }
        career_map_token(map_name, map, sizeof(map));
        won = result->won;
        seconds = career_nonnegative(result->end_tick / 30);
        timestamp = (long)time(NULL);
        if (timestamp < 0) timestamp = 0;

        /* HC3 keeps HC2 mode counters and adds incomplete/DNF status.
         * Readers preserve older HC1/HC2 career history.
         * Do not encode shots_fired/hit: the engine does not update them. */
        size = snprintf(line, sizeof(line),
            "HC3|%s|%ld|%ld|%s|"
            "%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|"
            "%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld\n",
            id, timestamp, game_type, map,
            total, humans, bots,
            career_nonnegative(result->statistics.deaths),
            career_nonnegative(result->statistics.assists[0]), won, seconds,
            career_nonnegative(result->best_streak),
            caps, returns, hill,
            career_nonnegative(result->statistics.suicides),
            career_nonnegative(result->statistics.friendly_fire_kills),
            grabs, oddball_ticks, carrier_kills, kills_as_carrier,
            laps, best_lap_ticks, last_lap_ticks, (long)result->incomplete);
        if (size <= 0 || size >= (int)sizeof(line))
            return;
        if (result->incomplete) {
            career_attempted_tick = result->end_tick;
            if (!career_checkpoint(root, line, size)) platform_log("career: checkpoint storage failed");
            return;
        }
        /* A power loss could leave a partial trailing line. Separate it
         * rather than merging it with the next match; the reader rejects
         * the partial line and keeps the complete one that follows. */
        {
            int needs_newline = 0;
            FILE *previous = fopen(path, "rb");
            if (previous) {
                long old_size;
                if (fseek(previous, 0, SEEK_END) != 0 ||
                    (old_size = ftell(previous)) < 0 ||
                    old_size > CAREER_MAX_BYTES - size - 1) {
                    fclose(previous);
                    recorded = 1; /* preserve full/corrupt history unchanged */
                    platform_log("career: history at capacity or unreadable");
                    return;
                }
                if (old_size > 0 && fseek(previous, -1, SEEK_END) == 0) {
                    int last = fgetc(previous);
                    needs_newline = last != '\n' && last != EOF;
                }
                fclose(previous);
            }
            ledger = fopen(path, "ab");
            if (!ledger) {
                platform_log("career: could not open local postgame ledger");
                return;
            }
            if (needs_newline && fwrite("\n", 1, 1, ledger) != 1) {
                fclose(ledger);
                recorded = 1;
                platform_log("career: could not isolate partial record");
                return;
            }
        }
        length = (long)fwrite(line, 1, (size_t)size, ledger);
        {
            int synced = length == size && fflush(ledger) == 0 &&
                fsync(fileno(ledger)) == 0;
            int closed = fclose(ledger) == 0;
            /* Even failed/partial appends must not be replayed by this
             * process. The Java reader rejects incomplete lines. */
            recorded = 1;
            if (synced && closed) {
                char pending[1024];
                if (snprintf(pending, sizeof(pending), "%s/career-pending-v1.log", root) < (int)sizeof(pending))
                    unlink(pending);
                halo_highlights_import(result->moments, result->moment_count);
                /* The host already included victory in its selection. */
                halo_highlights_finish(root, id, map, result->end_tick, 0);
            }
            platform_log(synced && closed ?
                "career: saved local postgame record" :
                "career: postgame storage failed (no completed record)");
        }
        return;
    }
}
void career_record_update(long game_type, const char *map_name)
{
    career_record_write(game_type, map_name);
}

void career_record_postgame(long game_type, const char *map_name)
{
    if (!network_game_distributed_client()) career_results_finish(game_type);
    career_record_write(game_type, map_name);
}
#endif /* HALO_ANDROID */
