"""Five-tab launcher and flat, directly visible settings."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class FlatLauncherTest(unittest.TestCase):
    def setUp(self):
        self.s = (J / "LauncherActivity.java").read_text()

    def test_five_tabs_and_offline_choice(self):
        self.assertIn('"Leaderboard", "Offline Match", "Host", "Join", "Tools"', self.s)
        self.assertNotIn("modeTabs[5]", self.s)
        self.assertIn('botMatch.setText("BOT MATCH")', self.s)
        self.assertIn('campaign.setText("CAMPAIGN")', self.s)
        self.assertIn("offlineLeft.addView(offlineType)", self.s)
        self.assertIn('getString("offline_game_type",', self.s)
        self.assertIn('putString("offline_game_type", bots ? "bots" : "campaign")', self.s)
        self.assertIn("offlineMode.setChecked(botMatch.isChecked())", self.s)
        self.assertIn("offlinePanel.setVisibility(playing && !lanMode.isChecked()", self.s)
        self.assertIn("offlineBotOptions.setVisibility(offlineMode.isChecked()", self.s)
        self.assertIn("campaignInfo.setVisibility(offlineMode.isChecked()", self.s)
        self.assertIn('lanMode.isChecked() ? (hostRole.isChecked() ? 2 : 3) : 1', self.s)

    def test_recording_controls_only_join_global_prefs(self):
        a = self.s.index('LinearLayout joinPanel =')
        b = self.s.index('roleColumn.addView(joinPanel)', a)
        join = self.s[a:b]
        self.assertIn('addSetupCheck(recordingChecks, recordHighlights)', join)
        self.assertIn('addSetupCheck(recordingChecks, keepFullVideo)', join)
        self.assertIn('putBoolean("record_opt_in", checked)', join)
        self.assertIn('putBoolean("keep_full_match", checked)', join)
        self.assertIn('keepFullVideo.setEnabled(checked)', join)
        self.assertNotIn('footer.addView(recordHighlights)', self.s)
        self.assertNotIn('footer.addView(keepFullVideo)', self.s)
        self.assertIn('getBoolean("record_opt_in", false)', self.s)
        self.assertIn("startActivityForResult(request, PICK_REEL_CONSENT)", self.s)
        self.assertIn("all multiplayer modes", join)
        self.assertIn("No campaign/co-op.", join)
        self.assertEqual(self.s.count('CheckBox recordHighlights ='), 1)
        self.assertEqual(self.s.count('CheckBox keepFullVideo ='), 1)
        self.assertEqual(self.s.count('footer.addView(play, playParams)'), 1)

    def test_no_expand_buttons_and_relevant_mode_guards(self):
        self.assertNotIn("toggleSection(", self.s)
        self.assertNotIn("DisclosureChevron.attach(", self.s)
        for name in ("advancedToggle", "killstreakToggle", "mapsToggle",
                     "mapAdvancedToggle", "customMapsToggle", "savesToggle"):
            self.assertNotIn(name, self.s)
        self.assertIn('advanced.addView(hudSection("NETWORK OPTIONS"))', self.s)
        self.assertIn("networkColumn.addView(advanced)", self.s)
        for panel in ("repairPanel", "advancedMaps", "customMapsPanel", "campaignSavesPanel"):
            column = "toolsLeft" if panel in ("repairPanel", "advancedMaps") else "toolsRight"
            self.assertIn(column + ".addView(" + panel + ")", self.s)
            self.assertNotIn(panel + ".setVisibility(View.GONE)", self.s)
        self.assertIn("boolean available = playing && (offlineMode.isChecked()", self.s)
        self.assertIn("lanMode.isChecked() && hostRole.isChecked()", self.s)
        self.assertIn("killstreakPanel.setVisibility(available ? View.VISIBLE : View.GONE)", self.s)

    def test_existing_launch_and_protection_paths(self):
        self.assertIn("OfflineBotConfig.enable(", self.s)
        self.assertIn("OfflineBotConfig.disable(", self.s)
        self.assertIn('putString("play_mode", "standard")', self.s)
        self.assertIn('putString("play_mode", "offline")', self.s)
        self.assertIn('if (invitePending && !lanMode.isChecked())', self.s)
        self.assertIn('if (!canStartGame(feedback, appSettings))', self.s)
        self.assertIn("hostPicker.search(true, () -> play.performClick())", self.s)
        self.assertIn('LanUpdateClient.check(this, hostIp.getText().toString().trim()', self.s)
        self.assertIn('setTitle("Restore campaign progress?")', self.s)
        self.assertIn('setTitle("Restore the previous " + selected + "?")', self.s)
        self.assertIn('if (repairBusy)', self.s)

if __name__ == "__main__":
    unittest.main()
