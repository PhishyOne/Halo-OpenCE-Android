"""v39 UI consistency, rank insignia and safe touch placement.
These tests validate source/asset contracts; physical device checks still apply.
"""
from pathlib import Path
import hashlib
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"
ASSETS = ROOT / "port/android/app/src/main/assets/fonts"


class VisualDesignV39Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.launcher = (JAVA / "LauncherActivity.java").read_text()
        cls.career = (JAVA / "CareerProfile.java").read_text()
        cls.badges = (JAVA / "RankInsignia.java").read_text()
        cls.theme = (JAVA / "HaloTheme.java").read_text()
        cls.lobby = (JAVA / "LobbyBotControls.java").read_text()
        cls.game = (JAVA / "HaloActivity.java").read_text()
        cls.touch = (JAVA / "TouchControls.java").read_text()

    def test_rank_insignias_are_12_distinct_scalable_designs(self):
        for rank in ("Recruit", "Private", "Corporal", "Sergeant",
                     "Staff Sergeant", "Lieutenant", "Captain", "Major",
                     "Colonel", "Brigadier", "General", "Legend"):
            self.assertIn('"' + rank + '"', self.badges)
        for expr in ("getWidth()", "getHeight()", "canvas.scale(scale, scale)",
                     "Paint.ANTI_ALIAS_FLAG", "switch (rank)", "case 10:",
                     "default: // Legend", "setContentDescription("):
            self.assertIn(expr, self.badges)
        self.assertIn("new RankInsignia(activity, rankIndex)", self.career)
        self.assertIn("identity(activity, name, result.rankIndex()", self.career)
        self.assertIn("identity(activity, name, career.rankIndex()", self.career)

    def test_exact_native_menu_overpass_fonts_licensed_and_embedded(self):
        for name, min_bytes in (("Overpass-750.ttf", 170000),
                                ("Overpass-900.ttf", 169000)):
            f = ASSETS / name
            self.assertTrue(f.is_file(), f"Missing bundled typeface {name}")
            self.assertGreaterEqual(f.stat().st_size, min_bytes)
        license_file = ASSETS / "Overpass-OFL.txt"
        self.assertIn("SIL OPEN FONT LICENSE", license_file.read_text())
        self.assertIn('heading ? "fonts/Overpass-900.ttf"', self.theme)
        self.assertIn('"fonts/Overpass-750.ttf"', self.theme)
        self.assertIn("Typeface.createFromAsset", self.theme)
        self.assertIn("HaloTheme.font", self.launcher)
        self.assertIn("HaloTheme.font", self.career)

    def test_native_blue_palette_extended_to_android_added_ui(self):
        self.assertIn("FOCUS = Color.rgb(40, 150, 255)", self.theme)
        self.assertIn("HaloTheme.FOCUS", self.launcher)
        self.assertIn("HaloTheme.FOCUS_TEXT", self.launcher)
        self.assertIn("HaloTheme.FOCUS_TEXT", self.career)
        self.assertIn("HaloTheme.PANEL_BOTTOM", self.lobby)
        self.assertIn("HaloTheme.font(button, true)", self.lobby)
        self.assertIn("0x992896ff", self.touch)
        self.assertIn("HaloTheme.face(getContext(), false)", self.touch)
        self.assertIn("HaloTheme.TEXT", self.touch)
        self.assertNotIn("0x9983d9ff", self.touch)

    def test_bot_lobby_control_moved_down_without_entering_gesture_region(self):
        self.assertNotIn("buttons.bottomMargin = (int)(65 *", self.game)
        self.assertIn("buttons.bottomMargin = lobbyBotSafeBottomMargin(0);",
                      self.game)
        self.assertIn("int baseline = Math.round(34 * density);", self.game)
        self.assertIn("gestureBottomPixels) + Math.round(12 * density)", self.game)
        self.assertIn("return Math.max(baseline, safe);", self.game)
        self.assertIn("updateLobbyBotPosition();", self.game)
        self.assertIn("lobbyBotControls.setLayoutParams(position);", self.game)
        self.assertIn("Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL", self.game)
        self.assertIn("nativeAdjust(direction)", self.lobby)
        self.assertIn("shownStatus >= 0", self.lobby)

    def test_v39_cleanup_without_changing_lan_or_stats_identity(self):
        self.assertNotIn("HOST TIPS", self.launcher)
        self.assertNotIn("toolsServiceRecord", self.launcher)
        self.assertIn("CareerProfile.dashboard(", self.launcher)
        self.assertIn("class ChoiceButton extends Button", self.launcher)
        self.assertNotIn("import android.widget.Spinner;", self.launcher)
        self.assertIn("setSingleChoiceItems(this.choices, selection,", self.launcher)
        self.assertIn("int getSelectedItemPosition() { return selection; }",
                      self.launcher)
        self.assertIn("LanConfig.save(", self.launcher)
        self.assertIn("LanHostPicker hostPicker", self.launcher)
        self.assertIn("CampaignMapTransfer.export(", self.launcher)


if __name__ == "__main__":
    unittest.main()
