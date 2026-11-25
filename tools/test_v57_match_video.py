"""Source contracts for consent-scoped match capture and conservative cleanup."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class MatchVideoTests(unittest.TestCase):
    def setUp(self):
        self.service = (JAVA / "HaloVideoCaptureService.java").read_text()
        self.activity = (JAVA / "HaloActivity.java").read_text()
        self.launcher = (JAVA / "LauncherActivity.java").read_text()
        self.library = (JAVA / "ReelLibrary.java").read_text()
        self.muxer = (JAVA / "ReelVideoMuxer.java").read_text()
        self.engine = (ROOT / "source/game/game_engine.c").read_text()
        self.native = (ROOT / "port/linux/game/career_record.c").read_text()

    def test_native_competitive_match_only(self):
        self.assertIn("career_video_state(game_engine != NULL && !network_coop_active()", self.engine)
        self.assertIn("game_engine_globals.postgame_state == game_engine_mode_active", self.engine)
        self.assertIn("career_video_state(0, 0)", self.engine)
        self.assertIn("CLOCK_MONOTONIC", self.native)
        self.assertIn("video_foreground_token(root, token)", self.native)
        self.assertIn("career_match_id(career_id, sizeof(career_id))", self.native)

    def test_one_consent_and_no_campaign_encoder(self):
        self.assertEqual(self.service.count("projection.createVirtualDisplay("), 1)
        self.assertIn("null, null, main", self.service)
        self.assertIn("virtualDisplay.setSurface(recorderSurface)", self.service)
        self.assertIn("virtualDisplay.setSurface(null)", self.service)
        self.assertIn("foreground && fresh && state.active", self.service)
        self.assertIn("!state.id.equals(attemptedMatch)", self.service)
        self.assertIn("publishVideoVisibility(false)", self.activity)
        self.assertIn("game.putExtra(MatchVideoState.TOKEN, videoToken)", self.launcher)
        self.assertIn('getBoolean("keep_full_match", false)', self.service)

    def test_limits_and_no_background_restart(self):
        self.assertIn("MAX_RAW_BYTES = 512L * 1024 * 1024", self.service)
        self.assertIn("finishCapture(false)", self.service)
        self.assertIn("if (projection == current) stopSession()", self.service)
        self.assertIn("MatchVideoState.foreground(root, token)", self.service)

    def test_cleanup_requires_fresh_verified_reel(self):
        self.assertNotIn("trimSources(", self.library)
        self.assertNotIn("trimFinished(", self.library)
        self.assertIn("timeline.id.equals(candidate.id)", self.library)
        self.assertIn("reel.exists()) continue", self.library)
        self.assertIn("ReelVideoMuxer.build(raw, reel, plan.windows, true)", self.library)
        self.assertIn("(!strict || allWindows)", self.muxer)
        delete = self.library.index("Files.delete(raw.toPath())")
        for condition in ("VideoCaptureDiagnostic.inspect(reel)", "check.decoded - check.black < 3",
                          "plan.mayDiscard(timeline) && timeline.stillSame(raw)",
                          "synced.getFD().sync()"):
            self.assertLess(self.library.index(condition), delete)
        self.assertIn("HaloVideoFiles.suppressed(root, reel.getName())", self.library)

if __name__ == "__main__":
    unittest.main()
