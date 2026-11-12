/* Independent Commander Mode and personal Killstreak Rewards policy.
 * v22 adds one host-designated human Commander across both teams.
 * A reliable host-only settings message carries a role identity as well as
 * the feature mask. No client input can nominate or grant a role.
 */
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/players.h"
#include "interface/hud_messaging.h"
#include "main/main.h"
#include "memory/data.h"
#include "networking/network_game_globals.h"
#include "bots.h"
#include "network_coop.h"
#include "network_distributed.h"
#include "commander_features.h"
#include "commander_tactical.h"
#include "commander_tactical_policy.h"
#include "commander_role_policy.h"
#include <string.h>

int config_boolean(char const *name);
boolean network_connection_last_read_was_unreliable(void);

/* New length and semantics require network protocol 47 (v21 was 46).
 * The host and clients identify a player by its absolute slot, current
 * team and exact public roster name; their salted player datum may differ.
 * The packet comes only from an authenticated reliable host stream. */
struct distributed_commander_settings {
    struct distributed_message_header header;
    word mask;
    word reserved;
    word role_slot;
    word role_team;
    wchar_t role_name[COMMANDER_ROLE_NAMES];
    unsigned int serial;
};
typedef char verify_distributed_commander_settings_size[
    sizeof(struct distributed_commander_settings) == 44 ? 1 : -1
];

struct commander_role_id {
    word slot;
    word team;
    wchar_t name[COMMANDER_ROLE_NAMES];
};

static unsigned int commander_client_mask;
static unsigned int commander_sent_mask = ~0u;
static struct commander_role_id role_host;
static struct commander_role_id role_client;
static unsigned int role_generation;
static unsigned int role_sent_generation = ~0u;
static unsigned int role_send_serial; /* never reset on new maps in this process */
static unsigned int role_receive_serial;

static void role_clear(struct commander_role_id *role)
{
    memset(role, 0, sizeof(*role));
    role->slot = COMMANDER_ROLE_NONE;
    role->team = COMMANDER_ROLE_NONE;
}

static int role_is_empty(struct commander_role_id const *role)
{
    return role->slot == COMMANDER_ROLE_NONE;
}

static int role_player_valid(struct commander_role_id const *role)
{
    long id;
    struct player_datum *player;

    if (role_is_empty(role) || role->slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS ||
        role->team > 1)
        return 0;
    id = distributed_player_from_byte((byte)role->slot);
    player = id != NONE ? player_try_and_get(id) : NULL;
    return player && !player->quit_out_of_game &&
        !bots_machine_is_bot(player->network_player_data.machine_index) &&
        commander_role_identity_matches(role->slot, role->team, role->name,
            (unsigned)DATUM_INDEX_TO_ABSOLUTE_INDEX(id),
            (unsigned)player->team_index, player->name,
            HALO_PORT_MAXIMUM_NETWORK_PLAYERS);
}

static int commander_multiplayer_game(void)
{
    return game_in_progress() && game_engine_running() &&
        !network_coop_active();
}

static int commander_team_game(void)
{
    struct game_variant *variant;
    if (!commander_multiplayer_game())
        return 0;
    variant = game_engine_get_variant();
    return variant && variant->universal_variant.teams;
}

static unsigned int commander_host_selected_mask(void)
{
    unsigned int mask = 0;
    if (config_boolean("commander.enabled"))
        mask |= COMMANDER_ENABLED;
    if (config_boolean("commander.satellite"))
        mask |= COMMANDER_SATELLITE;
    if (config_boolean("commander.uav"))
        mask |= COMMANDER_UAV;
    if (config_boolean("commander.spotting"))
        mask |= COMMANDER_SPOTTING;
    if (config_boolean("commander.orders"))
        mask |= COMMANDER_ORDERS;
    if (config_boolean("killstreak.enabled"))
        mask |= KILLSTREAK_ENABLED;
    if (config_boolean("killstreak.orbital"))
        mask |= KILLSTREAK_ORBITAL;
    if (config_boolean("killstreak.announcements"))
        mask |= KILLSTREAK_ANNOUNCEMENTS;
    if (config_boolean("commander.announcements"))
        mask |= COMMANDER_ANNOUNCEMENTS;
    return mask;
}

unsigned int commander_features_mask(void)
{
    int multiplayer = commander_multiplayer_game();
    int team_multiplayer = commander_team_game();
    if (!multiplayer)
        return 0;
    if (game_connection() == _game_connection_network_client)
        return commander_policy_effective(commander_client_mask,
            team_multiplayer, multiplayer);
    return commander_policy_effective(commander_host_selected_mask(),
        team_multiplayer, multiplayer);
}

int commander_feature_allowed(unsigned int feature)
{
    return commander_policy_allows(commander_features_mask(), feature);
}

int killstreak_feature_allowed(unsigned int feature)
{
    return killstreak_policy_allows(commander_features_mask(), feature);
}

static int commander_host_authority(void)
{
    return game_connection() == _game_connection_local ||
        game_connection() == _game_connection_network_server;
}

/* Host's first local human controls nomination. Selection is deliberately
 * independent of the selected player's team, so either side may appoint a
 * commander for this one-role candidate. No bot can be a commander. */
int commander_features_host_can_assign(void)
{
    long id;
    struct player_datum *p;

    if (!commander_host_authority() ||
        !commander_feature_allowed(COMMANDER_ENABLED) ||
        !commander_team_game() || !game_engine_in_play() ||
        main_menu_is_active())
        return 0;
    id = local_player_get_player_index(0);
    p = id != NONE ? player_try_and_get(id) : NULL;
    return p && !p->quit_out_of_game &&
        commander_tactical_host_can_assign(commander_features_mask(),
            game_engine_has_teams(), commander_host_authority(), 0,
            !bots_machine_is_bot(p->network_player_data.machine_index));
}

int commander_features_local_is_role(short local)
{
    long id;
    struct player_datum *p;
    struct commander_role_id const *role;

    if (local < 0 || local >= MAXIMUM_LOCAL_PLAYERS ||
        !commander_feature_allowed(COMMANDER_ENABLED))
        return 0;
    id = local_player_get_player_index(local);
    p = id != NONE ? player_try_and_get(id) : NULL;
    role = game_connection() == _game_connection_network_client ?
        &role_client : &role_host;
    return p && !p->quit_out_of_game && !role_is_empty(role) &&
        !bots_machine_is_bot(p->network_player_data.machine_index) &&
        commander_role_identity_matches(role->slot, role->team, role->name,
            (unsigned)DATUM_INDEX_TO_ABSOLUTE_INDEX(id),
            (unsigned)p->team_index, p->name,
            HALO_PORT_MAXIMUM_NETWORK_PLAYERS);
}

/* Called from the host's Android touch MAP/ASSIGN toolbar in the game.
 * Cycles eligible human roster slots in deterministic order, then NONE.
 * Roles stay through respawn, but expire on disconnect or team/name change.
 * No change is ever accepted from a network client. */
void commander_features_cycle_role(void)
{
    struct data_iterator it;
    struct player_datum *p;
    struct player_datum *selected = NULL;
    short first_slot = NONE;
    short chosen_slot = NONE;
    long selected_id = NONE;
    wchar_t notice[40];
    wchar_t const label[] = L"COMMANDER: ";
    short i, length = 0;

    if (!commander_features_host_can_assign() || !player_data)
        return;
    data_iterator_new(&it, player_data);
    while ((p = data_iterator_next(&it)) != NULL)
    {
        short slot = (short)DATUM_INDEX_TO_ABSOLUTE_INDEX(it.datum_index);
        if (p->quit_out_of_game || (p->team_index != 0 && p->team_index != 1) ||
            bots_machine_is_bot(p->network_player_data.machine_index) ||
            (p->local_player_index != NONE && p->local_player_index != 0) ||
            slot < 0 || slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
            continue;
        if (first_slot == NONE)
            first_slot = slot;
        if (slot > (short)role_host.slot &&
            (chosen_slot == NONE || slot < chosen_slot))
        {
            chosen_slot = slot;
        }
    }
    /* Clear after the final nominee; the next press starts from first. */
    if (role_is_empty(&role_host))
        chosen_slot = first_slot;

    if (chosen_slot != NONE)
    {
        selected_id = distributed_player_from_byte((byte)chosen_slot);
        selected = selected_id != NONE ? player_try_and_get(selected_id) : NULL;
    }
    if (selected == NULL)
    {
        role_clear(&role_host);
        hud_print_message(0, L"Commander cleared");
    }
    else
    {
        role_host.slot = (word)chosen_slot;
        role_host.team = (word)selected->team_index;
        memcpy(role_host.name, selected->name, sizeof(role_host.name));
        for (i = 0; i < (short)NUMBEROF(label) - 1 && length < (short)NUMBEROF(notice) - 1; i++)
            notice[length++] = label[i];
        for (i = 0; i < (short)NUMBEROF(selected->name) &&
            selected->name[i] && length < (short)NUMBEROF(notice) - 1; i++)
            notice[length++] = selected->name[i];
        notice[length] = 0;
        hud_print_message(0, notice);
    }
    role_generation++;
}

void commander_features_new_game(void)
{
    commander_client_mask = 0;
    commander_sent_mask = ~0u;
    role_clear(&role_host);
    role_clear(&role_client);
    role_generation++;
    role_sent_generation = ~0u;
    role_receive_serial = 0;
    commander_tactical_reset();
}

/* Return false on exhaustion rather than reuse the in-process serial. */
static int commander_settings_make(struct distributed_commander_settings *m,
    unsigned int mask)
{
    if (role_send_serial == 0xFFFFFFFFu)
        return 0;
    memset(m, 0, sizeof(*m));
    m->mask = (word)commander_policy_effective(mask, 1, 1);
    m->role_slot = COMMANDER_ROLE_NONE;
    m->role_team = COMMANDER_ROLE_NONE;
    if ((m->mask & COMMANDER_ENABLED) && role_player_valid(&role_host))
    {
        m->role_slot = role_host.slot;
        m->role_team = role_host.team;
        memcpy(m->role_name, role_host.name, sizeof(role_host.name));
    }
    m->serial = ++role_send_serial;
    return 1;
}

void commander_features_send_to_machine(long machine_index)
{
    struct distributed_commander_settings message;
    if (game_connection() != _game_connection_network_server ||
        !commander_settings_make(&message, commander_features_mask()))
        return;
    distributed_send_to_machine_reliably(machine_index, &message,
        _distributed_message_commander_settings, 1, (word)sizeof(message));
}

void commander_features_update(void)
{
    struct distributed_commander_settings message;
    unsigned int mask;
    if (game_connection() != _game_connection_network_server ||
        main_menu_is_active() || !game_in_progress())
        return;
    mask = commander_features_mask();
    if (!role_is_empty(&role_host) &&
        (!(mask & COMMANDER_ENABLED) || !role_player_valid(&role_host)))
    {
        role_clear(&role_host);
        role_generation++;
    }
    if (mask == commander_sent_mask && role_generation == role_sent_generation)
        return;
    if (!commander_settings_make(&message, mask))
        return;
    commander_sent_mask = mask;
    role_sent_generation = role_generation;
    distributed_send(&message, _distributed_message_commander_settings, 1,
        (word)sizeof(message), _distributed_to_clients_reliably);
}

boolean commander_features_handles_message(word const *data, word size)
{
    struct distributed_message_header header;
    if (size < sizeof(header))
        return FALSE;
    memcpy(&header, data, sizeof(header));
    return header.type == _distributed_message_commander_settings;
}

void commander_features_handle_message(long machine_index, word const *data,
    word size)
{
    struct distributed_commander_settings m;
    int blank;

    if (machine_index != NONE ||
        game_connection() != _game_connection_network_client ||
        network_connection_last_read_was_unreliable() ||
        size != sizeof(m))
        return;
    memcpy(&m, data, sizeof(m));
    blank = m.role_slot == COMMANDER_ROLE_NONE;
    if (GET_MESSAGE_TYPE(m.header.header) != 2 ||
        GET_MESSAGE_SIZE(m.header.header) != sizeof(m) ||
        GET_MESSAGE_FLAGS(m.header.header) != 0 ||
        m.header.type != _distributed_message_commander_settings ||
        m.header.count != 1 || m.reserved != 0 ||
        (m.mask & ~COMMANDER_KNOWN_MASK) != 0 ||
        m.serial == 0 || m.serial <= role_receive_serial ||
        !commander_role_identity_valid(m.role_slot, m.role_team, m.role_name,
            HALO_PORT_MAXIMUM_NETWORK_PLAYERS))
        return;

    role_receive_serial = m.serial;
    commander_client_mask = commander_policy_effective(m.mask, 1, 1);
    role_clear(&role_client);
    if ((commander_client_mask & COMMANDER_ENABLED) && !blank)
    {
        role_client.slot = m.role_slot;
        role_client.team = m.role_team;
        memcpy(role_client.name, m.role_name, sizeof(role_client.name));
    }
}
