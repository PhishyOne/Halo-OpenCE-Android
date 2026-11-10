"""Isolated large-local-bot corpse policy and guarded engine integration tests."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "port/linux/game/bot_garbage_policy.h"
OBJECTS = ROOT / "source/objects/objects.c"
BOTS = ROOT / "port/linux/game/bots.c"
DIAGNOSTICS = ROOT / "port/android/app/src/main/java/com/halo/decomp/HaloDiagnostics.java"

TEST = r"""
#include <stdio.h>
#include "bot_garbage_policy.h"
#define CHECK(x) do { \
    if (!(x)) {fprintf(stderr,"Failed line %d: %s\n",__LINE__,#x);return 1;} \
} while(0)
int main(void) {
    CHECK(!halo_stress_bot_garbage_mode(0, 1, 127)); /* co-op or no game engine */
    CHECK(!halo_stress_bot_garbage_mode(1, 0, 127)); /* live LAN or Internet */
    CHECK(!halo_stress_bot_garbage_mode(1, 1, 47)); /* normal lobby */
    CHECK( halo_stress_bot_garbage_mode(1, 1, 48));
    CHECK( halo_stress_bot_garbage_mode(1, 1, 128));

    CHECK(halo_active_garbage_scale_players(0, 0) == 16);
    CHECK(halo_active_garbage_scale_players(1, 0) == 16);
    CHECK(halo_active_garbage_scale_players(16, 0) == 16);
    CHECK(halo_active_garbage_scale_players(32, 0) == 32);
    CHECK(halo_active_garbage_scale_players(127, 0) == 127);
    CHECK(halo_active_garbage_scale_players(128, 0) == 128);
    CHECK(halo_active_garbage_scale_players(15, 1) == 16);
    CHECK(halo_active_garbage_scale_players(32, 1) == 32);
    CHECK(halo_active_garbage_scale_players(48, 1) == 32);
    CHECK(halo_active_garbage_scale_players(127, 1) == 32);
    CHECK(halo_active_garbage_scale_players(128, 1) == 32);

    CHECK(!halo_bot_is_nonviewing_garbage_player(1, 0, 1)); /* real host */
    CHECK( halo_bot_is_nonviewing_garbage_player(1, 1, 1)); /* bot #1 */
    CHECK( halo_bot_is_nonviewing_garbage_player(1, 127, 1)); /* bot #127 */
    CHECK(!halo_bot_is_nonviewing_garbage_player(0, 1, 1)); /* not local stress */
    CHECK(!halo_bot_is_nonviewing_garbage_player(0, 127, 1)); /* keep LAN behavior */
    puts("large-bot garbage policy checks PASS");
    return 0;
}
"""

class LargeBotGarbagePolicyTest(unittest.TestCase):
    def test_pure_thresholds_and_mode_isolation(self):
        compiler = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertTrue(compiler, "C compiler required")
        with tempfile.TemporaryDirectory() as work:
            src = Path(work) / "test.c"
            binpath = Path(work) / "test"
            src.write_text(TEST)
            completed = subprocess.run(
                [compiler, "-std=gnu89", "-Wall", "-Wextra", "-Werror",
                 "-I", str(HEADER.parent), str(src), "-o", str(binpath)],
                capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            run = subprocess.run([str(binpath)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("PASS", run.stdout)

    def test_garbage_collector_is_protected_by_stress_gate(self):
        objects = OBJECTS.read_text()
        self.assertIn("game_engine_running()", objects)
        self.assertIn("network_game_is_splitscreen_local()", objects)
        self.assertIn("halo_stress_bot_garbage_mode(", objects)
        self.assertIn("halo_active_garbage_scale_players(player_count, objects_large_local_bot_game())", objects)
        self.assertIn("player->local_player_index == NONE", objects)
        self.assertIn("BOTS_FIRST_MACHINE", objects)
        self.assertIn("halo_bot_is_nonviewing_garbage_player(ignore_bot_views,", objects)
        self.assertIn("if (object_visible_to_any_player(object_index))", objects)
        self.assertIn("return network_coop_active() || objects_large_local_bot_game();", objects)
        self.assertIn("if (!objects_garbage_retry_mode() || collected_count > 0 ||", objects)
        self.assertIn("garbage_active_retry_time = should_collect ? NONE", objects)
        # Only the source engine's existing GC may delete objects.
        new_mode = objects.split("static boolean objects_large_local_bot_game(", 1)[1].split(
            "boolean object_visible_to_any_player(", 1)[0]
        for risky in ("object_delete_immediately(", "object_set_garbage(",
                      "unit_kill(", "player_died("):
            self.assertNotIn(risky, new_mode)

    def test_bot_object_counters_are_not_per_tick(self):
        bots = BOTS.read_text()
        diag = DIAGNOSTICS.read_text()
        report = bots.split("static void bots_report(", 1)[1].split(
            "void bots_update_actions(", 1)[0]
        self.assertIn("TICKS_PER_SECOND * 60", report)
        self.assertIn("objects_information_get(&information)", report)
        self.assertIn("_object_mask_biped", report)
        self.assertIn("bots: perf objects", report)
        self.assertIn("error(_error_silent, \"bots: perf", report)
        self.assertIn("target_scans %ld evaluated %ld los %ld", report)
        self.assertIn("bots: perf", diag)
        self.assertIn('"debug.old.txt"', diag)
        scan = bots.split("static long bots_find_target(", 1)[1].split(
            "/* ---------- moving */", 1)[0]
        self.assertIn("bots_globals.target_scans++", scan)
        self.assertIn("bots_globals.target_scores++", scan)
        self.assertIn("bots_target_line_of_sight(", scan)
        self.assertNotIn("qsort(", scan)
        # The instrumentation wrapper must preserve the collision result.
        wrapper = bots.split("static boolean bots_target_line_of_sight(",1)[1].split(
            "static long bots_find_target(",1)[0]
        self.assertIn("return bots_line_of_sight(", wrapper)

if __name__ == "__main__":
    unittest.main()
