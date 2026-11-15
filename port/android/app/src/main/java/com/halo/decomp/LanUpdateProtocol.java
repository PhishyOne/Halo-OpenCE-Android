package com.halo.decomp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Strict, Android-free LAN update framing, metadata and IPv4 policy.
 * No LAN hostname resolution, redirects, arbitrary paths or version guessing.
 * This protocol is only used by peers which already have the Halo app. */
final class LanUpdateProtocol {
    static final int PORT = 5162; // separate from Halo's 5150/5151 gameplay ports
    static final String META_PATH = "/halo/manifest";
    static final String APK_PATH = "/halo/apk";
    static final String MAGIC = "HALO-LAN-UPDATE-1";
    static final long MAX_APK_BYTES = 128L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 512;

    static final class Manifest {
        final String packageName;
        final int version;
        final long size;
        final String sha256;

        Manifest(String packageName, int version, long size, String sha256) {
            this.packageName = packageName;
            this.version = version;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private LanUpdateProtocol() { }

    static String encode(Manifest manifest) {
        return MAGIC + "\n" + manifest.packageName + "\n" + manifest.version +
            "\n" + manifest.size + "\n" + manifest.sha256 + "\n";
    }

    static Manifest parse(String text) throws IOException {
        if (text == null || text.getBytes(StandardCharsets.US_ASCII).length > MAX_MANIFEST_BYTES)
            throw new IOException("Bad Halo update manifest size");
        String[] lines = text.split("\n", -1);
        if (lines.length != 6 || !MAGIC.equals(lines[0]) || !lines[5].isEmpty() ||
            !lines[1].matches("[a-z][a-z0-9_.]{3,100}") ||
            !lines[2].matches("[1-9][0-9]{0,9}") ||
            !lines[3].matches("[1-9][0-9]{0,11}") ||
            !lines[4].matches("[0-9a-f]{64}"))
            throw new IOException("Unrecognized Halo LAN update");
        try {
            int version = Integer.parseInt(lines[2]);
            long size = Long.parseLong(lines[3]);
            if (version <= 0 || size < 1024 || size > MAX_APK_BYTES)
                throw new NumberFormatException("version or APK size");
            return new Manifest(lines[1], version, size, lines[4]);
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid Halo update version or size", invalid);
        }
    }

    /** Canonical dotted-quad, private IPv4 only; no DNS, carrier/CGNAT,
     * multicast, loopback or IPv6 addresses. */
    static byte[] privateAddress(String ip) {
        if (ip == null || !ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
            return null;
        String[] fields = ip.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (fields[i].length() > 1 && fields[i].charAt(0) == '0')
                return null;
            int n;
            try { n = Integer.parseInt(fields[i]); }
            catch (NumberFormatException failure) { return null; }
            if (n < 0 || n > 255)
                return null;
            b[i] = (byte)n;
        }
        int a = b[0] & 255, second = b[1] & 255;
        if (!(a == 10 || (a == 172 && second >= 16 && second <= 31) ||
            (a == 192 && second == 168)))
            return null;
        return b;
    }

    static boolean lanInterface(String name) {
        if (name == null)
            return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("wlan") || n.startsWith("swlan") ||
            n.startsWith("ap") || n.startsWith("softap") ||
            n.startsWith("eth") || n.startsWith("br") ||
            n.startsWith("rndis") || n.startsWith("usb");
    }

    /** Some Android hotspot implementations publish a /32 or no usable
     * interface prefix even when their peers are a private /24 LAN.
     * Bound to a local private IP and a recognized Wi-Fi/AP interface, a
     * conservative /24 peer check allows those devices without exposing
     * a wildcard internet-facing listener. Carrier/VPN and ordinary
     * ethernet interfaces still fail closed on invalid prefixes. */
    static int usableHostPrefix(String interfaceName, int prefix) {
        if (!lanInterface(interfaceName))
            return -1;
        if (prefix >= 16 && prefix <= 30)
            return prefix;
        String name = interfaceName.toLowerCase(Locale.ROOT);
        boolean hotspot = name.startsWith("ap") ||
            name.startsWith("softap") || name.startsWith("swlan") ||
            name.startsWith("wlan") || name.startsWith("br") ||
            name.startsWith("rndis") || name.startsWith("usb");
        return hotspot ? 24 : -1;
    }

    /** Manual guest diagnostics distinguish a host's unavailable update
     * socket from a failed APK verifier, without exposing stack traces,
     * credentials, or peer-supplied untrusted error text. */
    static String connectionHelp(IOException cause) {
        String title;
        if ("Halo update host is busy".equals(cause.getMessage())) {
            title = "The host is busy sending Halo updates to other players.";
        } else if ("Halo hotspot subnet rejected this phone".equals(cause.getMessage())) {
            title = "The host rejected this phone as outside its hotspot subnet.";
        } else if (cause instanceof java.net.ConnectException) {
            title = "The host's Halo update port is closed.";
        } else if (cause instanceof java.net.SocketTimeoutException) {
            title = "The host did not answer before the update check timed out.";
        } else if (cause instanceof java.net.NoRouteToHostException) {
            title = "This phone cannot reach the host's network address.";
        } else {
            title = "The host's update service did not return a valid response.";
        }
        return title + "\n\nOn the host phone: choose LAN → Host, enable " +
            "'Share My Halo Version', and press Play. Leave the Halo game " +
            "running while friends enter the HOST IP and check for the " +
            "update. The update service uses TCP port 5162. " +
            "If gameplay works but this still fails, save the Halo " +
            "Diagnostic Report from the host launcher and check the " +
            "'LAN update server' section. Do not uninstall Halo.";
    }

    static boolean sameSubnet(byte[] local, byte[] peer, int prefix) {
        if (local == null || peer == null || local.length != 4 || peer.length != 4 ||
            prefix < 16 || prefix > 30 ||
            privateAddress(asAddress(local)) == null ||
            privateAddress(asAddress(peer)) == null)
            return false;
        for (int i = 0; i < 4; i++) {
            int bits = Math.min(8, Math.max(0, prefix - i * 8));
            int mask = bits == 0 ? 0 : 255 << (8 - bits) & 255;
            if (((local[i] ^ peer[i]) & mask) != 0)
                return false;
        }
        return true;
    }

    static String asAddress(byte[] b) {
        return (b[0] & 255) + "." + (b[1] & 255) + "." +
            (b[2] & 255) + "." + (b[3] & 255);
    }
}
