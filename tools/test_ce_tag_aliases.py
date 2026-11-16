"""Exercise the exact CE tag-root validator, including negative controls.

Shared full vehicle/weapon definitions occur in Beach Assault. The fix
must not relax Xbox roots, cross-type aliases, partial overlaps, or
arbitrary shared nested data. No copyrighted map assets are used here.
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
SOURCE = ROOT / "port/linux/game/tag_validate.c"
FIXTURE = ROOT / "tools/harness/tests/ce_tag_root_alias.c"


class CeTagRootAliasesTest(unittest.TestCase):
    def compile_and_run(self, source: str):
        cc = os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc, "A C compiler is required")
        with tempfile.TemporaryDirectory(prefix="halo-ce-root-alias-") as temporary:
            directory = Path(temporary)
            (directory / "production_alias.inc").write_text(source)
            executable = directory / "test"
            built = subprocess.run([
                cc, "-std=gnu89", "-Wall", "-Wextra", "-Werror",
                "-Wno-multichar", "-I", str(directory),
                str(FIXTURE), "-o", str(executable),
            ], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            return subprocess.run([str(executable)], capture_output=True,
                                  text=True, timeout=10)

    def test_actual_validator_accepts_only_safe_exact_root_aliases(self):
        production = function(SOURCE.read_text(), "validate_tag_table")
        result = self.compile_and_run(production)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS exact CE tag root aliases", result.stdout)

    def test_security_checks_are_behaviorally_required(self):
        production = function(SOURCE.read_text(), "validate_tag_table")
        faults = (
            ("owner->group_tag != instance->group_tag ||",
             "(owner->group_tag != instance->group_tag && FALSE) ||"),
            ("(instance->group_tag != 'vehi' && instance->group_tag != 'weap')",
             "FALSE"),
            ("if (!claim(instance->base_address, group->definition->size))",
             "if (claim(instance->base_address, group->definition->size) && FALSE)"),
            ("if (tag_validate_globals.custom_edition)",
             "if (tag_validate_globals.custom_edition || TRUE)"),
        )
        for before, after in faults:
            with self.subTest(guard=before):
                self.assertEqual(production.count(before), 1)
                result = self.compile_and_run(production.replace(before, after))
                self.assertNotEqual(result.returncode, 0,
                                    "Weakened validator passed the security fixture")
                self.assertIn("line", result.stderr)

    def test_inference_logs_only_formatted_text_and_resets_per_map(self):
        source = SOURCE.read_text()
        infer = function(source, "tag_validate_custom_edition_infer_groups")
        self.assertEqual(infer.count("tag_validate_report(message)"), 1)
        self.assertLess(infer.index("snprintf(message,"),
                        infer.index("tag_validate_report(message)"))
        self.assertIn("memset(&tag_validate_inference, 0,", infer)
        self.assertIn("tag_validate_inference.regrouped = pending_applied", infer)
        note = function(source, "tag_validate_custom_edition_note_structure_bsp")
        self.assertNotIn("tag_validate_report(message)", note)

    def test_standalone_tool_matches_game_group_inference_order(self):
        tool = (ROOT / "tools/map_validate.c").read_text()
        checked = function(tool, "custom_edition_check")
        self.assertLess(checked.index("custom_edition_cache_load("),
                        checked.index("tag_validate_custom_edition_infer_groups("))
        self.assertLess(checked.index("tag_validate_custom_edition_infer_groups("),
                        checked.index("custom_edition_cache_convert("))
        self.assertLess(checked.index("custom_edition_cache_convert("),
                        checked.index("tag_validate_custom_edition_tags("))
        # The game has paired allocation wrappers; no calloc/debug_free mix.
        production = function(SOURCE.read_text(), "validate_tag_table")
        self.assertNotIn("calloc(", production)
        self.assertIn("root_owners = malloc(", production)
        self.assertIn("free(root_owners)", production)
        self.assertIn("void *debug_malloc(", tool)
        self.assertIn("void debug_free(", tool)


if __name__ == "__main__":
    unittest.main()
