"""Compile the real reward module, kill-credit path and powerup helpers in a fake world.

Uses the host C ABI for portability: this is source behavior coverage, not a
32-bit game build, Android runtime test, or physical LAN acceptance.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import constant, enum_with, function, mutated, read

ROOT = Path(__file__).resolve().parents[1]
CASES = ("thresholds", "life-streak-not-multikill", "authority-modes", "excluded", "credit", "resets", "identities", "pickups", "postgame")


def generated(fault=None):
    game = read("source/game/game.h")
    players = read("source/game/players.c")
    policy = read("port/linux/game/commander_features.c")
    bots = read("port/linux/game/bots.c")
    constants = enum_with(game, "_game_connection_local") + "\n"
    constants += enum_with(read("source/game/game_engine.h"), "game_engine_slayer") + "\n"
    constants += enum_with(read("source/game/players.h"), "_player_powerup_active_camouflage") + "\n"
    for name in ("HALO_PORT_MAXIMUM_NETWORK_PLAYERS", "HALO_PORT_MAXIMUM_NETWORK_MACHINES"):
        constants += f"#define {name} {constant(read('port/linux/include/halo_port_limits.h'), name)}\n"
    for line in read("port/linux/game/bots.h").splitlines():
        if line.startswith("#define BOTS_"):
            constants += line + "\n"
    production = "\n".join(function(policy, name) for name in (
        "commander_multiplayer_game", "commander_team_game", "commander_host_selected_mask",
        "commander_features_mask", "killstreak_feature_allowed")) + "\n"
    production += function(bots, "bots_first_machine") + "\n" + function(bots, "bots_machine_is_bot") + "\n"
    production += re.sub(r'^#include .*$', '', read("port/linux/game/killstreak_rewards.c"), flags=re.M)
    for name in ("player_powerup_on", "player_powerup_additional", "player_powerup_off",
                 "player_update_powerups", "player_handle_powerup", "player_handle_powerup_minor"):
        production += "\n" + function(players, name)
    production += "\n" + function(read("source/objects/damage.c"), "object_double_charge_shield")
    for name in ("game_statistics_start", "game_statistics_stop", "game_statistics_record_kill"):
        production += "\n" + function(read("source/game/game_statistics.c").replace("void\ngame_statistics_stop", "void game_statistics_stop"), name)
    if fault:
        production = mutated(production, *fault)
    return constants, production


class KillstreakRewardsTest(unittest.TestCase):
    def compile_and_run(self, cases, fault=None, expected=0):
        cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertIsNotNone(cc, "a C compiler is required")
        with tempfile.TemporaryDirectory(prefix="killstreak-rewards-") as tmp:
            work = Path(tmp)
            constants, production = generated(fault)
            (work / "constants.inc").write_text(constants)
            (work / "production.inc").write_text(production)
            result = subprocess.run([
                cc, "-std=gnu89", "-Wall", "-Werror", "-Wno-unused-function",
                "-I", str(work), "-I", str(ROOT / "port/linux/game"),
                str(ROOT / "tools/harness/tests/killstreak_rewards.c"),
                "-o", str(work / "test")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for case in cases:
                with self.subTest(case=case):
                    result = subprocess.run([str(work / "test"), case], capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

    def test_source_behavior(self):
        self.compile_and_run(CASES)

    def test_negative_controls(self):
        faults = (
            (("if (unit->object.maximum_shield_vitality > 0.0f)", "if (1)"), "pickups"),
            (("if (!friendly_fire && killing_player_index != dead_player_index)", "if (1)"), "credit"),
            (("if (state->kills != 3 && state->kills != 5)", "if (state->kills != 2 && state->kills != 5)"), "thresholds"),
            (("if (!killstreak_authority() || !killstreak_match_active ||", "if (!killstreak_match_active ||"), "authority-modes"),
            (('bots_machine_is_bot(player->network_player_data.machine_index) ||\n        !game_team_is_enemy(victim->team_index, (short)player->team_index))', '0 ||\n        !game_team_is_enemy(victim->team_index, (short)player->team_index))'), "excluded"),
            (("state->player_index != credited_player_index ||", "0 ||"), "identities"),
            (("state->unit_index != player->unit_index)", "0)"), "identities"),
            (("killstreak_rewards_reset_player(dead_player_index);", "/* missed death */"), "resets"),
            (('game_engine_in_play() &&\n        killstreak_feature_allowed(KILLSTREAK_ENABLED)', 'killstreak_feature_allowed(KILLSTREAK_ENABLED)'), "postgame"),
            (("powerup_type == _player_powerup_active_camouflage)\n        state->owns_camouflage = FALSE;", "powerup_type == _player_powerup_active_camouflage)\n        state->owns_camouflage = TRUE;"), "pickups"),
        )
        for fault, case in faults:
            with self.subTest(fault=fault):
                self.compile_and_run((case,), fault, 1)

    def test_lifecycle_and_build_wiring(self):
        players = read("source/game/players.c")
        for name in ("player_delete", "player_died", "player_spawn", "machine_remove_player",
                     "players_set_local_player_unit"):
            self.assertIn("killstreak_rewards_reset_player(", function(players, name))
        dispose = function(read("source/game/game.c"), "game_dispose_from_old_map")
        self.assertLess(dispose.index("killstreak_rewards_stop();"), dispose.index("objects_dispose_from_old_map();"))
        self.assertIn("killstreak_rewards_update();", read("source/main/main.c"))
        for path in ("source/game/game.c", "source/game/players.c",
                     "source/game/game_statistics.c", "source/main/main.c"):
            self.assertIn('#include "killstreak_rewards.h"', read(path))
        for platform in ("linux", "windows", "android", "web"):
            self.assertRegex(read(f"tools/{platform}_build.py"), r'sorted\(Path\(\w+\["game_sources"\]\)\.glob\("\*\.c"\)\)')
        # Existing packets already convey all resulting engine state.
        network = read("port/linux/game/network_distributed.c")
        for value in ("powerup_durations", "_distributed_unit_camouflaged_bit", "shield_vitality"):
            self.assertIn(value, network)


if __name__ == "__main__":
    unittest.main()
