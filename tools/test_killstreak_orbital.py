"""Offline source-backed orbital state, input, wire and damage tests.

The complete reward/orbital modules and real kill helper run in a fake world.
Collision responses model geometry; real BSPs, native ABI and phones still need
acceptance testing. CC=gcc enforces the same gnu89/Wall/Wextra/Werror gate.
"""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import wave
sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import constant, enum_with, function, mutated, read, structure
ROOT = Path(__file__).resolve().parents[1]
CASES = ("award", "wire", "authority", "replay", "lifecycle", "geometry", "impact",
         "input", "retry", "presentation", "damage", "capacity")


def generated(fault=None):
    network = read("port/linux/game/network_distributed.h")
    constants = enum_with(read("source/game/game.h"), "_game_connection_local") + "\n"
    for name in ("HALO_PORT_MAXIMUM_NETWORK_PLAYERS", "HALO_PORT_MAXIMUM_NETWORK_MACHINES"):
        constants += f"#define {name} {constant(read('port/linux/include/halo_port_limits.h'), name)}\n"
    for suffix in ("award", "request", "incoming"):
        name = "_distributed_message_orbital_" + suffix
        constants += f"#define {name} {constant(network, name)}\n"
    constants += structure(network, "distributed_message_header").replace("long game_time;", "int game_time;") + "\n"
    production = function(read("source/input/input_abstraction.c"), "input_abstraction_orbital_chord") + "\n"
    production += function(read("source/objects/damage.c"), "damage_kill_object_for_player") + "\n"
    for module in ("orbital", "rewards"):
        production += re.sub(r'^#include .*$', '', read(f"port/linux/game/killstreak_{module}.c"), flags=re.M) + "\n"
    if fault:
        production = mutated(production, *fault)
    return constants, production


class OrbitalTest(unittest.TestCase):
    def compile_run(self, cases, fault=None, expected=0):
        cc = os.environ.get("CC") or shutil.which("gcc") or shutil.which("cc")
        self.assertIsNotNone(cc, "Set CC to a local C compiler; no downloads are performed")
        with tempfile.TemporaryDirectory(prefix="orbital-") as tmp:
            work = Path(tmp)
            constants, production = generated(fault)
            (work / "constants.inc").write_text(constants)
            (work / "production.inc").write_text(production)
            result = subprocess.run([cc, "-std=gnu89", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
                "-I", str(work), "-I", str(ROOT / "port/linux/game"),
                "-I", str(ROOT / "source/bungie_net/common"),
                str(ROOT / "tools/harness/tests/killstreak_orbital.c"), "-o", str(work / "test")],
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for case in cases:
                with self.subTest(case=case):
                    result = subprocess.run([str(work / "test"), case], capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                    if expected == 0:
                        print(result.stdout.strip())

    def test_behavior(self):
        self.compile_run(CASES)

    def test_negative_controls(self):
        for fault, case in (
            (("if (state->kills >= 10)", "if (state->kills >= 5)"), "award"),
            (("if (state->kills == 10)", "if (state->kills == 9)"), "award"),
            (("network_connection_last_read_was_unreliable() ||", "0 ||"), "wire"),
            (("m.header.count != 1 || m.reserved ||", "m.header.count != 1 ||"), "wire"),
            (("p->network_player_data.machine_index != machine ||", "0 ||"), "authority"),
            (("!distributed_machine_has_player(machine, (short)m->player_slot)", "0"), "authority"),
            (("!killstreak_rewards_has_ten(id, m->unit_index) ||", "0 ||"), "authority"),
            (("!s->active || s->consumed ||", "!s->active ||"), "replay"),
            (("s->serial != m->serial ||", "0 ||"), "replay"),
            (("if (!orbital_geometry(m->unit_index, &m->target))", "if (0)"), "geometry"),
            (("game_time_get() >= s->impact_tick", "1"), "impact"),
            (("!game_team_is_enemy(p->team_index, (short)strike.team)", "0"), "impact"),
            (("!orbital_near(&unit->object.position, &strike.target, ORBITAL_RADIUS)", "(orbital_near(&unit->object.position, &strike.target, ORBITAL_RADIUS) && 0)"), "impact"),
            (("down && !flashlight_down[controller_index] &&", "down && (flashlight_down[controller_index] ? down : down) &&"), "input"),
            (("game_time_get() >= orbital_local[local].request_timeout", "0"), "retry"),
            (("m->serial <= w->serial", "0"), "presentation"),
        ):
            with self.subTest(fault=fault):
                self.compile_run((case,), fault, 1)

    def test_wiring_and_original_audio(self):
        network = read("port/linux/game/network_distributed.c")
        handler = function(network, "network_distributed_handle_message")
        self.assertIn("if (!distributed_handling_batch)\n\t\t\tkillstreak_orbital_handle_message", handler)
        for path in ("source/input/input_abstraction.c", "source/interface/hud.c", "source/objects/damage.c"):
            self.assertIn("port:", read(path))
        hud = function(read("source/interface/hud.c"), "hud_draw_screen")
        self.assertRegex(hud, r"if \(!cinematic_in_progress\(\)\)\s+killstreak_orbital_present\(render.local_player_index\)")
        self.assertIn("killstreak_recon_present(render.local_player_index)", hud)
        self.assertIn("hud_scripted_globals->show_hud", hud)
        self.assertIn("tools/test_killstreak_orbital.py -v", read(".github/workflows/build.yml"))
        self.assertEqual(constant(read("port/linux/include/halo_port_limits.h"), "HALO_PORT_NETWORK_VERSION"), 47)
        raw = ROOT / "port/android/app/src/main/res/raw"
        for name, sha in (
            ("halo_reward_orbital_authorized.wav", "538b9024ef39a94d6f031b1e6a7f9bbfc7b341689d36ee3a8cbbea1d6772b546"),
            ("halo_reward_orbital_incoming.wav", "1155ad38bc3d1f32671159d783e5eb50b0a7be195de5c69f1e0cf19751726315"),
        ):
            self.assertEqual(hashlib.sha256((raw / name).read_bytes()).hexdigest(), sha)
            with wave.open(str(raw / name)) as w:
                self.assertEqual((w.getnchannels(), w.getsampwidth(), w.getframerate()), (1, 2, 24000))
        for name in ("Authorized", "Incoming"):
            java = read("port/android/app/src/main/java/com/halo/decomp/HaloActivity.java")
            self.assertIn(f"rewardOrbital{name}Loaded = status == 0", java)
            self.assertIn(f"rewardOrbital{name}Sample", java)
        module = read("port/linux/game/killstreak_orbital.c")
        for name in ("orbital_accept", "orbital_receive", "killstreak_orbital_award"):
            body = function(module, name)
            self.assertNotIn("game_engine_play_killstreak_sound", body)
            self.assertNotIn("hud_print_message", body)
        self.assertNotIn("COMMANDER_", module)

if __name__ == "__main__":
    unittest.main()
