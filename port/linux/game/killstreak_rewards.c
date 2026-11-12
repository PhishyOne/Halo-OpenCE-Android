/* The engine owns effect decay and replication. Only humans earn rewards;
 * their kills against enemy humans OR bots qualify. */
#include "cseries.h"
#include "game/game.h"
#include "game/game_engine.h"
#include "game/game_allegiance.h"
#include "game/players.h"
#include "memory/data.h"
#include "objects/damage.h"
#include "units/units.h"
#include "bots.h"
#include "commander_features.h"
#include "killstreak_rewards.h"
#include "killstreak_recon.h"
#include "killstreak_orbital.h"

#include <string.h>

struct killstreak_reward_state {
    long player_index;
    long unit_index;
    unsigned char kills;
    boolean active;
    boolean owns_camouflage;
};

static struct killstreak_reward_state killstreak_players[HALO_PORT_MAXIMUM_NETWORK_PLAYERS];
static boolean killstreak_match_active;

static int killstreak_authority(void)
{
    return game_connection() == _game_connection_local ||
        game_connection() == _game_connection_network_server;
}

static struct killstreak_reward_state *killstreak_state(long player_index)
{
    unsigned int slot = (unsigned short)player_index;

    if (player_index == NONE || slot >= HALO_PORT_MAXIMUM_NETWORK_PLAYERS)
        return NULL;
    return &killstreak_players[slot];
}

int killstreak_rewards_has_ten(long player_index, long unit_index)
{
    struct killstreak_reward_state *state = killstreak_state(player_index);

    return killstreak_authority() && killstreak_match_active && state &&
        state->active && state->player_index == player_index &&
        state->unit_index == unit_index && state->kills == 10;
}

void killstreak_rewards_reset_player(long player_index)
{
    struct killstreak_reward_state *state = killstreak_state(player_index);
    struct player_datum *player;
    struct unit_datum *unit;

    killstreak_orbital_reset_player(player_index);
    killstreak_recon_reset_player(player_index);
    if (!state || !state->active || state->player_index != player_index)
        return;
    if (killstreak_authority() && state->kills >= 3)
        killstreak_recon_revoke(player_index, state->unit_index);
    /* Never touch a replacement player's or replacement unit's effects.
     * Normal powerup helpers relinquish ownership before adding a pickup. */
    if (killstreak_authority() && state->owns_camouflage)
    {
        player = player_try_and_get(player_index);
        if (player && player->unit_index == state->unit_index &&
            (unit = unit_try_and_get(state->unit_index)) != NULL &&
            unit->unit.player_index == player_index)
        {
            player->powerup_durations[_player_powerup_active_camouflage] = 0;
            SET_FLAG(unit->unit.flags, _unit_active_camouflaged_bit, FALSE);
        }
    }
    /* Overshields have no separate reward timer or provenance. Let the
     * stock engine consume them; never subtract shields during cleanup. */
    memset(state, 0, sizeof(*state));
}

void killstreak_rewards_stop(void)
{
    unsigned int slot;

    for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; slot++)
        if (killstreak_players[slot].active)
            killstreak_rewards_reset_player(killstreak_players[slot].player_index);
    killstreak_match_active = FALSE;
    killstreak_recon_stop();
    killstreak_orbital_stop();
}

void killstreak_rewards_start(void)
{
    /* New map player datums may reuse both identifiers and slots. Old map
     * effects are gone; do not look up old indices in the new world. */
    memset(killstreak_players, 0, sizeof(killstreak_players));
    killstreak_recon_reset();
    killstreak_orbital_start();
    killstreak_match_active = TRUE;
}

void killstreak_rewards_powerup(long player_index, short powerup_type)
{
    struct killstreak_reward_state *state = killstreak_state(player_index);

    if (state && state->active && state->player_index == player_index &&
        powerup_type == _player_powerup_active_camouflage)
        state->owns_camouflage = FALSE;
}

void killstreak_rewards_update(void)
{
    struct killstreak_reward_state *state;
    struct player_datum *player;
    struct unit_datum *unit;
    unsigned int slot;
    int enabled = killstreak_authority() && killstreak_match_active &&
        game_engine_in_play() &&
        killstreak_feature_allowed(KILLSTREAK_ENABLED);

    killstreak_recon_update();
    killstreak_orbital_update();
    for (slot = 0; slot < HALO_PORT_MAXIMUM_NETWORK_PLAYERS; slot++)
    {
        state = &killstreak_players[slot];
        if (!state->active)
            continue;
        if (!enabled || !(player = player_try_and_get(state->player_index)) ||
            player->quit_out_of_game || player->unit_index != state->unit_index ||
            bots_machine_is_bot(player->network_player_data.machine_index) ||
            !(unit = unit_try_and_get(state->unit_index)) ||
            unit->unit.player_index != state->player_index ||
            TEST_FLAG(unit->object.damage_flags, _object_dead_bit))
            killstreak_rewards_reset_player(state->player_index);
        else if (player->powerup_durations[_player_powerup_active_camouflage] == 0)
            state->owns_camouflage = FALSE;
    }
}

void killstreak_rewards_record_kill(long credited_player_index, long dead_player_index)
{
    struct killstreak_reward_state *state;
    struct player_datum *player;
    struct player_datum *victim;
    struct unit_datum *unit;
    boolean owns_camouflage;

    /* A replicated permission bit is not authority to grant an effect. */
    if (!killstreak_authority() || !killstreak_match_active ||
        !game_engine_in_play() ||
        !killstreak_feature_allowed(KILLSTREAK_ENABLED))
        return;
    state = killstreak_state(credited_player_index);
    if (!state || credited_player_index == dead_player_index ||
        !(player = player_try_and_get(credited_player_index)) ||
        !(victim = player_try_and_get(dead_player_index)) ||
        player->quit_out_of_game || victim->quit_out_of_game ||
        bots_machine_is_bot(player->network_player_data.machine_index) ||
        !game_team_is_enemy(victim->team_index, (short)player->team_index))
        return;
    if (player->unit_index == NONE ||
        !(unit = unit_try_and_get(player->unit_index)) ||
        unit->unit.player_index != credited_player_index ||
        TEST_FLAG(unit->object.damage_flags, _object_dead_bit))
    {
        killstreak_rewards_reset_player(credited_player_index);
        return;
    }
    /* Full salted datum indices, not just array slots, identify a life. */
    if (!state->active || state->player_index != credited_player_index ||
        state->unit_index != player->unit_index)
    {
        memset(state, 0, sizeof(*state));
        state->active = TRUE;
        state->player_index = credited_player_index;
        state->unit_index = player->unit_index;
    }
    if (state->kills >= 10)
        return;
    state->kills++;
    if (state->kills == 10)
        killstreak_orbital_award(credited_player_index, player->unit_index);
    if (state->kills != 3 && state->kills != 5)
        return;

    owns_camouflage = state->owns_camouflage ||
        (player->powerup_durations[_player_powerup_active_camouflage] == 0 &&
         !TEST_FLAG(unit->unit.flags, _unit_active_camouflaged_bit));
    /* MAX(existing, 900): refresh, never stack or shorten a map pickup.
     * The helper's notification is for other callers; restore our ownership. */
    player_handle_powerup_minor(credited_player_index,
        _player_powerup_active_camouflage, 900);
    state->owns_camouflage = owns_camouflage;
    /* Stock pickup behavior: starts charging only at shield vitality <= 1.
     * Existing overshields stay intact, with stock damage and decay. */
    /* No-shield variants and Infection roles have no capacity to finish
     * charging. Do not leave their units in the charging damage state. */
    if (unit->object.maximum_shield_vitality > 0.0f)
        object_double_charge_shield(player->unit_index);
    killstreak_recon_award(credited_player_index, player->unit_index, state->kills);
}
