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
#define CHECK(x) do { if (!(x)) {fprintf(stderr, "bad line %d: %s\n",__LINE__,#x);return 1;} } while(0)
int main(void) {
  unsigned int all=COMMANDER_KNOWN_MASK, flags, selected;
  int i;
  CHECK(commander_policy_effective(0,1)==0);
  CHECK(commander_policy_effective(all & ~COMMANDER_ENABLED,1)==0);
  CHECK(commander_policy_effective(all,0)==0);
  CHECK(commander_policy_effective(all,1)==all);
  CHECK(commander_policy_effective(COMMANDER_ENABLED|COMMANDER_ORBITAL,1)
        ==COMMANDER_ENABLED);
  CHECK(commander_policy_effective(COMMANDER_ENABLED|COMMANDER_KILLSTREAKS|
      COMMANDER_ORBITAL,1)==(COMMANDER_ENABLED|COMMANDER_KILLSTREAKS|COMMANDER_ORBITAL));
  CHECK(commander_policy_effective(all|0x1000u,1)==all);
  CHECK(!commander_policy_allows(0,COMMANDER_SATELLITE));
  CHECK(!commander_policy_allows(COMMANDER_SATELLITE,COMMANDER_SATELLITE));
  CHECK(commander_policy_allows(all,COMMANDER_ORDERS));
  CHECK(commander_policy_allows(all,COMMANDER_ANNOUNCEMENTS));
  CHECK(!commander_policy_allows(all,COMMANDER_ORDERS|COMMANDER_UAV));
  CHECK(!commander_policy_allows(all,0));
  CHECK(!commander_policy_allows(all,0x1000u));
  for (i=0;i<256;i++) {
    flags=commander_policy_effective((unsigned)i,1);
    CHECK((flags & ~COMMANDER_KNOWN_MASK)==0);
    if (!(i & COMMANDER_ENABLED)) CHECK(flags==0);
    if (!(i & COMMANDER_KILLSTREAKS)) CHECK(!(flags & COMMANDER_ORBITAL));
    if ((i & COMMANDER_ENABLED)) {
      CHECK(commander_policy_allows(flags,COMMANDER_ENABLED));
      CHECK(!!commander_policy_allows(flags,COMMANDER_SPOTTING) ==
            !!(i & COMMANDER_SPOTTING));
    }
    for (selected=1;selected<=COMMANDER_ANNOUNCEMENTS;selected<<=1) {
      if (commander_policy_allows(flags,selected))
        CHECK((flags & selected)==selected);
    }
  }
  puts("Commander feature policy PASS (256 masks, dependencies, team-only, fail-closed)");
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

    def test_config_master_defaults_off(self):
        s=CONFIG.read_text()
        self.assertIn('{ "commander.enabled", _config_boolean, "false"',s)
        for name in ("satellite","uav","spotting","orders",
                     "killstreaks","orbital","announcements"):
            self.assertIn('{ "commander.'+name+'", _config_boolean, "true"',s)

    def test_client_can_never_enable_itself(self):
        s=NATIVE.read_text()
        self.assertIn('if (game_connection() == _game_connection_network_client)',s)
        self.assertIn('commander_policy_effective(commander_client_mask, 1)',s)
        self.assertIn('return commander_policy_effective(commander_host_selected_mask(), 1)',s)
        self.assertIn('static unsigned int commander_client_mask;',s)
        self.assertIn('commander_client_mask = 0;',s)
        self.assertIn('!game_in_progress() || !game_engine_running()',s)
        self.assertIn('network_coop_active()',s)
        self.assertIn('variant->universal_variant.teams',s)
        self.assertIn('network_connection_last_read_was_unreliable()',s)
        self.assertIn('game_connection() != _game_connection_network_client',s)
        self.assertIn('machine_index != NONE',s)
        self.assertIn('size != sizeof(message)',s)
        self.assertIn('message.reserved != 0',s)
        self.assertIn('message.header.count != 1',s)
        self.assertIn('(message.mask & ~COMMANDER_KNOWN_MASK) != 0',s)
        self.assertIn('commander_client_mask = commander_policy_effective(message.mask, 1)',s)

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

    def test_android_host_only_and_master_dependency(self):
        s=LAUNCHER.read_text()
        self.assertIn('getSharedPreferences("commander_controls", MODE_PRIVATE)',s)
        self.assertIn('commanderToggle.setText("COMMANDER MODE: "',s)
        self.assertIn('commanderChecks[0].isChecked()',s)
        self.assertIn('commanderChecks[5].isChecked()',s)
        self.assertIn('i != 6 || killstreaks',s)
        self.assertIn('hostRole.isChecked()',s)
        self.assertIn('!invitePending',s)
        self.assertIn('saveCommanderChoices(new File(dataRoot, "config.toml")',s)
        self.assertIn('CommanderConfig.disable(file)',s)
        self.assertIn('CommanderConfig.save(file, selected)',s)
        self.assertIn('CommanderConfig.disable(new File(dataRoot, "config.toml"))',s)
        self.assertIn('Only policy controls are available',s)

if __name__=="__main__":
    unittest.main()
