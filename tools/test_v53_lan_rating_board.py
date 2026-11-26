"""v53 P2P LAN leaderboard + honest, provisional skill rating contracts."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"port/android/app/src/main/java/com/halo/decomp"

class LanPeerRatingUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.launcher=(JAVA/"LauncherActivity.java").read_text()
        cls.game=(JAVA/"HaloActivity.java").read_text()
        cls.profile=(JAVA/"CareerProfile.java").read_text()
        cls.protocol=(JAVA/"LanBoardProtocol.java").read_text()
        cls.discovery=(JAVA/"LanBoardDiscovery.java").read_text()
        cls.beacon=(JAVA/"LanBoardBeacon.java").read_text()
        cls.nearby=(JAVA/"LanBoardNearby.java").read_text()
        cls.server=(JAVA/"LanBoardServer.java").read_text()
        cls.client=(JAVA/"LanBoardClient.java").read_text()
        cls.ui=(JAVA/"LanBoardUi.java").read_text()
        cls.storage=(JAVA/"LanBoardStore.java").read_text()
        cls.rating=(JAVA/"LanRating.java").read_text()
        cls.identity=(JAVA/"LanBoardIdentity.java").read_text()

    def test_rating_is_provisional_not_fake_verified(self):
        self.assertIn("PRACTICE SR",self.profile)
        self.assertIn("Verified competitive Spartan Rating: UNRATED",self.profile)
        self.assertIn("VIEW / SYNC LAN LEADERBOARD",self.profile)
        self.assertIn("LanRating.calculate(career)",self.profile)
        self.assertIn("m.botKills == 0",self.rating)
        self.assertIn("m.humanKills == m.kills",self.rating)
        self.assertIn("!m.incomplete",self.rating)
        self.assertIn("!m.cooperative()",self.rating)
        self.assertIn("m.seconds >= 60",self.rating)
        self.assertIn("BASE = 1500",self.rating)
        self.assertIn("NOT official competitive Elo",self.rating)

    def test_each_opted_in_phone_can_serve_while_launcher_or_game_open(self):
        self.assertIn("refreshBoardSharing()",self.launcher)
        self.assertIn("lanBoardServer = LanBoardServer.start(this)",self.launcher)
        self.assertIn("stopBoardSharing();",self.launcher)
        self.assertIn("intent.putExtra(HaloActivity.LAN_BOARD_HOST",self.launcher)
        self.assertIn("LanShareSettings.career(this)",self.launcher)
        self.assertIn("LAN_BOARD_HOST",self.game)
        self.assertIn("lanBoardServer = LanBoardServer.start(this)",self.game)
        self.assertIn("lanBoardServer.stop();",self.game)
        self.assertIn("including campaign and offline bot matches",self.launcher)
        self.assertIn("port", self.server.lower())
        self.assertIn("new ServerSocket(",self.server)
        self.assertIn("LanBoardBeacon.start(context)",self.server)

    def test_nearby_discovery_is_nonce_bounded_and_subnet_restricted(self):
        self.assertIn("PORT = 5164",self.protocol)
        self.assertIn("PORT = 5165",self.discovery)
        self.assertIn("MAX_PEERS = 24",self.protocol)
        self.assertIn("MAX_LINE = 256",self.protocol)
        self.assertIn("MAX_PACKET = 80",self.discovery)
        self.assertIn("expected.equals(parse(",self.discovery)
        self.assertIn("LanBoardDiscovery.readProbe(",self.beacon)
        self.assertIn("LanUpdateProtocol.sameSubnet(",self.beacon)
        self.assertIn("LanUpdateProtocol.sameSubnet(",self.server)
        self.assertIn("LanUpdateProtocol.sameSubnet(",self.client)
        self.assertIn("LanDiscoveryProtocol.broadcast(",self.nearby)
        self.assertIn("LanBoardDiscovery.validReply(",self.nearby)
        self.assertIn("LanBoardClient.sync(",self.nearby)
        self.assertIn("MAX_DISCOVERED = 16",self.nearby)
        self.assertIn("socket.setSoTimeout(",self.client)
        self.assertIn("LanBoardProtocol.boundedLine(",self.client)
        self.assertIn("LanBoardProtocol.boundedLine(",self.server)
        self.assertIn("new SecureRandom()",self.client)

    def test_no_auto_upload_no_hosted_backend_credentials_or_full_journals(self):
        for body in (self.server,self.client,self.nearby,self.protocol,self.beacon):
            self.assertNotIn("HttpURLConnection",body)
            self.assertNotIn("new URL(",body)
            self.assertNotIn("https://",body)
            self.assertNotIn("JWT",body)
            self.assertNotIn("Authorization:",body)
            self.assertNotIn("writeMatch",body)
        self.assertIn("lan-board-v1.txt",self.protocol)
        self.assertIn("MAX_STORED_BYTES = 8192",self.protocol)
        self.assertIn("Files.move(",self.storage)
        self.assertIn("old.seenMs < entry.seenMs",self.storage)
        self.assertIn("!entry.id.equals(local.id)",self.storage)
        self.assertNotIn("MAX_AGE_MS",self.storage)
        self.assertIn("SAVE",self.ui.upper())  # UI describes saved local summaries

    def test_user_controls_sharing_and_can_refresh_without_host_server(self):
        self.assertIn("LAN CAREER SHARING",self.ui)
        self.assertIn("REFRESH LAN",self.ui)
        self.assertIn("LanBoardNearby.refresh(",self.ui)
        self.assertNotIn("if (sharing.isChecked()) synchronize();",self.ui)
        self.assertIn('sync = button("REFRESH LAN", this::synchronize)',self.ui)
        self.assertIn("LanShareSettings.career(activity)",self.ui)
        self.assertIn("Peer IP (optional)",self.ui)
        self.assertIn("LAN LEADERBOARD",self.ui)
        self.assertIn("UNVERIFIED",self.ui)
        self.assertIn("Last synced: ",self.ui)
        self.assertIn("java.text.DateFormat.MEDIUM",self.ui)
        self.assertIn("java.text.DateFormat.SHORT",self.ui)
        self.assertIn("saved career",self.ui)
        self.assertIn("LOCAL PLAYER",self.ui)
        self.assertNotIn("Recently seen on LAN",self.ui)
        self.assertNotIn("MAX_AGE_MS",self.storage)
        self.assertNotIn("MAX_AGE_MS",self.protocol)
        self.assertIn("getSharedPreferences",self.identity)
        self.assertIn("private static final SecureRandom RNG",self.identity)
        self.assertIn("commit()",self.identity)

if __name__=="__main__":
    unittest.main()
