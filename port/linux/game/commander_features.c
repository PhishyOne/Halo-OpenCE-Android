/* Authoritative Commander Mode feature policy (pre-feature foundation).
 *
 * The host chooses a master enabled flag and individual support options.
 * The packet contains only the resulting bitmask; no client may request
 * capabilities here. A network client starts with no permissions and only
 * accepts a reliable, correctly sized host policy packet. Future commander,
 * UAV, spotting, orders and killstreak call sites MUST check this mask
 * before performing their action. None of those gameplay actions are
 * implemented by this settings-only module.
 */
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "main/main.h"
#include "networking/network_game_globals.h"
#include "network_coop.h"
#include "network_distributed.h"
#include "commander_features.h"

#include <string.h>

int config_boolean(char const *name);
boolean network_connection_last_read_was_unreliable(void);

struct distributed_commander_settings {
    struct distributed_message_header header;
    word mask;
    word reserved;
};

typedef char verify_distributed_commander_settings_size[
    sizeof(struct distributed_commander_settings) == 12 ? 1 : -1
];

static unsigned int commander_client_mask;
static unsigned int commander_sent_mask = ~0u;

/* A commander is a team-multiplayer role, not an advantage in campaign
 * co-op, FFA or the menus. */
static int commander_team_game(void)
{
    struct game_variant *variant;

    if (!game_in_progress() || !game_engine_running() ||
        network_coop_active())
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
    if (config_boolean("commander.killstreaks"))
        mask |= COMMANDER_KILLSTREAKS;
    if (config_boolean("commander.orbital"))
        mask |= COMMANDER_ORBITAL;
    if (config_boolean("commander.announcements"))
        mask |= COMMANDER_ANNOUNCEMENTS;
    return mask;
}

unsigned int commander_features_mask(void)
{
    if (!commander_team_game())
        return 0;
    if (game_connection() == _game_connection_network_client)
        return commander_policy_effective(commander_client_mask, 1);
    /* Only the local host can enable functionality. The client never
     * consults its own TOML settings for commander capabilities. */
    return commander_policy_effective(commander_host_selected_mask(), 1);
}

int commander_feature_allowed(unsigned int feature)
{
    return commander_policy_allows(commander_features_mask(), feature);
}

void commander_features_new_game(void)
{
    commander_client_mask = 0;
    commander_sent_mask = ~0u;
}

static void commander_settings_make(
    struct distributed_commander_settings *message, unsigned int mask)
{
    memset(message, 0, sizeof(*message));
    message->mask = (word)commander_policy_effective(mask, 1);
}

void commander_features_send_to_machine(long machine_index)
{
    struct distributed_commander_settings message;

    if (game_connection() != _game_connection_network_server)
        return;
    commander_settings_make(&message, commander_features_mask());
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
    if (mask == commander_sent_mask)
        return;
    commander_sent_mask = mask;
    commander_settings_make(&message, mask);
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
    struct distributed_commander_settings message;

    if (machine_index != NONE ||
        game_connection() != _game_connection_network_client ||
        network_connection_last_read_was_unreliable() ||
        size != sizeof(message))
        return;
    memcpy(&message, data, sizeof(message));
    if (GET_MESSAGE_TYPE(message.header.header) != 2 ||
        message.header.type != _distributed_message_commander_settings ||
        message.header.count != 1 || message.reserved != 0 ||
        (message.mask & ~COMMANDER_KNOWN_MASK) != 0)
        return;
    commander_client_mask = commander_policy_effective(message.mask, 1);
}
