"""Source-contract checks for v41 career UI, map archive, and HC2 history.

Production native-format and ZIP integrity behavior is exercised separately
by CareerRecordsTest, test_career_native_v37 and CampaignMapTransferTest.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class V41Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = (J / "CareerProfile.java").read_text()
        cls.career = (J / "CareerRecords.java").read_text()
        cls.medal = (J / "AchievementMedal.java").read_text()
        cls.launcher = (J / "LauncherActivity.java").read_text()
        cls.archive = (J / "CampaignAdminArchive.java").read_text()
        cls.native = (ROOT / "port/linux/game/career_record.c").read_text()

    def test_rank_gallery_removed_earned_artwork_only(self):
        self.assertFalse((J / "RankGallery.java").exists())
        self.assertNotIn("VIEW ALL RANKS", self.profile)
        self.assertNotIn("VIEW ALL 12 UNSC RANK INSIGNIAS", self.profile)
        self.assertIn("new RankInsignia(activity, rankIndex)", self.profile)

    def test_three_earned_achievements_and_placeholders(self):
        self.assertIn("TOP ACHIEVEMENTS", self.profile)
        self.assertIn("target.addView(threeMedals(activity, result));", self.profile)
        self.assertIn("index < 3", self.profile)
        self.assertIn("career.topAchievements()", self.profile)
        self.assertIn('"LOCKED"', self.profile)
        self.assertIn('new AchievementMedal(activity,', self.profile)
        self.assertIn('"LEGENDARY"', self.medal)
        self.assertIn("if (medal.earned) earned.add(medal);", self.career)
        self.assertIn("earned.subList(0, Math.min(3, earned.size()))", self.career)

    def test_renaming_refreshes_main_profile_without_tab_switch(self):
        self.assertIn("() -> dashboard(activity, appDirectory, target)", self.profile)
        self.assertIn("Runnable onProfileChanged", self.profile)
        self.assertIn("onProfileChanged.run();", self.profile)
        self.assertIn('pref.edit().putString("name", name).apply();', self.profile)
        self.assertLess(self.profile.index('pref.edit().putString("name", name).apply();'),
                        self.profile.index("onProfileChanged.run();"))
        self.assertIn("show(activity, activity.getExternalFilesDir(null), onProfileChanged);",
                      self.profile)

    def test_detailed_match_history_backwards_compatible(self):
        self.assertIn('"HC1".equals(fields[0])', self.career)
        self.assertIn('"HC2".equals(fields[0])', self.career)
        self.assertIn("fields.length == 25", self.career)
        self.assertIn('return new Match(fields);', self.career)
        self.assertIn('"VIEW COMPLETE MATCH HISTORY"', self.profile)
        self.assertIn('MatchHistoryView.show(activity, career)', self.profile)
        for java_name, native_name in (
            ("flagGrabs", "grabs"), ("oddballTicks", "oddball_ticks"),
            ("ballCarrierKills", "carrier_kills"),
            ("killsAsCarrier", "kills_as_carrier"),
            ("raceLaps", "laps"), ("bestLapTicks", "best_lap_ticks")):
            self.assertIn(java_name, self.career)
            self.assertIn(native_name, self.native)
        self.assertIn('"Accuracy and headshots: Not tracked"', self.profile)
        self.assertNotIn(".shots_fired", self.native)
        self.assertNotIn(".shots_hit", self.native)

    def test_admin_campaign_library_requires_explicit_destination(self):
        self.assertNotIn('setText("EXPORT ADMIN REPAIR LIBRARY")', self.launcher)
        self.assertIn("PICK_ADMIN_CAMPAIGN_ARCHIVE", self.launcher)
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", self.launcher)
        self.assertIn("CampaignAdminArchive.export(", self.launcher)
        self.assertIn("SidewinderRepair.validate(source, name)", self.archive)
        self.assertIn("ZipOutputStream", self.archive)
        self.assertIn("CAMPAIGN-MAP-INVENTORY.txt", self.archive)
        self.assertIn("Files.isSymbolicLink", self.archive)
        self.assertIn('ZipEntry("maps/" + name)', self.archive)
        self.assertIn("source.length() != length", self.archive)
        self.assertNotIn("SidewinderRepair.install(", self.archive)


if __name__ == "__main__":
    unittest.main()
