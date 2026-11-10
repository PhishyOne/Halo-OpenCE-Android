"""Co-op ghost spectator: bounded host-object recovery policy and source gates."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / "port/linux/game/coop_unit_resync_policy.h"
OBJECTS = ROOT / "port/linux/game/network_objects.c"
STATES = ROOT / "port/linux/game/network_distributed.c"

POLICY_TEST = r"""
#include <stdio.h>
#include <string.h>
#include "coop_unit_resync_policy.h"
#define CHECK(x) do {if(!(x)){fprintf(stderr,"failed line %d: %s\n",__LINE__,#x);return 1;}}while(0)
int main(void) {
    struct coop_unit_resync_watch w = {0};
    const int U = 0x12345, NEW = 0x23456;
    /* Initial normal replication race: no early full resend. */
    CHECK(!coop_unit_resync_should_request(&w, U, 10, 1, 60, 360));
    CHECK(!coop_unit_resync_should_request(&w, U, 69, 1, 60, 360));
    CHECK(coop_unit_resync_should_request(&w, U, 70, 1, 60, 360));
    /* No storm of reliable snapshots even at 30 frames/s. */
    for (int i = 71; i < 430; i++)
        CHECK(!coop_unit_resync_should_request(&w, U, i, 1, 60, 360));
    CHECK(coop_unit_resync_should_request(&w, U, 430, 1, 60, 360));
    /* Sync completion does not mean the object necessarily appeared:
       unchanged missing state is allowed to retry only after cooldown. */
    CHECK(!coop_unit_resync_should_request(&w, U, 431, 1, 60, 360));
    /* Once a valid object arrives, clear the pending watch. */
    CHECK(!coop_unit_resync_should_request(&w, U, 432, 0, 60, 360));
    CHECK(!coop_unit_resync_should_request(&w, U, 433, 1, 60, 360));
    CHECK(!coop_unit_resync_should_request(&w, U, 492, 1, 60, 360));
    CHECK(coop_unit_resync_should_request(&w, U, 493, 1, 60, 360));
    /* A new life starts a new timer, not a request for the old life. */
    CHECK(!coop_unit_resync_should_request(&w, NEW, 494, 1, 60, 360));
    CHECK(!coop_unit_resync_should_request(&w, NEW, 553, 1, 60, 360));
    CHECK(coop_unit_resync_should_request(&w, NEW, 554, 1, 60, 360));
    /* Host state becomes dead; no spectator phantom resync. */
    CHECK(!coop_unit_resync_should_request(&w, NEW, 555, 0, 60, 360));
    CHECK(!coop_unit_resync_should_request(&w, NEW, 556, 1, 60, 360));
    /* Clock reset on level change, policy rebases rather than looping. */
    CHECK(!coop_unit_resync_should_request(&w, NEW, 20, 1, 60, 360));
    CHECK(coop_unit_resync_should_request(&w, NEW, 80, 1, 60, 360));
    puts("co-op unit recovery: debounce, cooldown, reset, new-life and clock-reset PASS");
    return 0;
}
"""


class CoopUnitResyncTest(unittest.TestCase):
    def test_policy(self):
        compiler = os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertTrue(compiler, "C compiler required")
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "test.c"
            binary = Path(temp) / "test"
            source.write_text(POLICY_TEST)
            subprocess.run([compiler, "-I", str(HEADER.parent), str(source), "-o", str(binary)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            self.assertIn("PASS", result.stdout)

    def test_source_gates(self):
        states = STATES.read_text()
        objects = OBJECTS.read_text()
        self.assertIn("if (local && network_coop_active())", states)
        self.assertIn("network_objects_client_watch_coop_unit(state->unit_index, available)", states)
        self.assertIn("network_objects_client_watch_coop_unit(NONE, FALSE)", states)
        self.assertIn("coop_unit_resync_should_request(&objects_client_coop_unit_watch,", objects)
        self.assertIn("2 * TICKS_PER_SECOND, 12 * TICKS_PER_SECOND", objects)
        self.assertIn("if (!objects_client_synchronized)", objects)
        self.assertIn("objects_client_ask_again = TRUE;", objects)
        self.assertIn("objects_client_synchronized = FALSE;", objects)
        self.assertIn("objects_client_ready_time = NONE;", objects)
        self.assertIn("csmemset(&objects_client_coop_unit_watch", objects)
        # No recovery path may spawn or kill a co-op player.
        recovery = objects.split("void network_objects_client_watch_coop_unit(", 1)[1].split(
            "word network_objects_entry_size(", 1)[0]
        for forbidden in ("unit_kill(", "unit_kill_no_statistics(", "player_teleport(",
                          "network_player_attach_unit(", "object_new("):
            self.assertNotIn(forbidden, recovery)


if __name__ == "__main__":
    unittest.main()
