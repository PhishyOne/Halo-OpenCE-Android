"""Halo v52: achievement card details, privacy and unchanged career logic."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class AchievementDetailUiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = (JAVA / "CareerProfile.java").read_text()
        cls.model = (JAVA / "CareerRecords.java").read_text()
        cls.icon = (JAVA / "AchievementMedal.java").read_text()

    def test_earned_medals_show_actual_name_rarity_and_requirement(self):
        s = self.profile
        start = s.index("private static void showAchievementDetails(")
        end = s.index("private static LinearLayout threeMedals(", start)
        popup = s[start:end]
        self.assertIn("medal.title", popup)
        self.assertIn("medal.rarity", popup)
        self.assertIn("medal.description", popup)
        self.assertIn('"UNLOCK REQUIREMENT"', popup)
        self.assertIn('"SPARTAN ACHIEVEMENT"', popup)
        self.assertIn('UNLOCKED', popup)
        self.assertIn("new AchievementMedal(activity, medal.rarity)", popup)
        self.assertIn("new ScrollView(activity)", popup)
        self.assertIn(".setView(scroll)", popup)
        self.assertIn("activity.isFinishing() || activity.isDestroyed()", popup)

    def test_locked_slots_reveal_no_future_unlock_names(self):
        s = self.profile
        start = s.index("private static void showAchievementDetails(")
        end = s.index("LinearLayout details = new LinearLayout(activity);", start)
        locked = s[start:end]
        self.assertIn("medal == null || !medal.earned", locked)
        self.assertIn("Not earned yet.", locked)
        self.assertIn("return;", locked)
        self.assertNotIn("career.achievements()", locked)
        self.assertNotIn("medal.description", locked)

    def test_whole_card_clickable_and_accessible(self):
        s = self.profile
        start = s.index("private static LinearLayout threeMedals(")
        end = s.index("private static void section(", start)
        tiles = s[start:end]
        self.assertIn("tile.setClickable(true)", tiles)
        self.assertIn("tile.setFocusable(true)", tiles)
        self.assertIn("tile.setContentDescription(", tiles)
        self.assertIn("tile.setOnClickListener(view ->", tiles)
        self.assertIn("showAchievementDetails(activity, medal, slot)", tiles)
        self.assertIn("tile.setForeground(new android.graphics.drawable.RippleDrawable(", tiles)
        self.assertIn("index < 3", tiles)
        self.assertIn('medal == null ? "LOCKED" : medal.title', tiles)

    def test_unlock_data_and_persistence_untouched(self):
        self.assertIn('final String title, description, rarity;', self.model)
        self.assertIn('final boolean earned;', self.model)
        self.assertIn('earned.sort((left, right) ->', self.model)
        self.assertIn('earned.subList(0, Math.min(3, earned.size()))', self.model)
        self.assertIn("medal.earned", self.model)
        self.assertIn('new Achievement("First Blood", "Earn your first kill"', self.model)
        self.assertIn('"COMMON"', self.icon)
        self.assertIn('"LOCKED"', self.icon)
        self.assertIn('career_profile_v1', self.profile)


if __name__ == "__main__":
    unittest.main()
