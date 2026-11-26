"""Compact landscape setup and non-destructive Tools regrouping."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class CompactLauncherTest(unittest.TestCase):
    def setUp(self):
        self.s = (J / "LauncherActivity.java").read_text()

    def test_setup_two_column_layouts(self):
        for call in ("setupGrid(offlinePanel, offlineLeft, offlineRight)",
                     "setupGrid(lanPanel, roleColumn, networkColumn)"):
            self.assertIn(call, self.s)
        self.assertIn("new LinearLayout.LayoutParams(0, -2, 1f)", self.s)
        self.assertIn("screenHeightDp <= 340 ? 34 : 42", self.s)
        self.assertIn("offlineLeft.addView(offlineType)", self.s)
        self.assertIn("offlineLeft.addView(offlineBotOptions)", self.s)
        self.assertIn("offlineRight.addView(offlineTeamOptions)", self.s)
        self.assertIn("offlineTeamOptions.setVisibility(offlineMode.isChecked()", self.s)
        self.assertIn("offlineRight.addView(campaignInfo)", self.s)
        self.assertIn("attachSetupPanel(offlineMode.isChecked() ? offlineRight : networkColumn", self.s)
        self.assertIn("((ViewGroup)view.getParent()).removeView(view)", self.s)
        self.assertIn("footer.setVisibility(playing ? View.VISIBLE : View.GONE)", self.s)
        self.assertIn("feedback.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE)", self.s)
        # Keep accessibility/keyboard fallback; normal compact pages fit
        # by reducing actual content height, not by clipping controls.
        self.assertIn("scroll.setFillViewport(true)", self.s)
        self.assertNotIn("scroll.setOnTouchListener", self.s)

    def test_role_specific_controls_and_driving(self):
        self.assertIn("roleColumn.addView(hostPanel)", self.s)
        self.assertIn("roleColumn.addView(joinPanel)", self.s)
        self.assertIn("networkColumn.addView(advanced)", self.s)
        self.assertIn("networkColumn.addView(recordingPanel)", self.s)
        self.assertIn("recordingPanel.setVisibility(hosting ? View.GONE : View.VISIBLE)", self.s)
        self.assertIn("LinearLayout checks = hosting ? hostChecks : joinChecks", self.s)
        self.assertIn("checks.addView(lanOnly, new LinearLayout.LayoutParams(0, -2, 1f))", self.s)
        self.assertEqual(self.s.count("CheckBox lanOnly ="), 1)
        self.assertIn("setupInput(manualHostPanel, \"HOST'S IP ADDRESS\", hostIp)", self.s)
        self.assertIn("setupInput(advanced, \"YOUR IP (OPTIONAL)\", local)", self.s)
        self.assertNotIn("driverBots", self.s)
        self.assertIn("count, skill, true, true, hosting);", self.s)
        self.assertIn("boolean hosting = hostRole.isChecked() && lanOnly.isChecked()", self.s)
        self.assertIn("int count = hosting ?", self.s)
        self.assertIn("addSetupCheck(recordingChecks, recordHighlights)", self.s)
        self.assertIn("addSetupCheck(recordingChecks, keepFullVideo)", self.s)
        self.assertIn('keepFullVideo.setText("KEEP FULL RECORDING");', self.s)
        self.assertIn("fullRecordingParams.weight = 1.25f", self.s)
        self.assertIn("keepFullVideo.setSingleLine(false)", self.s)
        self.assertIn('putBoolean("keep_full_match", checked)', self.s)

    def test_tools_actions_consolidated_and_no_disclosures(self):
        self.assertIn("toolsPage.addView(repairPanel)", self.s)
        self.assertIn("toolsPage.addView(campaignSavesPanel)", self.s)
        self.assertIn("repairPanel.addView(stockMapResults)", self.s)
        self.assertNotIn("toggleSection(", self.s)
        self.assertNotIn("DisclosureChevron.attach(", self.s)
        for label in ("CHECK MY MAPS", "REPAIR FROM HOST", "SHARE MAPS", "RECEIVE MAPS",
                      "IMPORT MAPS", "BACK UP CAMPAIGN PROGRESS", "RESTORE CAMPAIGN BACKUP"):
            self.assertIn('"' + label + '"', self.s)
        for retired in ("EXPORT ADMIN REPAIR LIBRARY", "RESTORE BACKUP",
                        "IMPORT A MAP FOLDER", "CHECK BEACH ASSAULT RESOURCES",
                        "SAVE DIAGNOSTIC REPORT", "CHOOSE REPLACEMENT"):
            self.assertNotIn('setText("' + retired + '")', self.s)
        repair = (J / "LanMapRepair.java").read_text()
        self.assertIn("pause.setVisibility(View.GONE)", repair)
        self.assertIn("pause.setVisibility(View.VISIBLE)", repair)
        self.assertIn("pause.setOnClickListener(v -> stop())", repair)

if __name__ == "__main__":
    unittest.main()
