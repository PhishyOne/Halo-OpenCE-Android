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
            built = subprocess.run([cc, "-DHALO_ANDROID", "-std=gnu89",
                "-Wall", "-Wextra", "-Werror",
                "-I", str(work), "-I", str(ROOT / "port/linux/game"),
                str(FIXTURE), str(ROOT / "port/linux/game/highlight_moments.c"),
                "-o", str(work / "test")],
                capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(work / "test"), str(work)],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("PASS career recorder", ran.stdout)
            lines = (work / "career-matches-v1.log").read_text().splitlines()
            self.assertEqual(len(lines), 4)
            a, b, oddball, race = (record.split("|") for record in lines)
            for entry in (a, b, oddball, race):
                self.assertEqual(len(entry), 25)
                self.assertEqual(entry[0], "HC2")
                self.assertEqual(len(entry[1]), 32)
            self.assertEqual(len({a[1], b[1], oddball[1], race[1]}), 4)
            self.assertEqual(a[3:10],
                ["2", "bloodgulch", "3", "2", "1", "2", "1"])
            self.assertEqual(b[3:10],
                ["1", "sidewinder", "2", "-1", "-1", "2", "1"])
            self.assertEqual(a[12], "3")
            self.assertEqual(b[12], "1")
            self.assertEqual(b[13:15], ["2", "1"]) # CTF captures, returns
            self.assertEqual(b[18], "3") # flag grabs
            self.assertEqual(oddball[3:5], ["3", "icefields"])
            self.assertEqual(oddball[19:22], ["75", "2", "3"])
            self.assertEqual(race[3:5], ["5", "raceway"])
            self.assertEqual(race[22:25], ["2", "300", "420"])



if __name__ == "__main__":
    unittest.main()
