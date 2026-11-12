"""v22 Commander host role assignment: strict, source-backed policy gates.

The fake-world C policy runs without any retail maps and does NOT qualify
Android JNI, rendered HUD, or two-phone LAN behavior.
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "port/linux/game"
COMMANDER = (GAME / "commander_features.c").read_text
ROLE = GAME / "commander_role_policy.h"
LIMITS = ROOT / "port/linux/include/halo_port_limits.h"
HOST = ROOT / "port/android/host/host_touch.c"
GUEST = ROOT / "port/android/guest/runtime/guest_host.h"
IMPORTS = ROOT / "port/android/host_imports.list"
TOUCH = ROOT / "port/android/app/src/main/java/com/halo/decomp/TouchControls.java"

C = r"""
#include <stdio.h>
#include <stddef.h>
#include "commander_role_policy.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"bad line %d: %s\n", __LINE__, #x); return 1; } } while(0)
int main(void)
{
    wchar_t host[12] = { 'H','o','s','t' };
    wchar_t host_alt[12] = { 'H','o','s','t' };
    wchar_t other[12] = { 'G','u','e','s','t' };
    wchar_t blank[12] = { 0 };
    wchar_t contaminated[12] = { 0 };
    unsigned i;
    contaminated[11] = 1;
    CHECK(commander_role_identity_valid(COMMANDER_ROLE_NONE,COMMANDER_ROLE_NONE,blank,128));
    CHECK(!commander_role_identity_valid(COMMANDER_ROLE_NONE,0,blank,128));
    CHECK(!commander_role_identity_valid(COMMANDER_ROLE_NONE,COMMANDER_ROLE_NONE,contaminated,128));
    CHECK(!commander_role_identity_valid(128,0,host,128));
    CHECK(!commander_role_identity_valid(0,2,host,128));
    CHECK(!commander_role_identity_valid(0,0,blank,128));
    CHECK(!commander_role_identity_valid(0,0,NULL,128));
    CHECK(commander_role_identity_valid(0,0,host,128));
    CHECK(commander_role_identity_valid(127,1,other,128));
    CHECK(commander_role_identity_matches(13,1,host,13,1,host_alt,128));
    CHECK(!commander_role_identity_matches(13,1,host,14,1,host_alt,128));
    CHECK(!commander_role_identity_matches(13,1,host,13,0,host_alt,128));
    CHECK(!commander_role_identity_matches(13,1,host,13,1,other,128));
    CHECK(!commander_role_identity_matches(COMMANDER_ROLE_NONE,COMMANDER_ROLE_NONE,blank,13,1,host,128));
    CHECK(!commander_role_identity_matches(13,1,host,13,1,NULL,128));
    for (i=0; i<12; i++) {
        host_alt[i] = host[i] ^ 1;
        CHECK(!commander_role_identity_matches(13,1,host,13,1,host_alt,128));
        host_alt[i] = host[i];
    }
    puts("PASS commander role identity, empty sentinel, stale-slot/team/name rejection");
    return 0;
}
"""

class CommanderRolesTest(unittest.TestCase):
    def test_identity_policy_c(self):
        cc = os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory() as tmp:
            c = Path(tmp) / "test.c"
            exe = Path(tmp) / "test"
            c.write_text(C)
            build = subprocess.run([cc, "-std=c99", "-Wall", "-Wextra", "-Werror",
                "-I", str(GAME), str(c), "-o", str(exe)],
                capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("PASS", run.stdout)

    def test_only_host_appoints_and_no_bot(self):
        s = COMMANDER()
        for required in (
            "game_connection() == _game_connection_network_server",
            "commander_features_host_can_assign()",
            "commander_features_local_is_role(short local)",
            "commander_features_cycle_role(void)",
            "role_host.slot = (word)chosen_slot",
            "role_host.team = (word)selected->team_index",
            "bots_machine_is_bot(p->network_player_data.machine_index)",
            "role_clear(&role_host)",
            "role_generation++",
            "role_player_valid(&role_host)",
            "commander_role_identity_matches",
            "game_engine_has_teams()",
            "role_clear(&role_client)",
            "role_receive_serial = 0",
            "role_send_serial; /* never reset",
        ):
            self.assertIn(required, s, required)
        self.assertNotIn("commander_features_cycle_role();",
            (GAME / "network_distributed.c").read_text())

    def test_reliable_packet_and_replay_guards(self):
        s = COMMANDER()
        for required in (
            "sizeof(struct distributed_commander_settings) == 44",
            "message.serial =",
            "m->serial = ++role_send_serial",
            "m.serial <= role_receive_serial",
            "machine_index != NONE",
            "game_connection() != _game_connection_network_client",
            "network_connection_last_read_was_unreliable()",
            "size != sizeof(m)",
            "GET_MESSAGE_TYPE(m.header.header) != 2",
            "GET_MESSAGE_SIZE(m.header.header) != sizeof(m)",
            "GET_MESSAGE_FLAGS(m.header.header) != 0",
            "m.header.count != 1",
            "m.reserved != 0",
            "(m.mask & ~COMMANDER_KNOWN_MASK) != 0",
            "commander_role_identity_valid",
            "commander_policy_effective(m.mask, 1, 1)",
            "role_receive_serial = m.serial",
            "_distributed_to_clients_reliably",
            "distributed_send_to_machine_reliably",
        ):
            if required == "message.serial =":
                continue
            self.assertIn(required, s, required)
        self.assertIn("#define HALO_PORT_NETWORK_VERSION 47", LIMITS.read_text())

    def test_native_touch_role_gate(self):
        raw = HOST.read_text()
        java = TOUCH.read_text()
        for name in ("host_touch_commander_take_assign", "host_touch_commander_state"):
            self.assertIn(name, raw)
            self.assertIn(name, GUEST.read_text())
            self.assertIn(name, IMPORTS.read_text())
        self.assertIn("commander_assign_pending = 0", raw)
        self.assertIn("commander_assign_pending = 1", raw)
        self.assertIn("(commander_status & 4)", raw)
        self.assertIn("nativeCommanderAssign()", java)
        self.assertIn("TOOLBAR_ASSIGN", java)
        self.assertIn("(commanderStatus & 4)", java)

if __name__ == "__main__":
    unittest.main()
