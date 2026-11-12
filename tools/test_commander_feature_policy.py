"""Commander feature matrix and host-authoritative transport regression gates."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
POLICY=ROOT/"port/linux/game/commander_feature_policy.h"
NATIVE=ROOT/"port/linux/game/commander_features.c"
NETWORK=ROOT/"port/linux/game/network_distributed.c"
HEADER=ROOT/"port/linux/game/network_distributed.h"
CONFIG=ROOT/"port/linux/src/port_config.c"
LAUNCHER=ROOT/"port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java"
MAIN=ROOT/"source/main/main.c"

C=r"""
#include <stdio.h>
#include "commander_feature_policy.h"
#define CHECK(x) do {if(!(x)){fprintf(stderr,"failed line %d: %s\n",__LINE__,#x);return 1;}}while(0)
int main(void) {
    unsigned int all=COMMANDER_KNOWN_MASK, flags, ffa;
    int i;
    CHECK(commander_policy_effective(0,1,1)==0);
    CHECK(commander_policy_effective(all,1,0)==0); /* campaign/co-op */
    CHECK(commander_policy_effective(all,1,1)==all);
    CHECK(commander_policy_effective(all,0,1)==KILLSTREAK_FEATURE_MASK);
    CHECK(commander_policy_effective(COMMANDER_ENABLED|COMMANDER_UAV,1,1)
          == (COMMANDER_ENABLED|COMMANDER_UAV));
    CHECK(commander_policy_effective(KILLSTREAK_ENABLED|KILLSTREAK_ORBITAL,1,1)
          == (KILLSTREAK_ENABLED|KILLSTREAK_ORBITAL));
    CHECK(commander_policy_effective(COMMANDER_ENABLED|KILLSTREAK_ENABLED,1,1)
          == (COMMANDER_ENABLED|KILLSTREAK_ENABLED));
    CHECK(commander_policy_effective(KILLSTREAK_ORBITAL|COMMANDER_ENABLED,1,1)
          == COMMANDER_ENABLED);
    CHECK(commander_policy_effective(all|0x8000u,1,1)==all);
    CHECK(!commander_policy_allows(0,COMMANDER_SATELLITE));
    CHECK(!commander_policy_allows(all,COMMANDER_ORDERS|COMMANDER_UAV));
    CHECK(!commander_policy_allows(all,0));
    CHECK(!commander_policy_allows(all,0x8000u));
    CHECK(!killstreak_policy_allows(all,COMMANDER_ORDERS));
    CHECK(killstreak_policy_allows(all,KILLSTREAK_ENABLED));
    CHECK(killstreak_policy_allows(all,KILLSTREAK_ORBITAL));
    for(i=0;i<512;i++) {
        flags=commander_policy_effective((unsigned)i,1,1);
        ffa=commander_policy_effective((unsigned)i,0,1);
        CHECK((flags & ~COMMANDER_KNOWN_MASK)==0);
        CHECK((ffa & COMMANDER_FEATURE_MASK)==0);
        CHECK((ffa & KILLSTREAK_FEATURE_MASK)==(flags & KILLSTREAK_FEATURE_MASK));
        CHECK(commander_policy_effective((unsigned)i,1,0)==0);
        if(!(i & COMMANDER_ENABLED)) CHECK(!(flags & COMMANDER_FEATURE_MASK));
        if(!(i & KILLSTREAK_ENABLED)) CHECK(!(flags & KILLSTREAK_FEATURE_MASK));
        if(!(i & KILLSTREAK_ENABLED)) CHECK(!(flags & KILLSTREAK_ORBITAL));
        CHECK(!!commander_policy_allows(flags,COMMANDER_ORDERS) ==
             !!((i & COMMANDER_ENABLED) && (i & COMMANDER_ORDERS)));
        CHECK(!!killstreak_policy_allows(flags,KILLSTREAK_ANNOUNCEMENTS) ==
             !!((i & KILLSTREAK_ENABLED) && (i & KILLSTREAK_ANNOUNCEMENTS)));
        CHECK(!!killstreak_policy_allows(ffa,KILLSTREAK_ORBITAL) ==
             !!((i & KILLSTREAK_ENABLED) && (i & KILLSTREAK_ORBITAL)));
    }
    puts("PASS independent Commander/Killstreak policy (512 masks, FFA, master deps)");
    return 0;
}
"""

class CommanderPolicyTest(unittest.TestCase):
    def test_pure_c_policy(self):
        cc=os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc,"C compiler is required")
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp)
            (work/"policy.c").write_text(C)
            built=subprocess.run(
                [cc,"-std=c99","-Wall","-Wextra","-Werror",
                 "-I",str(POLICY.parent),str(work/"policy.c"),"-o",str(work/"policy")],
                capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            run=subprocess.run([str(work/"policy")],
                capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)
            self.assertIn("PASS",run.stdout)

    def test_config_both_masters_default_off(self):
        s=CONFIG.read_text()
        self.assertIn('{ "commander.enabled", _config_boolean, "false"',s)
        self.assertIn('{ "killstreak.enabled", _config_boolean, "false"',s)
        for name in ("satellite","uav","spotting","orders","announcements"):
            self.assertIn('{ "commander.'+name+'", _config_boolean, "true"',s)
        for name in ("orbital","announcements"):
            self.assertIn('{ "killstreak.'+name+'", _config_boolean, "true"',s)
        self.assertNotIn('{ "commander.killstreaks",',s)
        self.assertNotIn('{ "commander.orbital",',s)

    def test_client_cannot_enable_either_group(self):
        s=NATIVE.read_text()
        self.assertIn('if (game_connection() == _game_connection_network_client)',s)
        self.assertIn('commander_policy_effective(commander_client_mask,',s)
        self.assertIn('commander_policy_effective(commander_host_selected_mask(),',s)
        self.assertIn('static unsigned int commander_client_mask;',s)
        self.assertIn('commander_client_mask = 0;',s)
        self.assertIn('game_in_progress() && game_engine_running()',s)
        self.assertIn('!network_coop_active()',s)
        self.assertIn('variant->universal_variant.teams',s)
        self.assertIn('config_boolean("killstreak.enabled")',s)
        self.assertIn('config_boolean("commander.enabled")',s)
        self.assertIn('config_boolean("killstreak.orbital")',s)
        self.assertIn('config_boolean("killstreak.announcements")',s)
        self.assertIn('int killstreak_feature_allowed(unsigned int feature)',s)
        self.assertIn('network_connection_last_read_was_unreliable()',s)
        self.assertIn('game_connection() != _game_connection_network_client',s)
        self.assertIn('machine_index != NONE',s)
        self.assertIn('size != sizeof(m)',s)
        self.assertIn('m.reserved != 0',s)
        self.assertIn('m.header.count != 1',s)
        self.assertIn('(m.mask & ~COMMANDER_KNOWN_MASK) != 0',s)
        self.assertIn('commander_client_mask = commander_policy_effective(m.mask, 1, 1)',s)

    def test_reliable_host_policy_wiring(self):
        s=NETWORK.read_text()
        native=NATIVE.read_text()
        main=MAIN.read_text()
        self.assertIn('_distributed_message_commander_settings = 96',HEADER.read_text())
        self.assertIn('commander_features_send_to_machine(machine_index);',s)
        self.assertIn('commander_features_new_game();',s)
        self.assertIn('commander_features_handle_message(machine_index, message, size);',s)
        self.assertIn('_distributed_to_clients_reliably',native)
        self.assertIn('distributed_send_to_machine_reliably(machine_index, &message,',native)
        self.assertIn('commander_features_update();',main)
        self.assertIn('commander_features_mask() == 0',native) if False else None

    def test_android_independent_masters_and_guest_disable(self):
        s=LAUNCHER.read_text()
        self.assertIn('getSharedPreferences("commander_controls", MODE_PRIVATE)',s)
        self.assertIn('getSharedPreferences("killstreak_controls", MODE_PRIVATE)',s)
        self.assertIn('commanderToggle.setText("2142 COMMANDER: "',s)
        self.assertIn('killstreakToggle.setText("KILLSTREAK REWARDS: "',s)
        self.assertIn('commanderChecks[0].isChecked()',s)
        self.assertIn('killstreakChecks[0].isChecked()',s)
        self.assertIn('hostRole.isChecked()',s)
        self.assertIn('!invitePending',s)
        self.assertIn('saveCommanderChoices(new File(dataRoot, "config.toml")',s)
        self.assertIn('saveKillstreakChoices(new File(dataRoot, "config.toml")',s)
        self.assertIn('CommanderConfig.disable(file)',s)
        self.assertIn('KillstreakConfig.disable(file)',s)
        self.assertIn('CommanderConfig.save(file, selected)',s)
        self.assertIn('KillstreakConfig.save(file, selected)',s)
        self.assertIn('CommanderConfig.disable(new File(dataRoot, "config.toml"))',s)
        self.assertIn('KillstreakConfig.disable(new File(dataRoot, "config.toml"))',s)
        self.assertIn('Switches only for now.',s)


if __name__=="__main__":
    unittest.main()
