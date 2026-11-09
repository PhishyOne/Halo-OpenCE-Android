"""Source-level checks for the standalone Android Halo launcher.

The actual Activity is compiled by the Android-only CI job. These checks
catch regression of the single Play footer and LAN/offline separation.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java"


class HaloLauncherLayoutTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = LAUNCHER.read_text()

    def test_two_modes_above_one_play(self):
        s = self.source
        self.assertIn('offlineMode.setText("OFFLINE BOTS");', s)
        self.assertIn('lanMode.setText("LAN / SYSTEM LINK");', s)
        self.assertIn('play.setText("PLAY");', s)
        self.assertLess(s.index('footer.addView(choices);'), s.index('footer.addView(play, playParams);'))
        self.assertEqual(s.count('play.setText("PLAY");'), 1)
        self.assertNotIn('Save settings & Open Halo', s)

    def test_modes_do_not_overlap_and_standard_remains_available(self):
        s = self.source
        self.assertIn('if (selected)\n                lanMode.setChecked(false);', s)
        self.assertIn('if (selected)\n                offlineMode.setChecked(false);', s)
        self.assertIn('offlinePanel.setVisibility(offlineMode.isChecked()', s)
        self.assertIn('lanPanel.setVisibility(lanMode.isChecked()', s)
        self.assertIn('launcherPreferences.edit().putString("play_mode", "standard")', s)

    def test_offline_uses_original_native_arguments(self):
        s = self.source
        self.assertIn('getSharedPreferences("offline_bots", MODE_PRIVATE)', s)
        self.assertIn('HaloActivity.OFFLINE_BOT_ARGUMENTS', s)
        self.assertIn('"--offline-bots", Integer.toString(count)', s)
        self.assertIn('Boolean.toString(crews)', s)
        self.assertNotIn('showOfflineBotOptions(', s)
        self.assertNotIn('new AlertDialog.Builder(this)', s)

    def test_lan_uses_same_validation(self):
        s = self.source
        self.assertIn('LanConfig.peerForRole(hostRole.isChecked()', s)
        self.assertIn('LanConfig.save(new File(dataRoot, "config.toml")', s)
        self.assertIn('hosting && objectiveBotsLan.isChecked()', s)
        self.assertIn('hosting && vehicleSeatsLan.isChecked()', s)
        self.assertIn('if (!canStartGame(feedback, appSettings))', s)
        self.assertIn('if (invitePending)', s)

    def test_no_test_build_copy(self):
        s = self.source
        for phrase in ('4 bots have been tested', 'stress test', 'Experimental: bots', 'Play Together'):
            with self.subTest(phrase=phrase):
                self.assertNotIn(phrase, s)
        self.assertIn('hudSurface(', s)
        self.assertIn('hudCheck(', s)

    def test_disc_import_is_preserved(self):
        s = self.source
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', s)
        self.assertIn('XisoExtractor.extractMaps(channel, dataRoot', s)
        self.assertIn('pick.setText("IMPORT DISC DATA");', s)


if __name__ == "__main__":
    unittest.main()
