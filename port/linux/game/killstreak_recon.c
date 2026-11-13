/* The host sends no targets, only an earned award to its owner's machine.
 * Rendering uses already replicated units. Commander policy never grants
 * or shares this state. All times are simulation ticks, not wall time. */
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/players.h"
#include "interface/hud_messaging.h"
#include "main/main.h"
#include "cutscene/cinematics.h"
#include "units/units.h"
#include "bots.h"
#include "commander_features.h"
#include "network_distributed.h"
#include "killstreak_recon.h"
#include <string.h>

#define KILLSTREAK_RECON_TICKS 900

struct killstreak_recon_message {
    struct distributed_message_header header;
    int player_index; /* absolute player slot, NOT host-local salted datum */
    int unit_index; /* host-owned salted unit index */
    unsigned int serial;
    int recon_end;
    word award; /* 0 revokes, 3 announces, 5 announces and enables recon */
    word reserved;
};

typedef char killstreak_recon_message_size[
    sizeof(struct killstreak_recon_message) == 28 ? 1 : -1];

struct killstreak_recon_local {
    long player_index;
    long unit_index;
    unsigned int serial;
    long recon_end;
    long notice_end;
    long next_notice;
    unsigned short awarded;
    unsigned short pending;
};

static struct killstreak_recon_local recon_local[MAXIMUM_LOCAL_PLAYERS];
static unsigned int recon_serial;
static boolean recon_running;

static int recon_playing(void)
{
    return recon_running && game_in_progress() && game_engine_in_play() &&
        !main_menu_is_active() &&
        (game_connection() == _game_connection_local ||
         game_connection() == _game_connection_network_server ||
         game_connection() == _game_connection_network_client) &&
        killstreak_feature_allowed(KILLSTREAK_ENABLED);
}

static int recon_living_player(long player_index, long unit_index)
{
    struct player_datum *player = player_try_and_get(player_index);
    struct unit_datum *unit;

    return player && !player->quit_out_of_game && unit_index != NONE &&
        player->unit_index == unit_index &&
        !bots_machine_is_bot(player->network_player_data.machine_index) &&
        (unit = unit_try_and_get(unit_index)) != NULL &&
        unit->unit.player_index == player_index &&
        !TEST_FLAG(unit->object.damage_flags, _object_dead_bit);
}

/* Keep the receive watermark on cancellation, so repeated packets cannot
 * resurrect an award. A different salted unit starts a different life. */
void killstreak_recon_reset_player(long player_index)
{
    short index;

    for (index = 0; index < MAXIMUM_LOCAL_PLAYERS; index++)
        if (recon_local[index].player_index == player_index)
        {
            recon_local[index].recon_end = 0;
            recon_local[index].pending = 0;
        }
}

void killstreak_recon_reset(void)
{
    memset(recon_local, 0, sizeof(recon_local));
    recon_running = TRUE;
}

void killstreak_recon_stop(void)
{
    recon_running = FALSE;
    killstreak_recon_update();
}

/* Called by the receiver or the local host, never by kill prediction.
 * A machine may contain several viewports: both mappings must agree. */
static void recon_receive(struct killstreak_recon_message const *message)
{
    struct player_datum *player;
    struct killstreak_recon_local *state;
    short local;
    long local_player_id;
    long now;

    if (!game_in_progress() || message->player_index < 0 ||
        message->player_index >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
        return;
    /* The host and guest can assign different salts to the same player
     * slot. Translate the authenticated host SLOT to the client-local
     * salted datum, without accepting a different local player's award.
     * Unit indices are replicated with the host's salt as normal. */
    local_player_id = distributed_player_from_byte((byte)message->player_index);
    if (local_player_id == NONE)
        return;
    now = game_time_get();
    player = player_try_and_get(local_player_id);
    if (!player || (local = player->local_player_index) < 0 ||
        local >= MAXIMUM_LOCAL_PLAYERS ||
        local_player_get_player_index(local) != local_player_id)
        return;
    state = &recon_local[local];
    if (!message->serial || message->serial <= state->serial)
        return;
    /* Reliable stream order includes revocations, even after policy OFF.
     * Never clear a replacement unit in response to an old unit's revoke. */
    state->serial = message->serial;
    if (message->award == 0)
    {
        if (state->player_index == local_player_id &&
            state->unit_index == message->unit_index)
        {
            state->recon_end = 0;
            state->pending = 0;
            state->awarded = 0;
        }
        return;
    }
    if (!recon_playing() ||
        !recon_living_player(local_player_id, message->unit_index) ||
        message->header.game_time < 0 || now < 0 ||
        /* A client may be a few ticks behind its host. Never extend the
         * end time to compensate for network transit or clock skew. */
        (message->header.game_time > now && message->header.game_time - now > 15) ||
        now - message->header.game_time >= KILLSTREAK_RECON_TICKS)
        return;
    if (state->player_index != local_player_id ||
        state->unit_index != message->unit_index)
    {
        state->player_index = local_player_id;
        state->unit_index = message->unit_index;
        state->awarded = state->pending = 0;
        state->recon_end = state->next_notice = 0;
    }
    if (message->award <= state->awarded)
        return;
    state->awarded = message->award;
    if (message->award == 5)
        state->recon_end = message->recon_end;
    if (killstreak_feature_allowed(KILLSTREAK_ANNOUNCEMENTS))
    {
        state->pending |= message->award == 3 ? 1 : 2;
        state->notice_end = message->header.game_time + KILLSTREAK_RECON_TICKS;
    }
}

static void recon_send(long player_index, long unit_index, word award)
{
    struct player_datum *player;
    struct killstreak_recon_message message;
    long machine;
    long now;

    if (!game_in_progress() || (game_connection() != _game_connection_local &&
        game_connection() != _game_connection_network_server))
        return;
    now = game_time_get();
    player = player_try_and_get(player_index);
    if (!player || now < 0 || now > 0x7fffffff - KILLSTREAK_RECON_TICKS ||
        recon_serial == 0xffffffffu)
        return;
    memset(&message, 0, sizeof(message));
    message.header.game_time = now;
    message.player_index = (int)DATUM_INDEX_TO_ABSOLUTE_INDEX(player_index);
    message.unit_index = (int)unit_index;
    message.serial = ++recon_serial;
    message.award = award;
    message.recon_end = award == 5 ? (int)now + KILLSTREAK_RECON_TICKS : 0;
    if (player->local_player_index != NONE)
        recon_receive(&message);
    else if (game_connection() == _game_connection_network_server)
    {
        machine = player->network_player_data.machine_index;
        if (machine >= 0 && machine < HALO_PORT_MAXIMUM_NETWORK_MACHINES &&
            !bots_machine_is_bot(machine))
            distributed_send_to_machine_reliably(machine, &message,
                _distributed_message_killstreak_recon, 1, (word)sizeof(message));
    }
}

void killstreak_recon_award(long player_index, long unit_index, unsigned short award)
{
    if ((award == 3 || award == 5) && recon_playing() &&
        recon_living_player(player_index, unit_index))
        recon_send(player_index, unit_index, award);
}

void killstreak_recon_revoke(long player_index, long unit_index)
{
    recon_send(player_index, unit_index, 0);
}

boolean killstreak_recon_handles_message(word const *data, word size)
{
    struct distributed_message_header header;

    if (size < sizeof(header))
        return FALSE;
    memcpy(&header, data, sizeof(header));
    return header.type == _distributed_message_killstreak_recon;
}

void killstreak_recon_handle_message(long machine_index, word const *data, word size)
{
    struct killstreak_recon_message message;

    if (machine_index != NONE ||
        game_connection() != _game_connection_network_client ||
        network_connection_last_read_was_unreliable() || size != sizeof(message))
        return;
    memcpy(&message, data, sizeof(message));
    if (GET_MESSAGE_TYPE(message.header.header) != 2 ||
        GET_MESSAGE_SIZE(message.header.header) != sizeof(message) ||
        GET_MESSAGE_FLAGS(message.header.header) != 0 ||
        message.header.type != _distributed_message_killstreak_recon ||
        message.header.count != 1 || message.reserved != 0 ||
        (message.award != 0 && message.award != 3 && message.award != 5) ||
        message.header.game_time < 0 ||
        message.header.game_time > 0x7fffffff - KILLSTREAK_RECON_TICKS ||
        message.recon_end != (message.award == 5 ?
            message.header.game_time + KILLSTREAK_RECON_TICKS : 0))
        return;
    recon_receive(&message);
}

void killstreak_recon_update(void)
{
    struct killstreak_recon_local *state;
    short local;
    int playing = recon_playing();
    long now = playing ? game_time_get() : 0;

    for (local = 0; local < MAXIMUM_LOCAL_PLAYERS; local++)
    {
        state = &recon_local[local];
        if (!playing ||
            local_player_get_player_index(local) != state->player_index ||
            !recon_living_player(state->player_index, state->unit_index))
        {
            state->recon_end = 0;
            state->pending = 0;
        }
        if (now >= state->recon_end)
            state->recon_end = 0;
        if (now >= state->notice_end ||
            !killstreak_feature_allowed(KILLSTREAK_ANNOUNCEMENTS))
            state->pending = 0;
    }
}

int killstreak_recon_active(short local_player_index)
{
    killstreak_recon_update();
    return recon_playing() && local_player_index >= 0 && local_player_index < MAXIMUM_LOCAL_PLAYERS &&
        recon_local[local_player_index].recon_end > game_time_get();
}

/* Only hud_draw_screen calls this, on the local render/main thread. No
 * audio or text is emitted from a server kill callback or packet handler. */
void killstreak_recon_present(short local_player_index)
{
    struct killstreak_recon_local *state;
    word award;

    killstreak_recon_update();
    if (local_player_index < 0 || local_player_index >= MAXIMUM_LOCAL_PLAYERS ||
        cinematic_in_progress() || !recon_playing())
        return;
    state = &recon_local[local_player_index];
    if (!state->pending || game_time_get() < state->next_notice)
        return;
    award = (state->pending & 1) ? 3 : 5;
    state->pending &= award == 3 ? ~1u : ~2u;
    state->next_notice = game_time_get() + 90;
    hud_print_message(local_player_index, award == 3 ?
        L"3 kills: Enhancements online" :
        L"5 kills: Recon enabled (30s)");
    game_engine_play_killstreak_sound(local_player_index, award);
}
