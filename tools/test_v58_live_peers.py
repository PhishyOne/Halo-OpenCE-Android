"""Live presence is separate from saved stats and foreground-only polling."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class LivePeerTest(unittest.TestCase):
    def setUp(self):
        self.launcher = (J / "LauncherActivity.java").read_text()
        self.monitor = (J / "LanBoardPresenceMonitor.java").read_text()
        self.server = (J / "LanBoardServer.java").read_text()
        self.nearby = (J / "LanBoardNearby.java").read_text()
        self.beacon = (J / "LanBoardBeacon.java").read_text()
        self.ui = (J / "LanBoardUi.java").read_text()

    def test_presence_has_no_stat_reads_or_exchange(self):
        branch = self.server.split("String ping = LanBoardPresence.readQuery(request);", 1)[1].split("String[] parts", 1)[0]
        self.assertIn("LanBoardIdentity.name(context)", branch)
        self.assertIn("LanBoardPresence.answer(", branch)
        self.assertNotIn("LanBoardIdentity.card(", branch)
        self.assertNotIn("LanBoardStore.merge(", branch)
        scan = self.nearby.split("static List<LanBoardPresence.Peer> presence(", 1)[1]
        self.assertNotIn("LanBoardClient.sync(", scan)
        self.assertNotIn("LanBoardStore.merge(", scan)
        self.assertNotIn("LanBoardStore.read(", self.monitor)

    def test_all_tabs_and_foreground_lifecycle(self):
        self.assertLess(self.launcher.index("masthead.addView(livePeers, liveParams)"),
                        self.launcher.index("root.addView(modeBar)"))
        self.assertIn("livePeerMonitor.pause()", self.launcher)
        self.assertIn("livePeerMonitor.close()", self.launcher)
        self.assertIn("livePeerMonitor.resume()", self.launcher)
        self.assertIn("if (!active || closed || busy) return;", self.monitor)
        self.assertIn("if (run == generation)", self.monitor)
        self.assertIn("handler.removeCallbacks(tick)", self.monitor)
        self.assertIn("REFRESH_MS = 6000", self.monitor)

    def test_legacy_and_rebinding(self):
        self.assertIn("LanBoardPresence.Peer.legacy", self.nearby)
        self.assertIn("discovery only", self.monitor)
        self.assertIn("!current.containsKey(e.getKey())", self.server)
        self.assertIn("Thread.sleep(3000)", self.server)
        self.assertIn("lock.release()", self.beacon)
        self.assertIn("route.isDefaultRoute()", self.nearby)
        self.assertIn("socket.bind(new InetSocketAddress(local, 0))", self.nearby)

    def test_manual_sync_only(self):
        self.assertIn("sync.setOnClickListener(view -> synchronize());", self.ui)
        self.assertNotIn("if (sharing.isChecked()) synchronize();", self.ui)
        self.assertNotIn("if (enabled) synchronize();", self.ui)
        self.assertIn("liveBoardTargets()", self.ui)
        self.assertIn("LanBoardNearby.refresh(activity, root, ip, live)", self.ui)
        self.assertIn("Last synced: ", self.ui)

if __name__ == "__main__":
    unittest.main()
