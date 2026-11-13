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

    def test_original_android_callouts_no_stock_clip_dependency(self):
        java = read("port/android/app/src/main/java/com/halo/decomp/HaloActivity.java")
        audio = function(read("source/game/game_engine_multiplayer_sounds.c"),
                         "game_engine_play_killstreak_sound")
        recon = function(read("port/linux/game/killstreak_recon.c"),
                         "killstreak_recon_present")
        host = read("port/android/host/host_sdl.c")
        guest = read("port/android/guest/runtime/guest_host.h")
        imports = read("port/android/host_imports.list")
        manifest = read("port/android/app/src/main/AndroidManifest.xml")
        self.assertIn("host_android_announce_killstreak((int)award)", audio)
        self.assertNotIn("_multiplayer_sound_triple_kill", audio)
        self.assertNotIn("_multiplayer_sound_killing_spree", audio)
        self.assertNotIn("unspatialized_impulse_sound_new", audio)
        self.assertIn('L"3 kills: Enhancements online"', recon)
        self.assertIn('L"5 kills: Recon enabled (30s)"', recon)
        self.assertIn("announceKillstreak(int award)", java)
        self.assertIn("new SoundPool.Builder()", java)
        self.assertIn("SoundPool", java)
        self.assertIn("R.raw.halo_reward_enhancements", java)
        self.assertIn("R.raw.halo_reward_recon", java)
        self.assertIn("rewardCallouts.play(sample", java)
        self.assertIn("rewardCallouts.stop(rewardActiveStream)", java)
        self.assertIn("rewardCallouts.release()", java)
        self.assertIn("!rewardActivityResumed", java)
        self.assertNotIn("TextToSpeech", java)
        self.assertNotIn("speech.tts", java)
        self.assertIn("SDL_GetAndroidJNIEnv()", host)
        self.assertIn("SDL_GetAndroidActivity()", host)
        self.assertIn('GetMethodID(env, activity_class, "announceKillstreak", "(I)V")', host)
        self.assertIn("host_android_announce_killstreak", guest)
        self.assertIn("host_android_announce_killstreak", imports)
        self.assertNotIn("android.intent.action.TTS_SERVICE", manifest)

        self.assertIn("local_player_get_next(local_player_index) != NONE", audio)

    def test_bundled_radio_wavs_are_original_offline_resources(self):
        import hashlib
        import wave
        raw = ROOT / "port/android/app/src/main/res/raw"
        expected = {
            "halo_reward_enhancements.wav":
                "a361983ed1b0f7cc7f88618db9caf02dfdaae4f46d00e00ff5ca207a3586b786",
            "halo_reward_recon.wav":
                "599153153e501161b9b942923f16c18abf849abf1070e9f767c0c91322ff5b3f",
        }
        for filename, sha in expected.items():
            with self.subTest(clip=filename):
                clip = raw / filename
                self.assertTrue(clip.is_file(), filename)
                self.assertLess(clip.stat().st_size, 200000)
                with clip.open("rb") as stream:
                    self.assertEqual(hashlib.file_digest(stream, "sha256").hexdigest(), sha)
                with wave.open(str(clip), "rb") as audio:
                    self.assertEqual(audio.getnchannels(), 1)
                    self.assertEqual(audio.getsampwidth(), 2)
                    self.assertEqual(audio.getframerate(), 24000)
                    self.assertGreater(audio.getnframes(), 20000)
        # v24 only changes the five-kill phrase. The original approved
        # Enhancements clip remains exact; new Recon enabled uses the same
        # original British voice model and has its own pinned PCM hash.
        combined = bytearray()
        for filename in expected:
            with wave.open(str(raw / filename), "rb") as wav:
                pcm = wav.readframes(wav.getnframes())
                import array
                values = array.array("h")
                values.frombytes(pcm)
                if __import__("sys").byteorder != "little":
                    values.byteswap()
                audible = [i for i, v in enumerate(values) if v != 0]
                self.assertTrue(audible)
                self.assertGreaterEqual(audible[0] / 24000, 0.28)
                self.assertGreaterEqual((len(values) - audible[-1] - 1) / 24000, 0.25)
                combined.extend(pcm)
        self.assertEqual(
            hashlib.sha256(combined).hexdigest(),
            "9bf5adefbb31d55f3c5b58281ef9be139ab9c453ef215f7534a6f97b1cdb92df")
        java = read("port/android/app/src/main/java/com/halo/decomp/HaloActivity.java")
        self.assertNotIn("killstreakSpeech", java)
        self.assertIn("initializeRewardCallouts()", java)
        self.assertIn("rewardCallouts.play(sample", java)

if __name__ == "__main__":
    unittest.main()
