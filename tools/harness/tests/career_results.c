/* Fake transport/world; both result collection/receiver and journal are the
 * production functions. Reordering mutates the real scoreboard separately. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "career_results.h"
#define TRUE 1
#define NONE (-1)
#define MAXIMUM_LOCAL_PLAYERS 4
#define HALO_PORT_MAXIMUM_NETWORK_PLAYERS 128
#define DATUM_INDEX_NEW(slot, id) ((long)(((uint32_t)(id) << 16) | (uint16_t)(slot)))
#define DATUM_INDEX_TO_ABSOLUTE_INDEX(n) ((short)((n) & 65535))
#define _game_connection_local 0
#define _game_connection_network_client 1
#define _game_connection_network_server 2
#define _distributed_message_career_start 101
#define _distributed_message_career_result 102
#define GET_MESSAGE_TYPE(n) ((n) & 3)
#define game_engine_ctf 1
#define game_engine_slayer 2
#define game_engine_oddball 3
#define game_engine_king 4
#define game_engine_race 5
struct player_datum {
    int identifier, local_player_index, quit_out_of_game, team_index;
    struct { int machine_index; } network_player_data;
    struct game_statistics statistics;
};
struct distributed_message_header { uint16_t header; uint8_t type, count; int32_t game_time; };
struct data_iterator { int position; long datum_index; };
static struct player_datum players[3];
static void *player_data;
static int connection, playing = 1, mode = 1;
static long tick = 600;
static uint32_t seed = 12345;
static char const *test_root;
static int packet_count;
static unsigned short packet_sizes[8];
static unsigned char packets[8][1024];
static long game_connection(void) { return connection; }
static int network_game_distributed_client(void) { return connection == 1; }
static int game_in_progress(void) { return playing; }
static long game_time_get(void) { return tick; }
static long game_engine_get_type(void) { return mode; }
static long network_game_get_random_seed(void) { return seed; }
static int bots_machine_is_bot(long m) { return m == 2; }
static struct player_datum *player_try_and_get(long i) {
    if (i < 0x10000 || i >= 0x10003) return NULL;
    return &players[i - 0x10000];
}
static void data_iterator_new(struct data_iterator *it, void *unused) {
    (void)unused; it->position = 0; it->datum_index = 0;
}
static void *data_iterator_next(struct data_iterator *it) {
    if (it->position >= 3) return NULL;
    it->datum_index = 0x10000 + it->position;
    return &players[it->position++];
}
static long game_engine_did_player_win(long i) { return i == 0x10001; }
static char const *platform_data_root(void) { return test_root; }
static void platform_log(char const *message) { (void)message; }
static void distributed_send_to_machine_reliably(long machine, void *data,
    unsigned char type, short count, unsigned short size) {
    struct distributed_message_header *h = data;
    assert(machine == 1 && packet_count < 8 && size <= 1024);
    h->header = 2; h->type = type; h->count = count; h->game_time = tick;
    packet_sizes[packet_count] = size;
    memcpy(packets[packet_count++], data, size);
}
#include "results.inc"
#include "writer.inc"

#define FALSE 0
#define REAL_MIN (-1e30f)
#define MAXIMUM_ATTACKERS_PER_UNIT 4
#define NUMBER_OF_CTF_TEAMS 2
#define VALID_INDEX(i,n) ((i) >= 0 && (i) < (n))
#define match_assert(file,line,condition) assert(condition)
typedef int boolean;
typedef float real;
static int game_statistics_active = 1;
struct unit_attacker { long player_index; unsigned long game_time_stamp; float damage_inflicted; };
struct unit_datum { struct { struct unit_attacker attackers[4]; } unit; };
static struct unit_datum victim;
static struct { long scores[2]; } ctf_globals;
static struct { int captures[2]; unsigned char capturers[2]; } ctf_events;
static struct player_datum *player_get(long id) { return player_try_and_get(id); }
static long player_index_from_unit_index(long id) { return id; }
static struct unit_datum *unit_get(long id) { (void)id; return &victim; }
static int game_team_is_enemy(long a, short b) { return a != b; }
static void killstreak_rewards_reset_player(long id) { (void)id; }
static void killstreak_rewards_record_kill(long a, long b) { (void)a; (void)b; }
static void game_engine_player_killed(long a,long b,long c,int d) {
    (void)a; (void)b; (void)c; (void)d;
}
static int game_engine_can_score(void) { return 1; }
static int ctf_capture_points(void) { return 1; }
static unsigned char distributed_player_to_byte(long id) { return (unsigned char)id; }
static void ctf_show_capture(long a, long b) { (void)a; (void)b; }
#include "kill.inc"
#include "capture.inc"


static int records(void) {
    char path[1024], line[600]; FILE *in; int count = 0;
    snprintf(path, sizeof(path), "%s/career-matches-v1.log", test_root);
    in = fopen(path, "rb"); if (!in) return 0;
    while (fgets(line, sizeof(line), in)) count++;
    fclose(in); return count;
}
static void host(void) {
    int i;
    memset(players, 0, sizeof(players));
    for (i = 0; i < 3; i++) {
        players[i].local_player_index = i ? NONE : 0;
        players[i].network_player_data.machine_index = i;
        players[i].team_index = i; players[i].identifier = 1;
    }
    for (i = 0; i < 4; i++) victim.unit.attackers[i].player_index = NONE;
    victim.unit.attackers[0].player_index = 0x10001;
    victim.unit.attackers[0].game_time_stamp = 600;
    victim.unit.attackers[0].damage_inflicted = 100;
    connection = 2; tick = 600; packet_count = 0;
    career_results_start(); career_record_start();
}
static void guest(void) {
    connection = 1;
    players[0].local_player_index = NONE; players[1].local_player_index = 0;
    career_results_start(); career_record_start();
}
static void deliver(int index) {
    assert(career_results_handle(NONE, packets[index], packet_sizes[index], 1));
}
static void host_match(void) {
    host();
    game_statistics_record_kill(0x10002, 0x10001, NONE, 1);
    assert(players[1].statistics.kills[0] == 1);
    tick = 900;
    ctf_award_capture(0x10001, 1);
    tick = 1200;
    career_results_finish(mode);
    assert(packet_count == 2);
}
int main(int argc, char **argv) {
    const struct career_result *r;
    struct distributed_career_result_message corrupt;
    struct distributed_career_start_message start;
    assert(argc == 3); test_root = argv[2];
    host_match();
    if (!strcmp(argv[1], "frozen")) {
        players[1].statistics.kills[0] = 99;
        tick = 1800; career_results_finish(mode);
        r = career_results_get(0x10001, mode);
        assert(r && r->statistics.kills[0] == 1 && r->end_tick == 1200);
        assert(packet_count == 2);
    } else if (!strcmp(argv[1], "ordering") || !strcmp(argv[1], "missing")) {
        guest();
        players[1].statistics.kills[0] = 1; /* host snapshot then death replay */
        players[1].statistics.kills_in_a_row = 1;
        game_statistics_record_kill(0x10002, 0x10001, NONE, 1);
        assert(players[1].statistics.kills[0] == 2);
        career_record_postgame(mode, "bloodgulch"); assert(records() == 0);
        deliver(0);
        career_record_postgame(mode, "bloodgulch"); assert(records() == 0);
        if (!strcmp(argv[1], "missing")) { puts("PASS missing final receipt"); return 0; }
        deliver(1);
        players[1].statistics.kills[0] = 0; /* late/stale live snapshot */
        career_record_postgame(mode, "bloodgulch"); assert(records() == 1);
        deliver(1); career_record_postgame(mode, "bloodgulch"); assert(records() == 1);
        r = career_results_get(0x10001, mode);
        assert(r && r->best_streak == 1 && r->statistics.kills[0] == 1 && r->bots == 1);
    } else if (!strcmp(argv[1], "capture")) {
        guest(); tick = 1500; deliver(0); deliver(1);
        r = career_results_get(0x10001, mode);
        assert(r && r->moment_count == 3);
        assert(r->moments[1].code == highlight_capture && r->moments[1].tick == 900);
        career_record_postgame(mode, "bloodgulch"); assert(records() == 1);
    } else if (!strcmp(argv[1], "replay_first")) {
        guest(); players[1].statistics.kills[0] = 0;
        players[1].statistics.kills_in_a_row = 0;
        game_statistics_record_kill(0x10002, 0x10001, NONE, 1);
        deliver(0); deliver(1);
        career_record_postgame(mode, "bloodgulch"); assert(records() == 1);
    } else if (!strcmp(argv[1], "validation")) {
        guest();
        deliver(1); assert(!career_results_get(0x10001, mode)); /* no start */
        career_results_handle(NONE, packets[0], packet_sizes[0], 0);
        deliver(1); assert(!career_results_get(0x10001, mode)); /* datagram */
        deliver(0);
        memcpy(&corrupt, packets[1], sizeof(corrupt));
        corrupt.generation++;
        career_results_handle(NONE, &corrupt, sizeof(corrupt), 1);
        assert(!career_results_get(0x10001, mode));
        memcpy(&start, packets[0], sizeof(start)); start.generation++;
        career_results_handle(NONE, &start, sizeof(start), 1); /* cannot rebind */
        career_results_handle(NONE, &corrupt, sizeof(corrupt), 1);
        assert(!career_results_get(0x10001, mode));
        memcpy(&corrupt, packets[1], sizeof(corrupt)); corrupt.result.best_streak = 2;
        career_results_handle(NONE, &corrupt, sizeof(corrupt), 1);
        assert(!career_results_get(0x10001, mode));
        memcpy(&corrupt, packets[1], sizeof(corrupt)); corrupt.result.player_index = 0x10000;
        career_results_handle(NONE, &corrupt, sizeof(corrupt), 1);
        assert(!career_results_get(0x10000, mode)); /* remote player */
        memcpy(&corrupt, packets[1], sizeof(corrupt)); corrupt.result.moment_count = 4;
        career_results_handle(NONE, &corrupt, sizeof(corrupt), 1);
        assert(!career_results_get(0x10001, mode));
        career_results_handle(NONE, packets[1], packet_sizes[1] - 1, 1);
        career_results_handle(1, packets[1], packet_sizes[1], 1);
        career_results_handle(NONE, packets[1], packet_sizes[1], 0);
        assert(!career_results_get(0x10001, mode));
        deliver(1); assert(career_results_get(0x10001, mode));
        memcpy(&corrupt, packets[1], sizeof(corrupt)); corrupt.result.statistics.kills[0] = 2;
        corrupt.result.bots = 2;
        career_results_handle(NONE, &corrupt, sizeof(corrupt), 1);
        assert(career_results_get(0x10001, mode)->statistics.kills[0] == 1);
        seed++; guest(); deliver(0); deliver(1);
        assert(!career_results_get(0x10001, mode)); /* previous match */
    } else if (!strcmp(argv[1], "death")) {
        host(); mode = 2;
        tick = 300; career_results_kill(0x10001, 0, 1);
        tick = 450; career_results_kill(0x10001, 1, 2);
        /* New life: the authoritative engine resets the streak. */
        tick = 900; career_results_kill(0x10001, 1, 1);
        players[1].statistics.kills[0] = 3; players[1].statistics.deaths = 1;
        tick = 1200; career_results_finish(mode);
        r = career_results_get(0x10001, mode);
        assert(r && r->best_streak == 2 && r->humans == 1 && r->bots == 2);
        assert(!career_results_get(0x10002, mode)); /* no bot profile */
        guest(); deliver(0); deliver(1);
        career_record_postgame(mode, "bloodgulch"); assert(records() == 1);
    } else assert(0 && "unknown test");
    puts("PASS career final results"); return 0;
}
