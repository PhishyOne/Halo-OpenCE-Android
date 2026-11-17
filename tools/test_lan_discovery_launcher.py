"""Source-contract tests for v36 LAN discovery and compact launcher."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "port/android/app/src/main/java/com/halo/decomp"


class DiscoveryAndLauncherTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.launcher = (SRC / "LauncherActivity.java").read_text()
        cls.game = (SRC / "HaloActivity.java").read_text()
        cls.server = (SRC / "LanDiscoveryServer.java").read_text()
        cls.client = (SRC / "LanDiscoveryClient.java").read_text()
        cls.picker = (SRC / "LanHostPicker.java").read_text()

    def test_only_host_game_advertises(self):
        self.assertIn('game.putExtra(HaloActivity.LAN_DISCOVERY_HOST, true);',
                      self.launcher)
        self.assertIn('getBooleanExtra(LAN_DISCOVERY_HOST, false)', self.game)
        self.assertIn('LanDiscoveryServer.start()', self.game)
        self.assertIn('lanDiscoveryServer.stop()', self.game)
        self.assertNotIn('LanDiscoveryServer.start()', self.launcher)

    def test_update_sharing_remains_opt_in(self):
        self.assertIn('if (shareUpdates.isChecked())', self.launcher)
        self.assertIn('game.putExtra(HaloActivity.LAN_UPDATE_HOST, true)', self.launcher)
        self.assertIn('getBooleanExtra(LAN_UPDATE_HOST, false)', self.game)
        self.assertIn('LanUpdateClient.check(this, hostIp.getText()', self.launcher)

    def test_no_cellular_or_arbitrary_lan_replies(self):
        self.assertIn('LanUpdateProtocol.lanInterface(nic.getName())', self.server)
        self.assertIn('LanUpdateProtocol.privateAddress(peer.getHostAddress())', self.server)
        self.assertIn('LanDiscoveryProtocol.sameLan(local.getAddress(), peer, prefix)',
                      self.server)
        self.assertIn('new InetSocketAddress(local, 0)', self.server)
        self.assertIn('LanDiscoveryProtocol.readProbe(', self.server)
        self.assertIn('LanDiscoveryProtocol.validReply(', self.client)
        self.assertIn('new SecureRandom().nextBytes(nonceBytes)', self.client)
        self.assertNotIn('getDeviceId(', self.server)

    def test_join_is_automatic_with_manual_fallback(self):
        self.assertIn('hostPicker.search(true, () -> play.performClick());',
                      self.launcher)
        self.assertIn('hostPicker.search(false, null);', self.launcher)
        self.assertIn('hostPicker.isSearching()', self.launcher)
        self.assertNotIn('manualHostToggle', self.launcher)
        self.assertIn('manualHostPanel.addView(hostIp);', self.launcher)
        self.assertIn('manual.setVisibility(View.VISIBLE);', self.picker)
        self.assertIn('new AlertDialog.Builder(activity)', self.picker)
        self.assertIn('address.setText(current);', self.picker)
        self.assertIn('LanUpdateProtocol.privateAddress(\n', self.launcher)

    def test_tools_separate_from_game_modes(self):
        self.assertIn('"Profile", "Offline Match", "Campaign", "Host", "Join", "Tools"', self.launcher)
        for name in ('mapsToggle', 'repairPanel', 'customMapsToggle',
                     'campaignSavesPanel', 'diagnostics'):
            self.assertIn(f'toolsPage.addView({name});', self.launcher)
        self.assertIn('toolsPage.setVisibility(toolsVisible[0] ? View.VISIBLE : View.GONE);',
                      self.launcher)
        self.assertNotIn('footer.addView(choices);', self.launcher)
        self.assertIn('footer.addView(play, playParams);', self.launcher)


if __name__ == "__main__":
    unittest.main()
