"""Regression gates for the v54 on-device black-player report."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class VideoPlaybackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = (JAVA / "HaloReelGallery.java").read_text()

    def test_surface_is_not_covered_by_opaque_view(self):
        self.assertIn("viewport.setBackgroundColor(Color.BLACK)", self.src)
        self.assertIn("player.setBackground(null)", self.src)
        self.assertNotIn("player.setBackgroundColor(", self.src)
        self.assertNotIn("setZOrderOnTop(true)", self.src)
        self.assertIn("new ColorDrawable(HaloTheme.BACKGROUND_BOTTOM)", self.src)
        self.assertNotIn("new ColorDrawable(Color.TRANSPARENT)", self.src)
        self.assertIn("FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER", self.src)

    def test_prepare_and_error_callbacks_before_open(self):
        for callback in ("setOnPreparedListener", "setOnInfoListener",
                         "setOnErrorListener", "setOnCompletionListener"):
            self.assertLess(self.src.index("player." + callback),
                            self.src.index("player.setVideoURI("))
        self.assertLess(self.src.index("resize(activity, w, false)"),
                        self.src.index("player.setVideoURI("))
        self.assertIn("MEDIA_INFO_VIDEO_RENDERING_START", self.src)
        self.assertIn("No first-frame signal yet", self.src)
        self.assertIn("Playback error (", self.src)

    def test_transport_controls_seek_pause_and_replay(self):
        self.assertIn("seek.setOnSeekBarChangeListener", self.src)
        self.assertIn("requestSeek(bar.getProgress())", self.src)
        self.assertIn("mp.setOnSeekCompleteListener", self.src)
        self.assertIn("if (!ended && !dragging && !seeking)", self.src)
        self.assertIn("if (ended) requestSeek(0)", self.src)
        self.assertIn("player.pause()", self.src)
        self.assertIn("toggle.setText(\"REPLAY\")", self.src)
        self.assertIn("clock(ms) + \" / \" + clock(duration)", self.src)

    def test_fullscreen_reuses_the_same_dialog_and_surface(self):
        self.assertEqual(self.src.count("new VideoView("), 1)
        self.assertEqual(self.src.count("player.setVideoURI("), 1)
        self.assertIn("resize(activity, w, full)", self.src)
        self.assertIn("WindowManager.LayoutParams.MATCH_PARENT", self.src)
        self.assertIn("EXIT FULL", self.src)
        self.assertIn("View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY", self.src)

    def test_surface_recreation_and_dismissal(self):
        self.assertIn("implements SurfaceHolder.Callback", self.src)
        self.assertIn("player.getHolder().addCallback(this)", self.src)
        self.assertIn("if (position > 0 && !ended) requestSeek(position)", self.src)
        self.assertIn("dialog.setOnDismissListener(unused -> playback.close())", self.src)
        self.assertIn("handler.removeCallbacks(ticker)", self.src)
        self.assertIn("player.getHolder().removeCallback(this)", self.src)
        self.assertIn("player.stopPlayback()", self.src)

    def test_export_and_inspection_remain_explicit_and_local(self):
        self.assertIn("playback.pauseForExport()", self.src)
        self.assertIn("activity.exportReelVideo(clip)", self.src)
        self.assertIn("VideoCaptureDiagnostic.inspect(clip)", self.src)
        for banned in ("HttpURLConnection", "FileOutputStream", "clip.delete()",
                       "MediaRecorder", "MediaProjection"):
            self.assertNotIn(banned, self.src)


if __name__ == "__main__":
    unittest.main()
