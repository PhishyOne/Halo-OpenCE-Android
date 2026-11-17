"""v38 retirement gate for the unused orbital strike experiment.

The legacy module and packet identifiers remain reserved for compatibility,
but Android must not award, start, or grant orbital/commander abilities. The
new 10-streak award's actual behavior is covered by test_killstreak_rewards.py.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "port/linux/game"
APP = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class RetiredOrbitalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rewards = (GAME / "killstreak_rewards.c").read_text()
        cls.policy = (GAME / "commander_features.c").read_text()
        cls.launcher = (APP / "LauncherActivity.java").read_text()
        cls.android = (APP / "HaloActivity.java").read_text()
        cls.engine_audio = (ROOT / "source/game/game_engine_multiplayer_sounds.c").read_text()

    def test_no_award_or_updater(self):
        for forbidden in ("killstreak_orbital_start(", "killstreak_orbital_update(",
                          "killstreak_orbital_award(", "killstreak_orbital_stop("):
            self.assertNotIn(forbidden, self.rewards)
        self.assertIn("state->immunity_end = now + 1800", self.rewards)
        self.assertIn("_object_cannot_take_damage_bit", self.rewards)

    def test_stale_client_and_config_cannot_grant_orbital(self):
        self.assertIn("#ifdef HALO_ANDROID", self.policy)
        self.assertIn("(KILLSTREAK_ENABLED | KILLSTREAK_ANNOUNCEMENTS)",
                      self.policy)
        self.assertIn('!key.equals("orbital") && checks[i].isChecked()',
                      self.launcher)
        self.assertIn("CommanderConfig.disable(new File(dataRoot,", self.launcher)
        self.assertNotIn("commanderToggle", self.launcher)
        self.assertNotIn("10-KILL ORBITAL BOMBARDMENT", self.launcher)

    def test_legacy_packet_ids_reserved_without_old_audio(self):
        header = (GAME / "network_distributed.h").read_text()
        self.assertIn("_distributed_message_orbital_award = 98", header)
        self.assertIn("_distributed_message_orbital_request = 99", header)
        self.assertIn("_distributed_message_orbital_incoming = 100", header)
        self.assertNotIn("rewardOrbital", self.android)
        self.assertNotIn("rewardBreach", self.android)
        self.assertIn("award != 14", self.engine_audio)
        self.assertIn("R.raw.halo_reward_phantom_warning", self.android)
        for old in ("orbital_authorized", "orbital_incoming",
                    "breach_ready", "breach_incoming"):
            self.assertFalse(
                (ROOT / f"port/android/app/src/main/res/raw/halo_reward_{old}.wav").exists(),
                f"Retired orbital audio is still packaged: {old}")


if __name__ == "__main__":
    unittest.main()
