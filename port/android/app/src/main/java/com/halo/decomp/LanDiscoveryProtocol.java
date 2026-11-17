package com.halo.decomp;

import java.nio.charset.StandardCharsets;

/** Minimal LAN-only, nonce-bound discovery frames. A reply is not authentication. */
final class LanDiscoveryProtocol {
    static final int PORT = 5163; // independent of gameplay 5150/5151 and update 5162
    static final int MAX_PACKET = 96;
    private static final String PROBE = "HALO-LAN-FIND-1 ";
    private static final String REPLY = "HALO-LAN-HOST-1 ";

    private LanDiscoveryProtocol() { }

    static boolean validNonce(String nonce) {
        return nonce != null && nonce.matches("[0-9a-f]{16}");
    }

    static byte[] probe(String nonce) {
        if (!validNonce(nonce)) throw new IllegalArgumentException("Bad discovery nonce");
        return (PROBE + nonce).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] reply(String nonce) {
        if (!validNonce(nonce)) throw new IllegalArgumentException("Bad discovery nonce");
        return (REPLY + nonce).getBytes(StandardCharsets.US_ASCII);
    }

    private static String packet(byte[] data, int length, String prefix) {
        if (data == null || length != prefix.length() + 16 ||
            length > MAX_PACKET || length > data.length)
            return null;
        String content = new String(data, 0, length, StandardCharsets.US_ASCII);
        String nonce = content.startsWith(prefix) ? content.substring(prefix.length()) : null;
        return validNonce(nonce) ? nonce : null;
    }

    static String readProbe(byte[] packet, int length) {
        return packet(packet, length, PROBE);
    }

    static boolean validReply(byte[] packet, int length, String expectedNonce) {
        return validNonce(expectedNonce) &&
            expectedNonce.equals(packet(packet, length, REPLY));
    }

    /** Directed broadcast, even when an Android hotspot advertises /32. */
    static byte[] broadcast(byte[] ip, int prefix) {
        if (ip == null || ip.length != 4 || prefix < 16 || prefix > 30)
            return null;
        byte[] result = ip.clone();
        for (int i = 0; i < 4; i++) {
            int known = Math.min(8, Math.max(0, prefix - i * 8));
            int mask = known == 0 ? 0 : (0xff << (8 - known)) & 0xff;
            result[i] = (byte)((ip[i] & mask) | (~mask & 0xff));
        }
        return result;
    }

    static boolean sameLan(byte[] local, byte[] peer, int prefix) {
        return LanUpdateProtocol.sameSubnet(local, peer, prefix);
    }
}
