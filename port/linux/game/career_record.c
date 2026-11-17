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
/* The guest game build does not expose the platform layer's private
 * platform.h include directory. These are its two narrow entry points. */
extern const char *platform_data_root(void);
extern void platform_log(const char *format, ...);

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { CAREER_MAX_BYTES = 2 * 1024 * 1024 };
static int recorded;
static long human_kills[MAXIMUM_LOCAL_PLAYERS];
static long bot_kills[MAXIMUM_LOCAL_PLAYERS];
static long longest_streak[MAXIMUM_LOCAL_PLAYERS];

void career_record_start(void)
{
    memset(human_kills, 0, sizeof(human_kills));
    memset(bot_kills, 0, sizeof(bot_kills));
    memset(longest_streak, 0, sizeof(longest_streak));
    recorded = 0;
}

void career_record_kill(short local_slot, int bot_victim, long current_streak)
{
    if (local_slot < 0 || local_slot >= MAXIMUM_LOCAL_PLAYERS || recorded)
        return;
    if (bot_victim)
        bot_kills[local_slot]++;
    else
        human_kills[local_slot]++;
    if (current_streak > longest_streak[local_slot])
        longest_streak[local_slot] = current_streak;
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

void career_record_postgame(long game_type, const char *map_name)
{
    struct data_iterator iterator;
    struct player_datum *player;
    const char *root;
    char path[1024], id[33], map[48], line[320];
    FILE *ledger;
    long timestamp, total, humans, bots, caps = 0, returns = 0, hill = 0;
    long local_slot, won, seconds, length;
    int size;

    if (recorded || game_type < game_engine_ctf ||
        game_type > game_engine_race)
        return;
    data_iterator_new(&iterator, player_data);
    while ((player = (struct player_datum *)data_iterator_next(&iterator)) != NULL)
    {
        local_slot = player->local_player_index;
        if (local_slot != 0 || player->quit_out_of_game)
            continue; /* Android has one local controller; never save bots/guests. */
        if (game_time_get() < 30)
            return; /* a loading/lobby error is not a completed match */
        if (!career_match_id(id, sizeof(id)))
            return;
        root = platform_data_root();
        if (!root || !root[0] ||
            snprintf(path, sizeof(path), "%s/%s", root,
                "career-matches-v1.log") >= (int)sizeof(path))
            return;
        total = career_nonnegative(player->statistics.kills[0]);
        humans = human_kills[local_slot];
        bots = bot_kills[local_slot];
        /* If the guest did not receive kill-credit events consistently,
         * preserve the scoreboard total but do NOT invent opponent types. */
        if (humans + bots != total)
            humans = bots = -1;
        if (game_type == game_engine_ctf) {
            caps = career_nonnegative(player->statistics.multiplayer_statistics.ctf_statistics.flag_scores);
            returns = career_nonnegative(player->statistics.multiplayer_statistics.ctf_statistics.flag_returns);
        }
        if (game_type == game_engine_king)
            hill = career_nonnegative(player->statistics.multiplayer_statistics.king_statistics.time_on_hill);
        career_map_token(map_name, map, sizeof(map));
        won = game_engine_did_player_win(iterator.datum_index) == TRUE ? 1 : 0;
        seconds = career_nonnegative(game_time_get() / 30);
        timestamp = (long)time(NULL);
        if (timestamp < 0) timestamp = 0;

        /* 18 strictly ordered ASCII fields; bounded atomic append by one
         * native game thread. No player names, network addresses or secrets. */
        size = snprintf(line, sizeof(line),
            "HC1|%s|%ld|%ld|%s|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld|%ld\n",
            id, timestamp, game_type, map, total, humans, bots,
            career_nonnegative(player->statistics.deaths),
            career_nonnegative(player->statistics.assists[0]), won, seconds,
            career_nonnegative(longest_streak[local_slot]),
            caps, returns, hill,
            career_nonnegative(player->statistics.suicides),
            career_nonnegative(player->statistics.friendly_fire_kills));
        if (size <= 0 || size >= (int)sizeof(line))
            return;
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
            platform_log(synced && closed ?
                "career: saved local postgame record" :
                "career: postgame storage failed (no completed record)");
        }
        return;
    }
}
#endif /* HALO_ANDROID */
