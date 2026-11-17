"""v29 bot-mode policy and read-only authoritative scoring integration.

Pure C tests exercise the modes' goal-selection rules in
tools.test_bot_objective_policy. This also verifies their *production*
wiring without assuming a fake race checkpoint or ball object.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BOTS = (ROOT/"port/linux/game/bots.c").read_text()
POLICY = (ROOT/"port/linux/game/bots_objectives_policy.h").read_text()
SERVER = (ROOT/"source/networking/network_server_manager.c").read_text()
RACE = (ROOT/"source/game/game_engine_race.c").read_text()
HEADER = (ROOT/"source/game/game_engine_race.h").read_text()
ENGINE = (ROOT/"source/game/game_engine.h").read_text()
LIMITS = (ROOT/"port/linux/include/halo_port_limits.h").read_text()

class AllMultiplayerBotModesTest(unittest.TestCase):
    def test_5_real_halo_engine_types(self):
        for index in ("game_engine_slayer", "game_engine_ctf",
                      "game_engine_king", "game_engine_oddball",
                      "game_engine_race"):
            self.assertIn(index, ENGINE)
            self.assertIn("server->game.variant.game_engine_index == "+index, SERVER)
            if index != "game_engine_slayer":
                self.assertIn("variant->game_engine_index == "+index, BOTS)
        self.assertIn('!config_boolean("network.online")', SERVER)
        self.assertIn('config_boolean("bots.objective_modes")', SERVER)
        self.assertIn("server->state != _network_game_server_state_pregame", SERVER)
        self.assertIn("server->sent_start_game_message", SERVER)
        self.assertIn("network_game_server_send_game_data_pregame(server)", SERVER)
        self.assertIn("HALO_PORT_NETWORK_VERSION 54", LIMITS)
        self.assertNotIn(chr(92)+"t/* All five original", SERVER)
        self.assertNotIn(chr(92)+"t   simulated bots.", SERVER)

    def test_oddball_uses_live_engine_goals_and_normal_pickup(self):
        for fragment in (
            "BOTS_OBJECTIVE_MAX_ODDBALLS 16",
            "bots_oddball_goal_slot(int bot_index, unsigned int active_goals,",
            "return -1;",
            "pick = bot_index % count;",
        ):
            self.assertIn(fragment, POLICY)
        for fragment in (
            "game_engine_get_goal_in_use((short)slot)",
            "game_engine_get_goal_position(&point, (short)goal_slot)",
            "bots_oddball_goal_slot(bot_index, active, FALSE)",
            "game_engine_variant.oddball.ball_spawn_count",
            "player_has_ball(index)",
            "bots_oddball_carrier_escape(bot, bot_index)",
            "variant->game_engine_index == game_engine_oddball ||",
            "tick->flags |= FLAG(_unit_control_swap_weapons_bit)",
        ):
            self.assertIn(fragment, BOTS)
        self.assertIn("bots_carries_objective(tick.player, bot->player_index)", BOTS)
        self.assertIn("bots_bot_objective_action(bot, &tick)", BOTS)

    def test_race_only_reads_legal_player_checkpoints(self):
        self.assertIn("unsigned long race_engine_bot_eligible_goals(long player_index)",
            HEADER)
        begin=RACE.index("unsigned long race_engine_bot_eligible_goals(long player_index)")
        end=RACE.index("static long count_bits_32(",begin)
        fn=RACE[begin:end]
        for fragment in (
            "game_engine_get_variant()->game_engine_index != game_engine_race",
            "DATUM_INDEX_TO_ABSOLUTE_INDEX(player_index)",
            "VALID_INDEX(absolute_index, MULTIPLAYER_MAXIMUM_PLAYERS)",
            "player_try_and_get(player_index)",
            "player->unit_index == NONE",
            "slot < MAXIMUM_RACE_FLAGS",
            "TEST_FLAG(race_globals.lap_completed_value, slot)",
            "race_goal_matches_player(player_index, slot)",
            "SET_FLAG(eligible, slot, TRUE)",
        ):
            self.assertIn(fragment, fn)
        for forbidden in ("race_complete_lap(", "race_touch_flag(",
                          "race_globals.lap_bit_vector[absolute_index] =",
                          "player->statistics.multiplayer_statistics.race_statistics.laps =",
                          "game_engine_set_goal_position("):
            self.assertNotIn(forbidden, fn)
        self.assertIn("race_engine_bot_eligible_goals(player_index)", BOTS)
        self.assertIn("bots_race_goal_slot(bot_index, active)", BOTS)
        self.assertIn("BOTS_OBJECTIVE_MAX_RACE_CHECKPOINTS 32", POLICY)
        self.assertIn("game_engine_get_variant()->game_engine_index != game_engine_race", BOTS)
        self.assertNotIn("game_engine_get_type()", BOTS)
        self.assertNotIn("game_engine_get_type()", RACE)

    def test_original_ctf_and_king_behavior_preserved(self):
        for s in ("bots_ctf_goal_slot(bot_index, player->team_index,",
                  "bots_carries_flag(player), active)",
                  "bots_king_goal_slot(bot_index, active)",
                  "BOTS_OBJECTIVE_MAX_KING_HILLS"):
            self.assertIn(s, BOTS)
        self.assertIn("bots_carries_flag(player)", BOTS)
        self.assertIn("config_boolean(\"bots.vehicle_seats\")", BOTS)
        self.assertIn("bots_system_link_wanted_count()", SERVER)

if __name__=="__main__":
    unittest.main()
