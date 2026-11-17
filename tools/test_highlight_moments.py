"""Compile and test the actual Android event-only highlight marker writer."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
C = ROOT / "port/linux/game/highlight_moments.c"
HEADER = ROOT / "port/linux/game"
HARNESS = ROOT / "tools/harness/tests/highlight_moments.c"


class HighlightMomentsTest(unittest.TestCase):
    def test_writer_and_highlight_selection(self):
        cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory(prefix="halo-highlights-") as folder:
            build = Path(folder)
            result = subprocess.run(
                [cc, "-DHALO_ANDROID", "-std=gnu89", "-Wall", "-Wextra",
                 "-Werror", "-I", str(HEADER), str(HARNESS), str(C),
                 "-o", str(build / "highlight-test")],
                text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(build / "highlight-test"), folder],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS highlight moments", result.stdout)
            rows = (build / "career-highlights-v1.log").read_text().splitlines()
            self.assertEqual(len(rows), 3)
            for row in rows:
                fields = row.split("|")
                self.assertEqual(len(fields), 8)
                self.assertEqual(fields[0], "HLM1")
                self.assertEqual(len(fields[1]), 32)
                self.assertRegex(fields[2], r"^[a-z0-9_-]+$")
                self.assertLessEqual(int(fields[6]), int(fields[3]))
                self.assertLessEqual(int(fields[3]), int(fields[7]))


if __name__ == "__main__":
    unittest.main()
