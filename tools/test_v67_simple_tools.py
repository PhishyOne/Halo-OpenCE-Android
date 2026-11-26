"""Seven-action Tools and plain-English IP labels."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
J=ROOT/"port/android/app/src/main/java/com/halo/decomp"
class SimpleToolsTest(unittest.TestCase):
    def test_actions_and_progress(self):
        s=(J/"LauncherActivity.java").read_text()
        tools=s[s.index("// Seven visible user actions."):s.index("// Compact action over")]
        for title in ("CHECK MY MAPS","REPAIR FROM HOST","SHARE MAPS","RECEIVE MAPS",
                      "IMPORT MAPS","BACK UP CAMPAIGN PROGRESS","RESTORE CAMPAIGN BACKUP"):
            self.assertEqual(tools.count('setText("'+title+'")'),1,title)
        self.assertEqual(tools.count('new Button(this)'),8) # seven + contextual stop
        self.assertIn("mapAuditProgress.setVisibility(View.GONE)",tools)
        self.assertIn("repairPanel.addView(stockMapResults)",tools)
        self.assertIn("if (check.passed) continue;",s)
        self.assertIn("UnifiedMapImport.install(input, dataRoot, name,",s)
        self.assertIn("setMapToolsBusy(true)",s)
        self.assertIn("setMapToolsBusy(false)",s)
    def test_ip_labels(self):
        s=(J/"LauncherActivity.java").read_text()
        self.assertIn('setupInput(manualHostPanel, "HOST\'S IP ADDRESS", hostIp)',s)
        self.assertIn('setupInput(advanced, "YOUR IP (OPTIONAL)", local)',s)
        self.assertIn('local.setHint("Leave blank for auto")',s)
        self.assertIn('"YOUR IP: " + android.text.TextUtils.join',s)
    def test_global_god_mode_audio_reaches_android(self):
        engine=(ROOT/"source/game/game_engine_multiplayer_sounds.c").read_text()
        bridge=(ROOT/"port/android/host/host_sdl.c").read_text()
        java=(J/"HaloActivity.java").read_text()
        rewards=(ROOT/"port/linux/game/killstreak_rewards.c").read_text()
        chat=(ROOT/"port/linux/game/network_chat.c").read_text()
        distribute=(ROOT/"port/linux/game/network_distributed.c").read_text()
        block=bridge[bridge.index("int host_android_announce_killstreak(int award)"):].split("\n}",1)[0]
        self.assertIn("if (award != 3 && award != 5 && award != 14)",block)
        self.assertIn("award != 3 && award != 5 && award != 14",engine)
        self.assertIn("award != 3 && award != 5 && award != 14",java)
        self.assertIn("game_engine_play_killstreak_sound(local, 14)",chat)
        self.assertIn("distributed_send_notice(KILLSTREAK_PHANTOM_WARNING)",rewards)
        self.assertIn("_distributed_to_clients_reliably",distribute[distribute.index("void distributed_send_notice("):].split("\n}",1)[0])
        self.assertIn("R.raw.halo_reward_phantom_warning",java)

    def test_protocol_isolated_and_stock_fallback(self):
        stock=(J/"LanMapTransfer.java").read_text()
        repair=(J/"LanMapRepair.java").read_text()
        server=(J/"LanMapServer.java").read_text()
        custom=(J/"LanCustomMapTransfer.java").read_text()
        self.assertIn("PORT = 5166, LEGACY_PORT = 5164",stock)
        self.assertIn("connect(ip, LEGACY_PORT)",stock)
        self.assertIn("LanMapTransfer.repairConnection(host)",repair)
        self.assertIn("LanMapTransfer.healthy(root, offer)",repair)
        self.assertIn("LanCustomMapTransfer.installed(root, offer",repair)
        self.assertIn("LanCustomMapTransfer.download(connection, offer",repair)
        self.assertIn("LanCustomMapTransfer.serve(input,",server)
        self.assertIn("LanMapTransfer.serve(input,",server)
        self.assertIn("LanUpdateProtocol.sameSubnet(",server)
        self.assertIn("LinkOption.NOFOLLOW_LINKS",custom)
        self.assertIn("CustomMapImporter.validateImport(part,offer.name)",custom)
        self.assertIn("No files overwritten.",custom)
if __name__=="__main__":
    unittest.main()
