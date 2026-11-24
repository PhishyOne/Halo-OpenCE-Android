package com.halo.decomp;

import java.nio.charset.StandardCharsets;

/** Plain, nonce-bound discovery. Any opted-in phone with Halo open
 * may respond, not just the selected match host. No user data in UDP. */
final class LanBoardDiscovery {
    static final int PORT = 5165;
    static final int MAX_PACKET = 80;
    private static final String PROBE = "HALO-BOARD-FIND-1|";
    private static final String REPLY = "HALO-BOARD-HERE-1|";

    private LanBoardDiscovery() { }

    static byte[] probe(String nonce) { return frame(PROBE, nonce); }
    static byte[] reply(String nonce) { return frame(REPLY, nonce); }

    private static byte[] frame(String prefix, String nonce) {
        if (!LanBoardProtocol.nonceValid(nonce))
            throw new IllegalArgumentException("Invalid board scan nonce");
        return (prefix + nonce).getBytes(StandardCharsets.US_ASCII);
    }

    static String readProbe(byte[] bytes, int length) {
        return parse(bytes, length, PROBE);
    }

    static boolean validReply(byte[] bytes, int length, String expected) {
        return LanBoardProtocol.nonceValid(expected) &&
            expected.equals(parse(bytes, length, REPLY));
    }

    private static String parse(byte[] bytes, int length, String prefix) {
        if (bytes == null || length != prefix.length() + 16 ||
            length > MAX_PACKET || length > bytes.length) return null;
        String packet = new String(bytes, 0, length, StandardCharsets.US_ASCII);
        String nonce = packet.startsWith(prefix) ?
            packet.substring(prefix.length()) : null;
        return LanBoardProtocol.nonceValid(nonce) ? nonce : null;
    }
}
