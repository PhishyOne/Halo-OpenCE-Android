"""Source integration/security gates for opt-in v23 LAN APK sharing.

Run the pure-Java, real byte-stream test separately with:
  javac -d /tmp/halo-lan-test port/android/app/src/main/java/com/halo/decomp/LanUpdate{Protocol,Socket}.java tools/lan/LanUpdateProtocolTest.java
  java -cp /tmp/halo-lan-test com.halo.decomp.LanUpdateProtocolTest

These tests do NOT prove that an Android hotspot accepts sockets or that the
PackageManager/installer flow works on a physical phone.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"
LAUNCHER = JAVA / "LauncherActivity.java"
GAME = JAVA / "HaloActivity.java"
HOST = JAVA / "LanUpdateServer.java"
CLIENT = JAVA / "LanUpdateClient.java"
WIRE = JAVA / "LanUpdateSocket.java"
PROTOCOL = JAVA / "LanUpdateProtocol.java"
UPDATER = JAVA / "Updater.java"
PROVIDER = JAVA / "UpdateProvider.java"
MANIFEST = ROOT / "port/android/app/src/main/AndroidManifest.xml"


class LanApkUpdateTests(unittest.TestCase):
    def test_game_host_opt_in_only(self):
        source = LAUNCHER.read_text()
        game = GAME.read_text()
        self.assertIn('shareUpdates.setText("SHARE MY HALO VERSION WITH LAN PLAYERS")', source)
        self.assertIn('getBoolean("lan_update_share", true)', source)
        self.assertIn("lanMode.isChecked() && hostRole.isChecked()", source)
        self.assertIn("lanOnly.isChecked() && !invitePending", source)
        self.assertIn("if (shareUpdates.isChecked())", source)
        self.assertIn("game.putExtra(HaloActivity.LAN_UPDATE_HOST, true)", source)
        self.assertIn("LanUpdateClient.check(this, hostIp.getText().toString().trim()", source)
        self.assertIn("if (lanMode.isChecked() && lanOnly.isChecked() &&", source)
        self.assertIn('play.setEnabled(false);', source)
        self.assertIn("static final String LAN_UPDATE_HOST", game)
        self.assertIn("getBooleanExtra(LAN_UPDATE_HOST, false)", game)
        self.assertIn("lanUpdateServer = LanUpdateServer.start(this);", game)
        self.assertIn("lanUpdateServer.stop();", game)
        self.assertIn("if (lanUpdateServer != null)", game)
        self.assertIn('checkHostUpdate.setText("CHECK UPDATE")', source)

    def test_server_not_internet_or_arbitrary_files(self):
        source = HOST.read_text()
        wire = WIRE.read_text()
        policy = PROTOCOL.read_text()
        self.assertIn("context.getApplicationInfo().sourceDir", source)
        self.assertIn("splits != null && splits.length > 0", source)
        self.assertIn("LanUpdateProtocol.lanInterface(nic.getName())", source)
        self.assertIn("LanUpdateProtocol.privateAddress(local.getHostAddress())", source)
        self.assertIn("getNetworkPrefixLength()", source)
        self.assertIn("new ServerSocket(LanUpdateProtocol.PORT,", source)
        self.assertNotIn("new ServerSocket(LanUpdateProtocol.PORT)", source)
        self.assertIn("LanUpdateProtocol.sameSubnet(boundIp, peer, prefix)", wire)
        self.assertIn("endpoint == null", wire)
        self.assertIn("404", wire)
        self.assertIn("META_PATH = \"/halo/manifest\"", policy)
        self.assertIn("APK_PATH = \"/halo/apk\"", policy)
        self.assertIn("prefix < 16 || prefix > 30", policy)
        self.assertIn('n.startsWith("wlan")', policy)
        self.assertNotIn('n.startsWith("rmnet")', policy)
        self.assertIn("clients.shutdownNow();", source)

    def test_client_never_installs_unverified_apk(self):
        source = CLIENT.read_text()
        wire = WIRE.read_text()
        self.assertIn("LanUpdateSocket.fetchManifest(host)", source)
        self.assertIn("LanUpdateSocket.download(host, offer, partial,", source)
        self.assertIn("verifyPackage(activity, partial, offer);", source)
        self.assertIn("PackageManager.GET_SIGNING_CERTIFICATES", source)
        self.assertIn("pm.getPackageArchiveInfo(", source)
        self.assertIn("getApkContentsSigners()", source)
        self.assertIn("MessageDigest.isEqual(", source)
        self.assertIn("candidate.getLongVersionCode() != offer.version", source)
        self.assertIn("current.getLongVersionCode() >= candidate.getLongVersionCode()", source)
        self.assertIn("offer.sha256.equals(hash.toString())", wire)
        self.assertIn("length != offer.size", wire)
        self.assertIn("file.getFD().sync()", wire)
        self.assertIn("ATOMIC_MOVE", source)
        self.assertIn("Updater.install(activity);", source)
        self.assertIn("setPositiveButton(\"Download update\"", source)
        self.assertIn("setNegativeButton(\"Not now\"", source)
        self.assertIn("REQUEST_INSTALL_PACKAGES", MANIFEST.read_text())
        self.assertIn("android:grantUriPermissions=\"true\"", MANIFEST.read_text())
        self.assertIn("static void install(Activity activity)", UPDATER.read_text())
        self.assertIn('static final String APK = "halo.apk"', PROVIDER.read_text())

    def test_android_single_instance_host_listener_rearm_and_tether_diagnostics(self):
        game = GAME.read_text()
        server = HOST.read_text()
        protocol = PROTOCOL.read_text()
        client = CLIENT.read_text()
        launcher = LAUNCHER.read_text()
        report = (JAVA / "HaloDiagnostics.java").read_text()
        manifest = MANIFEST.read_text()
        self.assertIn('android:launchMode="singleInstance"', manifest)
        for fragment in (
            'public void onNewIntent(Intent intent)',
            'super.onNewIntent(intent);',
            'setIntent(intent);',
            'updateLanShare(intent);',
            'getBooleanExtra(LAN_UPDATE_HOST, false)',
            'lanUpdateServer.stop();',
            'lanUpdateServer = null;',
            'lanUpdateServer = LanUpdateServer.start(this);',
        ):
            self.assertIn(fragment, game, fragment)
        self.assertLess(game.index('public void onNewIntent(Intent intent)'),
            game.index('private void updateLanShare(Intent intent)'))
        for fragment in (
            'LanUpdateProtocol.usableHostPrefix(',
            'LanUpdateProtocol.privateAddress(local.getHostAddress())',
            'new ServerSocket(LanUpdateProtocol.PORT,',
            'LanUpdateProtocol.sameSubnet(boundIp, peer, prefix)',
        ):
            # The subnet check lives in LanUpdateSocket. All other
            # bind decisions must be constrained to a private interface.
            self.assertIn(fragment, server + WIRE.read_text())
        self.assertIn('for (int attempt = 0; !closed && attempt < 6', server)
        self.assertIn('Thread.sleep(900)', server)
        self.assertIn('status("READY"', server)
        self.assertIn('status("UNAVAILABLE"', server)
        self.assertIn('status("STOPPED"', server)
        self.assertIn('"lan-update-status.txt"', server)
        self.assertIn('File.createTempFile(', server)
        self.assertIn('StandardCopyOption.REPLACE_EXISTING', server)
        self.assertIn('static int usableHostPrefix(', protocol)
        self.assertIn('return hotspot ? 24 : -1', protocol)
        self.assertIn('!lanInterface(interfaceName)', protocol)
        self.assertNotIn('n.startsWith("rmnet")', protocol)
        self.assertNotIn('n.startsWith("tun")', protocol)
        self.assertIn('LanUpdateProtocol.connectionHelp(exception)', client)
        self.assertIn('int attempts = manual ? 3 : 1', client)
        self.assertIn('instanceof java.net.ConnectException', client)
        self.assertIn('Thread.sleep(450L * (attempt + 1))', client)
        self.assertIn('"CHECK UPDATE"', launcher)
        self.assertIn('checkHostUpdate.setEnabled(false)', launcher)
        self.assertIn('checkHostUpdate.setEnabled(true)', launcher)
        self.assertIn('"lan-update-status.txt"', report)
        self.assertIn('## LAN update server', report)
        self.assertIn('IP.matcher(line)', report)
        self.assertIn('EMAIL.matcher(', report)
        self.assertIn('Files.readAllBytes(lanStatus.toPath())', report)
        self.assertNotIn('Files.readString(lanStatus.toPath()', report)

    def test_no_public_cleartext_exception_or_game_protocol_changes(self):
        source = WIRE.read_text()
        self.assertIn("new Socket()", source)
        self.assertIn('writer.write(("GET " + path + " HTTP/1.0', source)
        self.assertIn('Connection: close', source)
        self.assertNotIn("new URL(", source)
        self.assertNotIn("setInstanceFollowRedirects(true)", source)
        self.assertNotIn("usesCleartextTraffic", MANIFEST.read_text())
        self.assertIn("HALO_PORT_NETWORK_VERSION 54",
            (ROOT / "port/linux/include/halo_port_limits.h").read_text())
        for token in (
            "_distributed_message_commander_settings = 96",
            "_distributed_message_orbital_incoming = 100",
        ):
            self.assertIn(token,
                (ROOT / "port/linux/game/network_distributed.h").read_text())


if __name__ == "__main__":
    unittest.main()
