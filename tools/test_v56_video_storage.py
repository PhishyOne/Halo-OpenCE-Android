"""Source gates for explicit, scoped, persistent video deletion."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class VideoStorageTest(unittest.TestCase):
    def setUp(self):
        self.gallery = (JAVA / "HaloReelGallery.java").read_text()
        self.files = (JAVA / "HaloVideoFiles.java").read_text()
        self.library = (JAVA / "ReelLibrary.java").read_text()

    def test_visible_management_even_without_playable_video(self):
        self.assertEqual(self.gallery.count('setNeutralButton("MANAGE VIDEOS"'), 2)
        self.assertIn("setMultiChoiceItems(labels, checked", self.gallery)
        self.assertIn('"DELETE SELECTED"', self.gallery)
        self.assertIn('"DELETE ALL"', self.gallery)

    def test_confirmation_precedes_mutation_and_refresh(self):
        start = self.gallery.index("private static void confirmDelete")
        self.assertLess(self.gallery.index('setPositiveButton("DELETE"', start),
                        self.gallery.index("ReelLibrary.deleteVideos(", start))
        self.assertIn("result.removed", self.gallery)
        self.assertIn("result.skipped", self.gallery)
        self.assertIn("onDone.run()", self.gallery[start:])
        self.assertIn("manage(activity, appRoot, () -> show(activity, appRoot))", self.gallery)

    def test_completed_only_and_snapshot(self):
        self.assertIn("LinkOption.NOFOLLOW_LINKS", self.files)
        self.assertIn("target.length() != entry.bytes", self.files)
        self.assertIn("target.lastModified() != entry.modified", self.files)
        self.assertIn("validName(entry.name)", self.files)
        self.assertIn("Files.delete(target.toPath())", self.files)

    def test_serialized_and_persistent(self):
        self.assertIn("static synchronized HaloVideoFiles.DeleteResult deleteVideos", self.library)
        self.assertIn("static synchronized int assemble", self.library)
        self.assertLess(self.library.index("HaloVideoFiles.suppressed(appRoot, out.getName())"),
                        self.library.index("ReelVideoMuxer.build(source, out, windows)"))
        self.assertIn('entry.name + ".deleted"', self.files)


if __name__ == "__main__":
    unittest.main()
