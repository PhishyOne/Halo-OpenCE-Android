"""Compile and execute production postgame writer against fake match state."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "port/linux/game/career_record.c"
FIXTURE = ROOT / "tools/harness/tests/career_record.c"


class NativeCareerTest(unittest.TestCase):
    def test_production_writer(self):
        cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertIsNotNone(cc)
        source = SOURCE.read_text()
        self.assertIn("enum { CAREER_MAX_BYTES", source)
        self.assertIn("#endif /* HALO_ANDROID */", source)
        source = source[source.index("enum { CAREER_MAX_BYTES") :
                        source.index("#endif /* HALO_ANDROID */")]
        with tempfile.TemporaryDirectory(prefix="halo-career-native-") as temp:
            work = Path(temp)
            (work / "production.inc").write_text(source)
            built = subprocess.run([cc, "-std=gnu89", "-Wall", "-Wextra",
                "-Werror", "-I", str(work), str(FIXTURE), "-o", str(work / "test")],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(work / "test"), str(work)],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("PASS career recorder", ran.stdout)
            lines = (work / "career-matches-v1.log").read_text().splitlines()
            self.assertEqual(len(lines), 2)
            a, b = (record.split("|") for record in lines)
            self.assertEqual(len(a), 18)
            self.assertEqual(len(b), 18)
            self.assertEqual(a[0], "HC1")
            self.assertEqual(len(a[1]), 32)
            self.assertNotEqual(a[1], b[1])
            self.assertEqual(a[3:10], ["2", "bloodgulch", "3", "2", "1", "2", "1"])
            self.assertEqual(b[3:10], ["1", "sidewinder", "2", "-1", "-1", "2", "1"])
            self.assertEqual(a[12], "3")
            self.assertEqual(b[12], "1")


if __name__ == "__main__":
    unittest.main()
