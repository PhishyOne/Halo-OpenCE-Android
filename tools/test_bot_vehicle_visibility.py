"""Compile the actual bot sight query and verify the shooter exclusion.

A seated Spartan must not occlude itself with its parent vehicle. Walls,
unrelated vehicles, and on-foot sight must keep their existing semantics.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

try:
    from tools.harness import function
except ModuleNotFoundError:
    from harness import function

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "port/linux/game/bots.c"
FIXTURE = ROOT / "tools/harness/tests/bot_vehicle_visibility.c"


class VehicleVisibilityTest(unittest.TestCase):
    def run_source(self, production):
        cc = os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory(prefix="halo-vehicle-sight-") as temporary:
            folder = Path(temporary)
            (folder / "production_vehicle_los.inc").write_text(production)
            executable = folder / "test"
            compiled = subprocess.run([
                cc, "-std=gnu89", "-Wall", "-Wextra", "-Werror",
                "-Wno-unused-function", "-I", str(folder),
                str(FIXTURE), "-o", str(executable),
            ], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            return subprocess.run([str(executable)], capture_output=True,
                                  text=True, timeout=10)

    def test_actual_sight_function_preserves_cover_and_infantry(self):
        production = function(SOURCE.read_text(), "bots_line_of_sight")
        result = self.run_source(production)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS vehicle sight", result.stdout)

    def test_regression_reproduces_when_parent_exclusion_is_removed(self):
        production = function(SOURCE.read_text(), "bots_line_of_sight")
        before = "ignore_object_index = shooter->object.parent_object_index;"
        self.assertEqual(production.count(before), 1)
        result = self.run_source(production.replace(
            before, "ignore_object_index = unit_index;"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("line", result.stderr)

    def test_nonvehicle_parent_is_not_blindly_excluded(self):
        production = function(SOURCE.read_text(), "bots_line_of_sight")
        before = """object_try_and_get_and_verify_type(shooter->object.parent_object_index,
			_object_mask_vehicle)"""
        self.assertEqual(production.count(before), 1)
        result = self.run_source(production.replace(before, "TRUE"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("line", result.stderr)


if __name__ == "__main__":
    unittest.main()
