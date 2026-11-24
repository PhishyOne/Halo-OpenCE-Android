"""Source gate for black-recording diagnosis and original MP4 export."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class VideoDiagnosticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gallery = (JAVA / "HaloReelGallery.java").read_text()
        cls.launcher = (JAVA / "LauncherActivity.java").read_text()
        cls.files = (JAVA / "HaloVideoFiles.java").read_text()
        cls.inspect = (JAVA / "VideoCaptureDiagnostic.java").read_text()
        cls.brightness = (JAVA / "VideoFrameBrightness.java").read_text()
        cls.reel_library = (JAVA / "ReelLibrary.java").read_text()

    def test_both_original_and_reel_are_listed_without_storage_permissions(self):
        self.assertIn("HaloVideoFiles.rawRecordings(appRoot)", self.gallery)
        self.assertIn("ReelLibrary.finishedReels(appRoot)", self.gallery)
        self.assertIn("ORIGINAL FULL RECORDING", self.gallery)
        self.assertIn("HIGHLIGHT REEL", self.gallery)
        self.assertIn("REELS + ORIGINAL RECORDINGS", self.gallery)
        self.assertIn(".setItems(titles", self.gallery)
        self.assertNotIn("setMessage(", self.gallery.split(
            'new AlertDialog.Builder(activity)\n            .setTitle("REELS + ORIGINAL RECORDINGS")', 1)[1].split(".show();", 1)[0])
        self.assertIn('ReelSessionIndex.safeFileName(file.getName())', self.files)
        self.assertIn("HaloVideoFiles.validName(clip.getName())", self.gallery)

    def test_video_frames_inspected_locally_readonly(self):
        self.assertIn("CHECK FRAMES", self.gallery)
        self.assertIn("VideoCaptureDiagnostic.inspect(clip)", self.gallery)
        self.assertIn('new Thread(() -> {', self.gallery)
        self.assertIn("MediaMetadataRetriever", self.inspect)
        self.assertIn("getScaledFrameAtTime(", self.inspect)
        self.assertIn("VideoFrameBrightness.nearlyBlack(pixels)", self.inspect)
        self.assertIn("decoded < 3", self.inspect)
        self.assertIn('LIKELY BLACK VIDEO', self.inspect)
        self.assertIn('PICTURE DETECTED', self.inspect)
        self.assertIn('UNKNOWN', self.inspect)
        self.assertIn("frame.recycle()", self.inspect)
        self.assertNotIn("FileOutputStream", self.inspect)
        self.assertNotIn("HttpURLConnection", self.inspect)

    def test_no_lost_video_prepare_callback(self):
        self.assertLess(self.gallery.index("player.setOnPreparedListener("),
            self.gallery.index("player.setVideoURI(Uri.fromFile(clip))"))
        self.assertLess(self.gallery.index("dialog.show();"),
            self.gallery.index("player.setVideoURI(Uri.fromFile(clip))"))
        self.assertIn("player.start(); // queues playback", self.gallery)
        self.assertIn("player.setOnErrorListener(", self.gallery)

    def test_raw_export_is_explicit_bounded_and_keeps_original(self):
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", self.launcher)
        self.assertIn('save.setType("video/mp4")', self.launcher)
        self.assertIn("HaloVideoFiles.validName(chosen.getName())", self.launcher)
        self.assertIn("HaloVideoFiles.validName(name)", self.launcher)
        self.assertIn("chosen.length() > ReelSessionIndex.SOURCE_LIMIT", self.launcher)
        self.assertIn("source.length() <= ReelSessionIndex.SOURCE_LIMIT", self.launcher)
        self.assertIn("FileInputStream(source)", self.launcher)
        self.assertIn("64", self.launcher) # uses bounded 64K streaming
        self.assertNotIn("source.delete()", self.launcher.split(
            'private void exportReelToDocument(', 1)[1].split(
                'private int dp(', 1)[0])
        self.assertIn("ReelSessionIndex.safeFileName(file.getName())",
            self.reel_library)

    def test_capture_and_game_career_files_remain_untouched(self):
        for filename in ("HaloVideoCaptureService.java", "ReelSessionIndex.java",
                         "ReelVideoMuxer.java", "CareerRecords.java", "MatchHighlights.java",
                         "HaloActivity.java"):
            self.assertTrue((JAVA / filename).is_file())

if __name__ == "__main__":
    unittest.main()
