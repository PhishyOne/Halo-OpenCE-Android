"""No stock application alert/toast surfaces; retain system-owned Android flows."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class ThemePrivacyTests(unittest.TestCase):
    def read(self, name):
        return (J / (name + ".java")).read_text()

    def test_all_app_alert_and_toast_sites_use_shared_theme(self):
        for path in J.rglob("*.java"):
            src = path.read_text()
            with self.subTest(path=path.name):
                self.assertNotRegex(src, r"new\s+(?:android\.app\.)?(?:AlertDialog|ProgressDialog|PopupWindow|PopupMenu)\b")
                self.assertNotRegex(src, r"\bToast\.makeText\(")
                self.assertNotIn("import android.app.AlertDialog;", src)
                self.assertNotIn(".setError(", src)
        dialog = self.read("HaloDialog")
        self.assertIn("extends HaloWindow", dialog)
        self.assertIn("HaloTheme.surface(", dialog)
        self.assertIn("HaloTheme.menuButtonColors(b)", dialog)
        self.assertIn("HaloTheme.CHECK_TINT", dialog)
        self.assertIn("body.addView(footer", dialog)
        self.assertIn("spec.view instanceof ScrollView", dialog)
        self.assertIn("spec.multi.onClick(this, index, checked)", dialog)
        self.assertIn("if (!single) dismiss()", dialog)
        self.assertIn("action.listener.onClick(this, which)", dialog)
        self.assertIn("finally { dismiss(); }", dialog)
        self.assertIn("getButton(int which)", dialog)
        self.assertIn("setOnCancelListener(spec.cancelListener)", dialog)

    def test_theme_applies_to_custom_content_and_hud_notices(self):
        dialog = self.read("HaloDialog")
        for widget in ("Switch", "CompoundButton", "Button", "EditText", "ProgressBar"):
            self.assertIn("view instanceof " + widget, dialog)
        notice = self.read("HaloNotice")
        self.assertIn("HaloWindow.activeWindow(a)", notice)
        self.assertIn("ACCESSIBILITY_LIVE_REGION_POLITE", notice)
        self.assertIn("frame.removeView(text)", notice)
        self.assertIn("WeakReference", self.read("HaloWindow"))
        for name in ("HaloReelGallery", "LanReelPlayer", "MatchHistoryView"):
            self.assertIn("new HaloWindow(activity)", self.read(name))

    def test_video_preference_independent_and_server_rechecks(self):
        state = self.read("LanShareState")
        settings = self.read("LanShareSettings")
        ui = self.read("LanBoardUi")
        self.assertIn("return new LanShareState(enabled, videoAllowed)", state)
        self.assertIn("return new LanShareState(career, enabled)", state)
        self.assertIn("state.encode()", settings)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", settings)
        self.assertIn("LanShareSettings.setVideos(activity, next)", ui)
        self.assertIn("addVideoOptOut();", ui)
        self.assertLess(ui.index("panel.addView(actions);"), ui.index("addVideoOptOut();"))
        self.assertIn("LanShareSettings.reels(context)", self.read("LanBoardServer"))

    def test_system_owned_permission_and_install_flows_unchanged(self):
        launcher = self.read("LauncherActivity")
        self.assertIn("createScreenCaptureIntent(", launcher)
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", launcher)
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT", launcher)
        self.assertIn("startActivityForResult", launcher)
        self.assertIn("Updater.install(activity)", self.read("LanUpdateClient"))

if __name__ == "__main__":
    unittest.main()
