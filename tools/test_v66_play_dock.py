"""Compact right-hand Play action never covers required setup controls."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class PlayDockTest(unittest.TestCase):
    def test_single_action_and_measured_overlay(self):
        s = (J / "LauncherActivity.java").read_text()
        d = (J / "LauncherActionDock.java").read_text()
        self.assertIn("new LauncherActionDock(this, scroll, content)", s)
        self.assertIn("root.addView(actionDock,", s)
        self.assertEqual(s.count("footer.addView(play, playParams)"), 1)
        self.assertNotIn("root.addView(footer)", s)
        self.assertIn("actionDock.setActions(footer, statusScroll, statusColumn)", s)
        self.assertIn("actionDock.setSetupPage(", s)
        self.assertIn("lanMode.isChecked() ? lanPanel : offlinePanel", s)
        self.assertIn("lanMode.isChecked() ? networkColumn : offlineRight", s)
        self.assertIn("footer.setVisibility(launchVisible ? View.VISIBLE : View.GONE)", s)
        self.assertIn("statusScroll.setVisibility(launchVisible ? View.VISIBLE : View.GONE)", s)
        self.assertIn("statusColumn.addView(feedback)", s)
        self.assertIn("statusColumn.addView(appSettings)", s)
        self.assertIn("if (!canStartGame(feedback, appSettings))", s)
        self.assertIn("right.getMeasuredHeight()", d)
        self.assertIn("page.getMeasuredHeight()", d)
        self.assertIn("visibleChildren(page) == 1", d)
        self.assertIn("visibleChildren(content) == 1", d)
        self.assertIn("visibleChildren(statusContent) > 0", d)
        self.assertIn("PlayDockPolicy.overlay(", d)
        self.assertIn("scrollHeight = h - reserve", d)
        self.assertIn("scroll.measure(exact(w), exact(scrollHeight))", d)
        self.assertIn("footer.layout(w - edge - dockWidth", d)
        self.assertIn("scroll.scrollTo(0, 0)", d)

if __name__ == "__main__":
    unittest.main()
