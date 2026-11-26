"""Automatic LAN social hub and restricted, disk-free highlight streaming."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / "port/android/app/src/main/java/com/halo/decomp"

class SocialHubTest(unittest.TestCase):
    def read(self, name):
        return (J / (name + ".java")).read_text()

    def test_one_default_on_switch_preserves_old_explicit_off(self):
        settings = self.read("LanShareSettings")
        ui = self.read("LanBoardUi")
        self.assertIn('getBoolean("lan_board_share", true), true)', settings)
        self.assertIn("this.reels = career && reels", settings)
        self.assertIn("new State(enabled, enabled)", settings)
        self.assertIn("new State(false, false)", settings)
        self.assertIn("LAN SHARING • STATS + HIGHLIGHTS", ui)
        self.assertEqual(ui.count("sharing = new CheckBox(activity)"), 1)
        self.assertNotIn("Share saved highlights?", ui)
        self.assertIn("Not encrypted.", ui)
        self.assertIn("Full recordings stay private".lower(), ui.lower())
        self.assertIn("LanShareSettings.career(this)", self.read("LauncherActivity"))
        self.assertIn("refreshSocialSharing();", self.read("HaloActivity"))
        self.assertIn("LanShareSettings.career(this)", self.read("HaloActivity"))

    def test_automatic_career_sync_is_separate_and_bounded(self):
        monitor = self.read("LanBoardPresenceMonitor")
        client = self.read("LanBoardClient")
        self.assertIn("maybeAutoSync();", monitor)
        self.assertIn("!LanShareSettings.career(activity)", monitor)
        self.assertIn("auto.due(snapshot, SystemClock.elapsedRealtime())", monitor)
        self.assertIn("LanBoardClient.sync(context, root, peer.ip, peer.id)", monitor)
        self.assertIn("LanShareSettings.career(context)", client)
        self.assertIn("expectedId != null && !ids.contains(expectedId)", client)
        self.assertIn("INTERVAL_MS = 30000", self.read("LanBoardAutoSync"))
        self.assertIn("tryLock()", self.read("LanBoardStore"))

    def test_highlights_share_only_current_allowed_files(self):
        server = self.read("LanBoardServer")
        protocol = self.read("LanReelProtocol")
        self.assertIn("!LanShareSettings.career(context)", server)
        self.assertIn("!stopped && LanShareSettings.reels(context)", server)
        self.assertIn('new File(dir, "reel-" + id + ".mp4")', protocol)
        self.assertIn("LinkOption.NOFOLLOW_LINKS", protocol)
        self.assertIn("length > size - offset", protocol)
        self.assertIn("MAX_REEL = 48L * 1024 * 1024", protocol)
        self.assertNotIn("FileOutputStream", protocol)
        self.assertIn("deadlines.schedule", server)

    def test_leaderboard_first_with_three_in_activity_pages(self):
        ui = self.read("LanBoardUi")
        launcher = self.read("LauncherActivity")
        self.assertIn('"Leaderboard", "Offline Match", "Campaign", "Host", "Join", "Tools"', launcher)
        self.assertIn("socialUi = new LanBoardUi(this, dataRoot, dashboardPanel, scroll)", launcher)
        self.assertIn("final boolean[] homeVisible = { !invitePending };", launcher)
        self.assertIn("footer.setVisibility(homeVisible[0] ? View.GONE : View.VISIBLE)", launcher)
        self.assertIn("if (socialUi != null && socialUi.back()) return;", launcher)
        self.assertIn("enum Page { BOARD, PLAYER, VIDEOS }", ui)
        self.assertIn("card.setOnClickListener(v -> select(e.id, name, self))", ui)
        self.assertIn("CareerProfile.dashboard(activity, root, career)", ui)
        self.assertIn("renderRemoteReport();", ui)
        self.assertIn("action(panel, \"VIEW HIGHLIGHTS / VIDEOS ›\", this::buildVideos)", ui)
        self.assertIn("if (page == Page.VIDEOS) buildPlayer();", ui)
        self.assertIn("LanReelClient.list(peer)", ui)
        self.assertIn("HaloReelGallery.manage(activity, root,", ui)
        self.assertIn("generation != request", ui)
        self.assertNotIn("new AlertDialog", ui)
        self.assertNotIn("new Dialog(", ui)
        self.assertNotIn(".setItems(", ui)
        self.assertNotIn("new ScrollView(", ui)

    def test_streaming_in_memory_and_lifecycle(self):
        client = self.read("LanReelClient")
        player = self.read("LanReelPlayer")
        launcher = self.read("LauncherActivity")
        ui = self.read("LanBoardUi")
        self.assertIn("extends MediaDataSource", client)
        self.assertIn("new LanReelBuffer(reel.size, this::fetch)", client)
        self.assertIn("LanBoardClient.localInterface(address)", client)
        self.assertNotIn("FileOutputStream", client)
        self.assertNotIn("http://", player)
        self.assertIn("player.setDataSource(source)", player)
        self.assertIn("source.close()", player)
        self.assertIn("remoteReelPlayer.close()", launcher)
        self.assertIn("snapshot().byId(id, SystemClock.elapsedRealtime())", ui)
        self.assertIn("LanReelPlayer.play(activity, latest, clip)", ui)
        self.assertIn("NOT DETECTED • saved career", ui)

if __name__ == "__main__":
    unittest.main()
