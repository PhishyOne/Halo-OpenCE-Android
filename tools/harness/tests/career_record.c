/* Fake Halo game world: compile the production career-recorder functions. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <time.h>
#include "highlight_moments.h"

#define TRUE 1
#define MAXIMUM_LOCAL_PLAYERS 4
#define game_engine_none 0
#define game_engine_ctf 1
#define game_engine_slayer 2
#define game_engine_oddball 3
#define game_engine_king 4
#define game_engine_race 5
struct ctf_statistics { int flag_scores, flag_returns, flag_grabs; };
struct king_statistics { int time_on_hill; };
struct oddball_statistics { int time_with_the_ball, ball_carrier_kills, kills_as_ball_carrier; };
struct race_statistics { int laps, best_lap_time, last_lap_time; };
struct game_statistics {
    int kills[4], assists[4], deaths, suicides, friendly_fire_kills;
    union {
        struct ctf_statistics ctf_statistics;
        struct king_statistics king_statistics;
        struct oddball_statistics oddball_statistics;
        struct race_statistics race_statistics;
    } multiplayer_statistics;
};
struct player_datum {
    int local_player_index, quit_out_of_game;
    struct game_statistics statistics;
};
static struct player_datum players[2];
static void *player_data;
struct data_iterator { int position; long datum_index; };
static void data_iterator_new(struct data_iterator *it, void *unused) {
    (void)unused; it->position=0; it->datum_index=0;
}
static void *data_iterator_next(struct data_iterator *it) {
    if (it->position >= 2) return NULL;
    it->datum_index=it->position;
    return &players[it->position++];
}
struct career_result {
    struct game_statistics statistics;
    long humans, bots, best_streak, won, end_tick;
    int moment_count, incomplete;
    struct halo_highlight_event moments[3];
};
static struct career_result snapshot;
static int frozen;
static int network_game_distributed_client(void) { return 0; }
static void career_results_finish(long mode) {
    (void)mode;
    if (frozen) return;
    snapshot.statistics = players[0].statistics;
    snapshot.won = 1; snapshot.end_tick = 600;
    frozen = 1;
}
static struct career_result *career_results_get(long index, long mode) {
    (void)mode; return index == 0 && frozen ? &snapshot : NULL;
}
static void observed_kill(short slot, int bot, long streak) {
    (void)slot;
    if (bot) snapshot.bots++; else snapshot.humans++;
    if (streak > snapshot.best_streak) snapshot.best_streak = streak;
}
static void reset_snapshot(void) { memset(&snapshot, 0, sizeof(snapshot)); frozen = 0; }
static char const *test_root;
static void platform_log(char const *msg) { (void)msg; }

#include "production.inc"

static int count_records(void) {
    char path[512], line[350];
    FILE *in;
    int n=0;
    snprintf(path,sizeof(path),"%s/career-matches-v1.log",test_root);
    in=fopen(path,"rb");
    if(!in) return 0;
    while(fgets(line,sizeof(line),in)) n++;
    fclose(in);
    return n;
}
int main(int argc, char **argv) {
    assert(argc == 2);
    test_root = argv[1];
    assert(setenv("HALO_DATA_ROOT", test_root, 1) == 0);
    memset(players,0,sizeof(players));
    players[0].local_player_index=0;
    players[1].local_player_index=-1; /* host's bot / remote guest */
    players[0].statistics.kills[0]=3;
    players[0].statistics.assists[0]=1;
    players[0].statistics.deaths=2;
    reset_snapshot(); career_record_start();
    observed_kill(0,1,1);
    observed_kill(0,0,2);
    observed_kill(0,0,3);
    career_record_postgame(game_engine_slayer,"levels\\test\\bloodgulch");
    assert(count_records()==1);
    career_record_postgame(game_engine_slayer,"levels\\test\\bloodgulch");
    assert(count_records()==1); /* exactly one per real postgame */
    reset_snapshot(); career_record_start(); /* next match */
    players[0].statistics.kills[0]=2;
    players[0].statistics.multiplayer_statistics.ctf_statistics.flag_grabs=3;
    players[0].statistics.multiplayer_statistics.ctf_statistics.flag_scores=2;
    players[0].statistics.multiplayer_statistics.ctf_statistics.flag_returns=1;
    observed_kill(0,1,1);
    career_record_postgame(game_engine_ctf,"levels/test/sidewinder");
    assert(count_records()==2);

    reset_snapshot(); career_record_start();
    players[0].statistics.kills[0]=4;
    players[0].statistics.multiplayer_statistics.oddball_statistics.time_with_the_ball=75;
    players[0].statistics.multiplayer_statistics.oddball_statistics.ball_carrier_kills=2;
    players[0].statistics.multiplayer_statistics.oddball_statistics.kills_as_ball_carrier=3;
    career_record_postgame(game_engine_oddball,"levels/test/icefields");
    assert(count_records()==3);

    reset_snapshot(); career_record_start();
    players[0].statistics.kills[0]=1;
    players[0].statistics.multiplayer_statistics.race_statistics.laps=2;
    players[0].statistics.multiplayer_statistics.race_statistics.best_lap_time=300;
    players[0].statistics.multiplayer_statistics.race_statistics.last_lap_time=420;
    career_record_postgame(game_engine_race,"levels/test/raceway");
    assert(count_records()==4);

    reset_snapshot(); career_record_start();
    players[0].quit_out_of_game=1;
    career_record_postgame(game_engine_slayer,"excluded");
    assert(count_records()==4);
    puts("PASS career recorder: four HC2 matches, no duplicate/quit");
    return 0;
}
