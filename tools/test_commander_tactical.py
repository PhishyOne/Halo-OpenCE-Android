"""v22 read-only Android host map: source-backed pure policy and wiring gates.

This does not constitute native Android, UI, or LAN acceptance.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "port/linux/game"
HEADER = GAME / "commander_tactical_policy.h"
SOURCE = GAME / "commander_tactical.c"
HUD = ROOT / "source/interface/hud.c"
COMMANDER = GAME / "commander_features.c"
HOST = ROOT / "port/android/host/host_touch.c"
IMPORTS = ROOT / "port/android/host_imports.list"
GUEST = ROOT / "port/android/guest/runtime/guest_host.h"
TOUCH = ROOT / "port/android/app/src/main/java/com/halo/decomp/TouchControls.java"
LAUNCHER = ROOT / "port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java"

CASES = r"""
#include <stdio.h>
#include <math.h>
#include "commander_tactical_policy.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "failed %d: %s\n", __LINE__, #x); return 1; } } while (0)
int main(void)
{
    unsigned int policy = COMMANDER_ENABLED | COMMANDER_SATELLITE;
    float u = -9.0f, v = -9.0f;
    /* Exercise all static helpers in the shared policy header for Clang Werror. */
    CHECK(commander_policy_effective(policy,1,1)==policy);
    CHECK(killstreak_policy_allows(KILLSTREAK_ENABLED,KILLSTREAK_ENABLED));
    int t,h,i,p;
    for (t=0;t<2;t++) for(h=0;h<2;h++)
      for(i=0;i<3;i++) for(p=0;p<2;p++)
        CHECK(commander_tactical_host_can_assign(policy,t,h,i,p) ==
              (t && h && i==0 && p));
    CHECK(!commander_tactical_host_can_assign(0,1,1,0,1));
    CHECK(commander_tactical_host_can_assign(COMMANDER_ENABLED,1,1,0,1));
    CHECK(!commander_tactical_host_can_assign(COMMANDER_SATELLITE,1,1,0,1));
    CHECK(commander_tactical_host_can_assign(COMMANDER_ENABLED|KILLSTREAK_ENABLED,1,1,0,1));
    CHECK(commander_tactical_ally(0,0));
    CHECK(commander_tactical_ally(1,1));
    CHECK(!commander_tactical_ally(0,1));
    CHECK(!commander_tactical_ally(1,0));
    CHECK(!commander_tactical_ally(2,2));
    CHECK(!commander_tactical_ally(-1,-1));
    CHECK(commander_tactical_project(0,0,-100,100,-50,50,&u,&v));
    CHECK(u==0.5f && v==0.5f);
    CHECK(commander_tactical_project(-100,50,-100,100,-50,50,&u,&v));
    CHECK(u==0 && v==0);
    CHECK(commander_tactical_project(100,-50,-100,100,-50,50,&u,&v));
    CHECK(u==1 && v==1);
    CHECK(!commander_tactical_project(101,0,-100,100,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,-51,-100,100,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,1,1,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,100,-100,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,-100,100,50,-50,&u,&v));
    CHECK(!commander_tactical_project(0,0,-100,100,-50,50,NULL,&v));
    CHECK(!commander_tactical_project(0,0,-100,100,-50,50,&u,NULL));
    CHECK(!commander_tactical_project(NAN,0,-100,100,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,INFINITY,-100,100,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,-INFINITY,100,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,-100,INFINITY,-50,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,-100,100,NAN,50,&u,&v));
    CHECK(!commander_tactical_project(0,0,-100,100,-50,INFINITY,&u,&v));
    CHECK(!commander_tactical_project(0,0,-40000,100,-50,50,&u,&v));
    puts("PASS tactical host/teammate/map policy");
    return 0;
}
"""

class TacticalMapTest(unittest.TestCase):
    def test_projection_and_authority_policy(self):
        cc = os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc, "C compiler required")
        with tempfile.TemporaryDirectory(prefix="commander-tactical-") as directory:
            root = Path(directory)
            (root / "policy.c").write_text(CASES)
            cmd = [cc, "-std=c99", "-Wall", "-Wextra", "-Werror", "-I", str(GAME),
                   str(root / "policy.c"), "-o", str(root / "policy")]
            built = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            result = subprocess.run([str(root / "policy")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS", result.stdout)

    def test_no_enemy_markers_or_client_self_grant(self):
        s = SOURCE.read_text()
        self.assertIn("game_connection() == _game_connection_network_server", COMMANDER.read_text())
        self.assertIn("local == 0 && commander_features_host_can_assign()", s)
        self.assertIn("game_engine_has_teams()", s)
        self.assertIn("commander_features_mask()", COMMANDER.read_text())
        self.assertIn("commander_tactical_host_can_assign(", COMMANDER.read_text())
        self.assertIn("commander_tactical_ally(", s)
        self.assertIn("unit->unit.player_index != it.datum_index", s)
        self.assertIn("TEST_FLAG(unit->object.damage_flags, _object_dead_bit)", s)
        self.assertIn("COMMANDER_TACTICAL_MAX_MARKERS 48", s)
        self.assertIn("global_structure_bsp_get()", s)
        self.assertIn("host_touch_commander_take_toggle()", s)
        self.assertIn("host_touch_commander_state(state)", s)
        self.assertIn("commander_tactical_reset();", COMMANDER.read_text())
        self.assertIn("commander_tactical_present(render.local_player_index);", HUD.read_text())
        self.assertIn("No enemy intel", s)

    def test_android_bridge_and_ui_are_opt_in(self):
        raw = HOST.read_text()
        java = TOUCH.read_text()
        for name in ("host_touch_commander_state", "host_touch_commander_take_toggle", "host_touch_commander_take_assign"):
            self.assertIn(name, IMPORTS.read_text())
            self.assertIn(name, GUEST.read_text())
            self.assertIn(name, raw)
        for name in ("nativeCommanderToggle", "nativeCommanderStatus", "nativeCommanderAssign"):
            self.assertIn("Java_com_halo_decomp_TouchControls_" + name, raw)
            self.assertIn(name, java)
        self.assertIn("pthread_mutex_lock(&touch_lock)", raw)
        self.assertIn("commander_toggle_pending = 1;", raw)
        self.assertIn("commander_toggle_pending = 0;", raw)
        self.assertIn("(commanderStatus & 1) != 0", java)
        self.assertIn("TOOLBAR_COMMANDER", java)
        self.assertIn("TOOLBAR_ASSIGN", java)
        self.assertIn("commander_features_cycle_role()", SOURCE.read_text())
        self.assertIn("commander_features_local_is_role(local)", SOURCE.read_text())
        self.assertIn("in-game Map button", LAUNCHER.read_text())
        self.assertIn('"commander.enabled", _config_boolean, "false"',
                      (ROOT / "port/linux/src/port_config.c").read_text())

    def test_no_extra_wire_messages(self):
        header = (GAME / "network_distributed.h").read_text()
        self.assertIn("_distributed_message_orbital_incoming = 100", header)
        self.assertNotIn("_distributed_message_commander_map", header)

if __name__ == "__main__":
    unittest.main()
