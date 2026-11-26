"""v50 launcher UI contracts: theme, disclosure icons, read-only career."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class MatchHistoryUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = (JAVA / "CareerProfile.java").read_text()
        cls.launcher = (JAVA / "LauncherActivity.java").read_text()
        cls.view = (JAVA / "MatchHistoryView.java").read_text()
        cls.model = (JAVA / "MatchHistoryModel.java").read_text()
        cls.arrow = (JAVA / "DisclosureChevron.java").read_text()

    def test_generic_history_dialog_replaced_not_other_dialogs(self):
        begin = self.profile.index("private static void allMatches(")
        end = self.profile.index("private static void showMomentTimeline(", begin)
        body = self.profile[begin:end]
        self.assertIn("MatchHistoryView.show(activity, career)", body)
        self.assertNotIn(".setItems(", body)
        self.assertIn('"VIEW COMPLETE MATCH HISTORY"', self.profile)

    def test_halo_visuals_not_generic_system_list(self):
        self.assertIn('UNSC  /  MATCH ARCHIVE', self.view)
        self.assertIn("HaloTheme.surface(", self.view)
        self.assertIn("HaloTheme.menuButtonColors(", self.view)
        self.assertIn("HaloTheme.font(", self.view)
        self.assertIn("new HaloWindow(activity)", self.view)
        self.assertIn("new ListView(activity)", self.view)
        self.assertIn("setOnItemClickListener(", self.view)
        self.assertIn("MatchHistoryModel.select(career, filter)", self.view)
        self.assertNotIn("new AlertDialog.Builder(", self.view)
        self.assertNotIn("SQLiteDatabase", self.view)
        self.assertNotIn("HttpURLConnection", self.view)

    def test_recycled_cards_scales_to_long_history(self):
        self.assertIn("extends BaseAdapter", self.view)
        self.assertIn("if (reuse == null)", self.view)
        self.assertIn("(Holder) reuse.getTag()", self.view)
        self.assertIn("adapter.notifyDataSetChanged();", self.view)
        self.assertIn("new HashSet<>()", self.view)
        self.assertIn("expandedIds.add(match.id)", self.view)
        self.assertIn("expandedIds.remove(match.id)", self.view)
        self.assertIn("CareerProfile.matchDetails(match)", self.view)
        self.assertIn("h.chevron.setCardExpanded(open)", self.view)
        self.assertIn("h.details.setVisibility(open ? View.VISIBLE : View.GONE);", self.view)

    def test_right_collapsed_down_expanded_and_selected_color(self):
        self.assertIn("if (expanded)", self.arrow)
        self.assertIn("chevron.moveTo(cx - arm", self.arrow)
        self.assertIn("chevron.moveTo(cx - arm * .35f", self.arrow)
        self.assertIn("android.R.attr.state_selected", self.arrow)
        self.assertIn("HaloTheme.HIGHLIGHT_TEXT", self.arrow)
        self.assertIn("void setCardExpanded(boolean open)", self.arrow)

    def test_arrows_only_for_expandable_sections(self):
        found = re.findall(r"DisclosureChevron\.attach\((\w+)\);", self.launcher)
        self.assertEqual(found, [], "Launcher sections are visible, not expandable")
        self.assertNotIn("toggleSection(", self.launcher)
        # Match history still uses its disclosure arrows; only launcher
        # section toggles are removed.
        self.assertIn("h.chevron.setCardExpanded(open)", self.view)
        for action in ["stockCampaignImportButton", "playButton",
                       "stockCampaignAuditButton", "customMapImportButton",
                       "campaignBackupButton"]:
            self.assertNotIn("DisclosureChevron.attach(" + action + ");",
                             self.launcher)

    def test_old_profile_and_local_career_still_available(self):
        self.assertIn("career_profile_v1", self.profile)
        self.assertIn("CareerRecords.load(appDirectory)", self.profile)
        self.assertIn("new RankInsignia(activity, rankIndex)", self.profile)
        self.assertIn("SPARTAN RATING", self.profile)
        self.assertIn("TOP ACHIEVEMENTS", self.profile)
        self.assertIn("MatchHighlights.load(appDirectory)", self.profile)
        self.assertIn("CareerRecords.Match", self.model)


if __name__ == "__main__":
    unittest.main()
