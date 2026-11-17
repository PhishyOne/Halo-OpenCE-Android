/* v43: only the host observes career events. Its immutable final receipt
 * goes down the existing reliable connection before the postgame transition.
 * Clients never persist transient kill-replay / scoreboard combinations.
 * This is local progression, not cryptographically certified ranking. */
#include "cseries.h"
#include "career_results.h"
#include "game/game_engine.h"
#include "game/players.h"
#include "memory/data.h"
#include "networking/network_game_globals.h"
#include "network_distributed.h"
#include "bots.h"
#include <string.h>

struct distributed_career_result_message {
    struct distributed_message_header header;
    uint32_t seed, generation;
    struct career_result result;
};
struct distributed_career_start_message {
    struct distributed_message_header header;
    uint32_t seed, generation;
};
#if UINTPTR_MAX == UINT32_MAX
typedef char career_result_wire_size[sizeof(struct career_result) == 128 ? 1 : -1];
typedef char career_result_message_wire_size[
    sizeof(struct distributed_career_result_message) == 144 ? 1 : -1];
typedef char career_start_message_wire_size[
    sizeof(struct distributed_career_start_message) == 16 ? 1 : -1];
#endif

struct career_player_events {
    long player_index;
    long humans, bots, best_streak;
    int valid, result_ready;
    struct halo_highlight_buffer highlights;
    struct career_result result;
};
static struct career_player_events career_players[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static uint32_t career_generation, career_seed, career_next_generation;
static int career_finished;

void career_results_start(void)
{
    memset(career_players, 0, sizeof(career_players));
    career_finished = 0;
    career_seed = (game_connection() == _game_connection_network_server ||
        network_game_distributed_client()) ? (uint32_t)network_game_get_random_seed() : 0;
    if (++career_next_generation == 0) ++career_next_generation;
    career_generation = network_game_distributed_client() ? 0 : career_next_generation;
}

static struct career_player_events *career_player(long player_index)
{
    short slot = DATUM_INDEX_TO_ABSOLUTE_INDEX(player_index);
    struct career_player_events *events;
    struct player_datum *player;
    if (player_index == NONE || slot < 0 || slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
        return NULL;
    player = player_try_and_get(player_index);
    if (!player || DATUM_INDEX_NEW(slot, player->identifier) != player_index ||
        player->quit_out_of_game ||
        bots_machine_is_bot(player->network_player_data.machine_index))
        return NULL;
    events = &career_players[slot];
    if (!events->valid || events->player_index != player_index) {
        memset(events, 0, sizeof(*events));
        events->player_index = player_index;
        events->valid = 1;
    }
    return events;
}

void career_results_kill(long player_index, int bot_victim, long streak)
{
    struct career_player_events *events;
    if (network_game_distributed_client() || career_finished) return;
    events = career_player(player_index);
    if (!events) return;
    if (bot_victim) events->bots++; else events->humans++;
    if (streak > events->best_streak) events->best_streak = streak;
    halo_highlight_kill(&events->highlights, bot_victim, streak, game_time_get());
}

void career_results_capture(long player_index)
{
    struct career_player_events *events;
    if (network_game_distributed_client() || career_finished) return;
    events = career_player(player_index);
    if (events) halo_highlight_capture(&events->highlights, game_time_get());
}

/* Validate the entire receipt before making any of it visible. Fields the
 * journal uses have the same bounds as its Java reader. No host pointer,
 * path or name is read from the wire. */
static int career_result_valid(const struct career_result *r)
{
    const struct game_statistics *s = &r->statistics;
    int i;
    if (r->game_type < game_engine_ctf || r->game_type > game_engine_race ||
        r->end_tick < 30 || r->end_tick > 86400L * 30 ||
        r->won < 0 || r->won > 1 ||
        s->kills[0] < 0 || s->kills[0] > 10000 ||
        s->deaths < 0 || s->deaths > 10000 ||
        s->assists[0] < 0 || s->assists[0] > 10000 ||
        s->suicides < 0 || s->suicides > 10000 ||
        s->friendly_fire_kills < 0 || s->friendly_fire_kills > 10000 ||
        r->best_streak < 0 || r->best_streak > s->kills[0] ||
        r->moment_count < 0 || r->moment_count > 3)
        return 0;
    if (!((r->humans == -1 && r->bots == -1) ||
        (r->humans >= 0 && r->humans <= 10000 && r->bots >= 0 &&
         r->bots <= 10000 && r->humans + r->bots == s->kills[0])))
        return 0;
    /* The union contains three signed shorts; all stored mode counters
     * are nonnegative. Race uses -1 for an as-yet unset lap time; the
     * journal represents these as zero, as it did before v43. */
    if (r->game_type == game_engine_ctf &&
        (s->multiplayer_statistics.ctf_statistics.flag_grabs < 0 ||
         s->multiplayer_statistics.ctf_statistics.flag_grabs > 10000 ||
         s->multiplayer_statistics.ctf_statistics.flag_returns < 0 ||
         s->multiplayer_statistics.ctf_statistics.flag_returns > 10000 ||
         s->multiplayer_statistics.ctf_statistics.flag_scores < 0 ||
         s->multiplayer_statistics.ctf_statistics.flag_scores > 10000)) return 0;
    if (r->game_type == game_engine_king &&
        s->multiplayer_statistics.king_statistics.time_on_hill < 0) return 0;
    if (r->game_type == game_engine_oddball &&
        (s->multiplayer_statistics.oddball_statistics.time_with_the_ball < 0 ||
         s->multiplayer_statistics.oddball_statistics.ball_carrier_kills < 0 ||
         s->multiplayer_statistics.oddball_statistics.ball_carrier_kills > 10000 ||
         s->multiplayer_statistics.oddball_statistics.kills_as_ball_carrier < 0 ||
         s->multiplayer_statistics.oddball_statistics.kills_as_ball_carrier > 10000)) return 0;
    if (r->game_type == game_engine_race &&
        (s->multiplayer_statistics.race_statistics.laps < 0 ||
         s->multiplayer_statistics.race_statistics.laps > 10000 ||
         s->multiplayer_statistics.race_statistics.best_lap_time < -1 ||
         s->multiplayer_statistics.race_statistics.last_lap_time < -1)) return 0;
    for (i = 0; i < r->moment_count; i++) {
        const struct halo_highlight_event *e = &r->moments[i];
        if (!halo_highlight_valid(e, r->end_tick) ||
            (i && e->tick - r->moments[i - 1].tick < 120)) return 0;
        if ((e->code == highlight_spree3 && r->best_streak < 3) ||
            (e->code == highlight_spree5 && r->best_streak < 5) ||
            (e->code == highlight_spree10 && r->best_streak < 10) ||
            (e->code == highlight_win && !r->won) ||
            (e->code == highlight_capture && (r->game_type != game_engine_ctf ||
             s->multiplayer_statistics.ctf_statistics.flag_scores == 0))) return 0;
    }
    return 1;
}

void career_results_send_start(long machine_index)
{
    struct distributed_career_start_message message;
    if (game_connection() != _game_connection_network_server || !career_generation)
        return;
    memset(&message, 0, sizeof(message));
    message.seed = career_seed;
    message.generation = career_generation;
    distributed_send_to_machine_reliably(machine_index, &message,
        _distributed_message_career_start, 1, sizeof(message));
}

void career_results_finish(long game_type)
{
    struct data_iterator iterator;
    struct player_datum *player;
    if (network_game_distributed_client() || career_finished ||
        game_type < game_engine_ctf || game_type > game_engine_race) return;
    career_finished = 1;
    data_iterator_new(&iterator, player_data);
    while ((player = data_iterator_next(&iterator)) != NULL) {
        struct career_player_events *events = career_player(iterator.datum_index);
        struct career_result *r;
        if (!events) continue;
        r = &events->result;
        memset(r, 0, sizeof(*r));
        r->player_index = iterator.datum_index;
        r->game_type = game_type;
        r->end_tick = game_time_get();
        r->won = game_engine_did_player_win(iterator.datum_index) == TRUE;
        r->statistics = player->statistics;
        r->humans = events->humans;
        r->bots = events->bots;
        r->best_streak = events->best_streak;
        if (r->humans + r->bots != r->statistics.kills[0]) r->humans = r->bots = -1;
        r->moment_count = halo_highlight_select(&events->highlights,
            r->end_tick, r->won, r->moments);
        if (!career_result_valid(r)) continue;
        events->result_ready = 1;
        if (game_connection() == _game_connection_network_server &&
            player->local_player_index == NONE) {
            struct distributed_career_result_message message;
            long machine = player->network_player_data.machine_index;
            memset(&message, 0, sizeof(message));
            message.seed = career_seed;
            message.generation = career_generation;
            message.result = *r;
            /* Both packets use the same ordered connection as the postgame
             * transition. Repeating the same start is harmless. */
            career_results_send_start(machine);
            distributed_send_to_machine_reliably(machine, &message,
                _distributed_message_career_result, 1, sizeof(message));
        }
    }
}

const struct career_result *career_results_get(long player_index, long game_type)
{
    struct career_player_events *events = career_player(player_index);
    if (!events || !events->result_ready || events->result.game_type != game_type)
        return NULL;
    return &events->result;
}

int career_results_handle(long machine_index, const void *data, unsigned short size,
    int reliable)
{
    struct distributed_message_header header;
    struct distributed_career_result_message message;
    struct distributed_career_start_message start;
    struct career_player_events *events;
    struct player_datum *player;
    struct game_variant *variant;
    if (size < sizeof(header)) return 0;
    memcpy(&header, data, sizeof(header));
    if (header.type != _distributed_message_career_start &&
        header.type != _distributed_message_career_result) return 0;
    if (machine_index != NONE || !network_game_distributed_client() ||
        !game_in_progress() || !reliable || GET_MESSAGE_TYPE(header.header) != 2 ||
        header.count != 1) return 1;
    if (header.type == _distributed_message_career_start) {
        if (size != sizeof(start)) return 1;
        memcpy(&start, data, sizeof(start));
        if (start.seed != career_seed || !start.generation) return 1;
        if (!career_generation) career_generation = start.generation;
        return 1;
    }
    if (size != sizeof(message) || !career_generation) return 1;
    memcpy(&message, data, sizeof(message));
    variant = game_engine_get_variant();
    if (message.seed != career_seed || message.generation != career_generation ||
        !variant || message.result.game_type != variant->game_engine_index ||
        !career_result_valid(&message.result)) return 1;
    player = player_try_and_get(message.result.player_index);
    if (!player || player->local_player_index < 0 ||
        player->local_player_index >= MAXIMUM_LOCAL_PLAYERS) return 1;
    events = career_player(message.result.player_index);
    if (!events || events->result_ready) return 1;
    events->result = message.result;
    events->result_ready = 1;
    return 1;
}
