"""Source-backed private recon/packet/HUD/audio tests; no game binary or assets.

The fake world uses the host ABI, with an int32 game tick in the extracted
wire header to model the game's ILP32 ABI. Native links and LAN remain separate
acceptance checks. No network access, build configuration or SDK is required.
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
from harness import constant, enum_with, function, mutated, read, structure

ROOT = Path(__file__).resolve().parents[1]
CASES = ("wire", "identity", "replay", "time", "lifecycle", "policy", "unicast", "presentation", "hud")


def generated(fault=None):
    network = read("port/linux/game/network_distributed.h")
    constants = enum_with(read("source/game/game.h"), "_game_connection_local") + "\n"
    constants += enum_with(read("source/game/game_engine_multiplayer_sounds.c"), "_multiplayer_sound_triple_kill") + "\n"
    for name in ("HALO_PORT_MAXIMUM_NETWORK_PLAYERS", "HALO_PORT_MAXIMUM_NETWORK_MACHINES"):
        constants += f"#define {name} {constant(read('port/linux/include/halo_port_limits.h'), name)}\n"
    constants += f"#define _distributed_message_killstreak_recon {constant(network, '_distributed_message_killstreak_recon')}\n"
    constants += structure(network, "distributed_message_header").replace("long game_time;", "int game_time;") + "\n"
    production = re.sub(r'^#include .*$', '', read("port/linux/game/killstreak_recon.c"), flags=re.M)
    production += "\n" + function(read("source/game/game_engine_multiplayer_sounds.c"), "game_engine_play_killstreak_sound")
    production += "\n" + function(read("source/interface/hud.c"), "hud_draw_personal_recon")
    if fault:
        production = mutated(production, *fault)
    return constants, production


class KillstreakReconTest(unittest.TestCase):
    def compile_and_run(self, cases, fault=None, expected=0):
        cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory(prefix="killstreak-recon-") as tmp:
            work = Path(tmp)
            constants, production = generated(fault)
            (work / "constants.inc").write_text(constants)
            (work / "production.inc").write_text(production)
            result = subprocess.run([
                cc, "-std=gnu89", "-Wall", "-Werror", "-Wno-unused-function",
                "-I", str(work), "-I", str(ROOT / "port/linux/game"),
                "-I", str(ROOT / "source/bungie_net/common"),
                str(ROOT / "tools/harness/tests/killstreak_recon.c"),
                "-o", str(work / "test")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for case in cases:
                with self.subTest(case=case):
                    result = subprocess.run([str(work / "test"), case], capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                    if expected == 0:
                        print(result.stdout.strip())

    def test_source_behavior(self):
        self.compile_and_run(CASES)

    def test_negative_controls(self):
        for fault, case in (
            (("machine_index != NONE ||", "0 ||"), "wire"),
            (("network_connection_last_read_was_unreliable() || size != sizeof(message)", "size != sizeof(message)"), "wire"),
            (("message.header.count != 1 || message.reserved != 0 ||", "message.header.count != 1 ||"), "wire"),
            (("local_player_get_player_index(local) != local_player_id", "0"), "identity"),
            (("message->award <= state->awarded", "0"), "replay"),
            (("!game_team_is_enemy((short)viewer->team_index, (short)player->team_index) ||", "0 ||"), "hud"),
            (("drawn < 24", "drawn < 128"), "hud"),
            (("local_player_get_next(local_player_index) != NONE", "0"), "presentation"),
        ):
            with self.subTest(fault=fault):
                self.compile_and_run((case,), fault, 1)

    def test_privacy_and_wiring(self):
        recon = read("port/linux/game/killstreak_recon.c")
        send = function(recon, "recon_send")
        self.assertNotIn("distributed_send(", recon)
        self.assertIn("player->network_player_data.machine_index", send)
        self.assertIn("distributed_send_to_machine_reliably(machine,", send)
        self.assertIn("recon_receive(&message)", send)
        self.assertIn("DATUM_INDEX_TO_ABSOLUTE_INDEX(player_index)", send)
        self.assertIn("distributed_player_from_byte((byte)message->player_index)", recon)
        self.assertIn("local_player_get_player_index(local) != local_player_id", recon)
        self.assertNotIn("COMMANDER_", recon)
        for name in ("recon_receive", "recon_send", "killstreak_recon_handle_message"):
            self.assertNotIn("hud_print_message", function(recon, name))
            self.assertNotIn("game_engine_play_", function(recon, name))
        hud = read("source/interface/hud.c")
        marker = function(hud, "hud_draw_personal_recon")
        for forbidden in ("collision", "hud_player_name_in_sight", "config_", "commander_feature_allowed"):
            self.assertNotIn(forbidden, marker)
        screen = function(hud, "hud_draw_screen")
        self.assertIn("killstreak_recon_present(render.local_player_index)", screen)
        self.assertIn("!cinematic_in_progress() && hud_scripted_globals->show_hud", screen)
        handler = function(read("port/linux/game/network_distributed.c"), "network_distributed_handle_message")
        self.assertIn("if (!distributed_handling_batch)", handler)
        self.assertIn("killstreak_recon_handle_message(machine_index, message, size)", handler)
        # v16 takes its default path and rejects optional unknown message 97.
        self.assertIn("header.type >= NUMBER_OF_DISTRIBUTED_MESSAGES", handler)
        self.assertIn("default: entry_size = network_objects_entry_size(header.type)", handler)
        self.assertIn("killstreak_recon_stop();", read("port/linux/game/killstreak_rewards.c"))
        self.assertIn("tools/test_killstreak_recon.py -v", read(".github/workflows/build.yml"))


if __name__ == "__main__":
    unittest.main()
