"""Source contracts for opt-in, privacy-safe, bounded Android highlight videos."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class V51VideoTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.launcher = (JAVA / "LauncherActivity.java").read_text()
        cls.service = (JAVA / "HaloVideoCaptureService.java").read_text()
        cls.index = (JAVA / "ReelSessionIndex.java").read_text()
        cls.library = (JAVA / "ReelLibrary.java").read_text()
        cls.muxer = (JAVA / "ReelVideoMuxer.java").read_text()
        cls.gallery = (JAVA / "HaloReelGallery.java").read_text()
        cls.profile = (JAVA / "CareerProfile.java").read_text()
        cls.game = (JAVA / "HaloActivity.java").read_text()

    def test_media_projection_manifest_and_consent_gate(self):
        ns = "{http://schemas.android.com/apk/res/android}"
        root = ET.parse(ROOT /
            "port/android/app/src/main/AndroidManifest.xml").getroot()
        permissions = [p.attrib.get(ns+"name") for p in
                       root.findall("uses-permission")]
        self.assertIn("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
                      permissions)
        components = [s for s in root.iter("service") if
            s.attrib.get(ns+"name") == ".HaloVideoCaptureService"]
        self.assertEqual(len(components), 1)
        self.assertEqual(components[0].attrib.get(ns+"exported"), "false")
        self.assertEqual(components[0].attrib.get(ns+"foregroundServiceType"),
                         "mediaProjection")
        self.assertIn('getBoolean("record_opt_in", false)', self.launcher)
        self.assertIn('startActivityForResult(request, PICK_REEL_CONSENT)', self.launcher)
        self.assertIn("createConfigForDefaultDisplay()", self.launcher)
        self.assertIn("if (resultCode == RESULT_OK && data != null)", self.launcher)
        self.assertIn("launchWithoutConsent(game)", self.launcher)
        self.assertIn('resultCode != android.app.Activity.RESULT_OK', self.service)

    def test_real_video_not_replaced_by_fake_replay(self):
        self.assertIn("MediaProjectionManager", self.service)
        self.assertIn("MediaRecorder.VideoSource.SURFACE", self.service)
        self.assertIn("MediaRecorder.VideoEncoder.H264", self.service)
        self.assertIn("recorder.getSurface()", self.service)
        self.assertIn("projection.createVirtualDisplay(", self.service)
        self.assertIn("projection.registerCallback(", self.service)
        self.assertIn("recorder.start()", self.service)
        self.assertIn("MediaExtractor", self.muxer)
        self.assertIn("MediaMuxer", self.muxer)
        self.assertIn("MediaExtractor.SEEK_TO_PREVIOUS_SYNC", self.muxer)
        self.assertIn("muxer.writeSampleData(", self.muxer)
        self.assertIn("MediaCodec.BUFFER_FLAG_KEY_FRAME", self.muxer)
        self.assertIn('".pending"', self.muxer)

    def test_bounded_private_storage_and_failed_capture_fallback(self):
        self.assertIn('BIT_RATE = 1_000_000', self.service)
        self.assertIn('FRAME_RATE = 20', self.service)
        self.assertIn('WIDTH = 640', self.service)
        self.assertIn('MAX_MINUTES = 20', self.service)
        self.assertIn('SOURCE_LIMIT = 160 * 1024 * 1024', self.index)
        self.assertIn('MAX_RAW_BYTES = 512L * 1024 * 1024', self.service)
        self.assertIn("MIN_FREE = 500L * 1024 * 1024", self.service)
        self.assertIn("reels.getUsableSpace() < MIN_FREE", self.service)
        self.assertIn("ACTION_STOP", self.service)
        self.assertIn("Game", self.service)
        self.assertIn('output.length() <= ReelSessionIndex.SOURCE_LIMIT', self.service)
        self.assertIn("MAX_RECORDING_MS", self.index)
        self.assertIn("finished.finish(output, duration, trustworthy).write(output)", self.service)
        self.assertIn("ReelLibrary.assemble(", self.service)
        self.assertIn("Log.e(TAG", self.service)
        self.assertNotIn('new Socket(', self.service)
        self.assertNotIn('HttpURLConnection', self.service)
        self.assertNotIn('openConnection(', self.service)

    def test_local_reel_gallery_and_saf_export(self):
        self.assertIn("VideoView", self.gallery)
        self.assertIn("setVideoURI(Uri.fromFile(clip))", self.gallery)
        self.assertIn("SAVE A COPY", self.gallery)
        self.assertIn("activity.exportReelVideo(clip)", self.gallery)
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", self.launcher)
        self.assertIn('save.setType("video/mp4")', self.launcher)
        self.assertIn("FileInputStream(source)", self.launcher)
        self.assertIn("HaloReelGallery.show(", self.profile)
        self.assertIn("Older event bookmarks", self.profile)

    def test_existing_career_and_network_untouched(self):
        self.assertTrue((JAVA / "CareerRecords.java").is_file())
        self.assertTrue((JAVA / "MatchHighlights.java").is_file())
        self.assertTrue((JAVA / "NetworkSessionService.java").is_file())
        self.assertIn("stopService(new Intent(this, HaloVideoCaptureService.class));",
                      self.game)
        self.assertIn("getSharedPreferences", self.launcher)
        for path in ("source/game/game_engine.c", "source/game/game_statistics.c",
                     "port/linux/game/career_record.c"):
            self.assertTrue((ROOT / path).is_file())


if __name__ == "__main__":
    unittest.main()
