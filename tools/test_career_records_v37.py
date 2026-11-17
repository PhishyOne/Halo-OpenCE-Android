"""Contract tests for Android-only career recorder and isolated service record."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CareerV37Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.native = (ROOT / "port/linux/game/career_record.c").read_text()
        cls.stats = (ROOT / "source/game/game_statistics.c").read_text()
        cls.engine = (ROOT / "source/game/game_engine.c").read_text()
        cls.launcher = (ROOT / "port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java").read_text()
        cls.reader = (ROOT / "port/android/app/src/main/java/com/halo/decomp/CareerRecords.java").read_text()

    def test_postgame_snapshot_once(self):
        self.assertIn("career_record_start();", self.stats)
        self.assertIn("career_record_postgame(", self.engine)
        self.assertIn("if (recorded || game_type < game_engine_ctf", self.native)
        self.assertIn("recorded = 1;", self.native)
        self.assertIn('career-matches-v1.log', self.native)
        self.assertIn('fopen(path, "ab")', self.native)
        self.assertIn("fflush(ledger)", self.native)
        self.assertIn("fsync(fileno(ledger))", self.native)

    def test_local_player_only_bots_identified_by_machine(self):
        self.assertIn("local_slot != 0 || player->quit_out_of_game", self.native)
        self.assertIn("bots_machine_is_bot(dead_player->network_player_data.machine_index)",
                      self.stats)
        self.assertIn("if (humans + bots != total)", self.native)
        self.assertIn("humans = bots = -1;", self.native)

    def test_no_cross_platform_or_network_protocol_change(self):
        self.assertIn("#ifdef HALO_ANDROID", self.native)
        self.assertNotIn('#include "platform.h"', self.native)
        self.assertNotIn("struct stat", self.native)
        self.assertIn("ftell(previous)", self.native)
        self.assertIn("extern const char *platform_data_root(void);", self.native)
        self.assertIn("#ifdef HALO_ANDROID", self.stats)
        self.assertIn("#ifdef HALO_ANDROID", self.engine)
        self.assertNotIn("network_send", self.native)
        self.assertNotIn("network_protocol", self.native)

    def test_ui_and_reader_have_limits(self):
        self.assertIn("CareerProfile.show(this, dataRoot)", self.launcher)
        self.assertIn('MY SPARTAN PROFILE & ACHIEVEMENTS', self.launcher)
        self.assertIn("MAX_FILE_BYTES = 2 * 1024 * 1024", self.reader)
        self.assertIn("Set<String> seen = new HashSet<>()", self.reader)
        self.assertIn("if (content[i] == '\\n')", self.reader)
        self.assertIn("unknownOpponentKills", self.reader)
        self.assertIn("Math.min(30, Math.max(0, match.botKills))", self.reader)


if __name__ == "__main__":
    unittest.main()
