package com.halo.decomp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exact wire-format, nonce and private subnet preflight. */
public final class LanDiscoveryProtocolTest {
    private static int checked;
    private static void check(boolean ok, String name) {
        checked++;
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        String token = "0123456789abcdef";
        String other = "0123456789abcdee";
        byte[] query = LanDiscoveryProtocol.probe(token);
        byte[] response = LanDiscoveryProtocol.reply(token);
        check(token.equals(LanDiscoveryProtocol.readProbe(query, query.length)),
            "exact discovery probe");
        check(LanDiscoveryProtocol.validReply(response, response.length, token),
            "correct nonce reply");
        check(!LanDiscoveryProtocol.validReply(response, response.length, other),
            "replayed nonce rejected");
        check(LanDiscoveryProtocol.readProbe(response, response.length) == null,
            "reply not accepted as probe");
        check(!LanDiscoveryProtocol.validReply(query, query.length, token),
            "probe not accepted as reply");
        check(LanDiscoveryProtocol.readProbe(query, query.length - 1) == null,
            "truncation rejected");
        byte[] garbage = Arrays.copyOf(query, query.length + 1);
        check(LanDiscoveryProtocol.readProbe(garbage, garbage.length) == null,
            "trailing data rejected");
        byte[] malformed = query.clone();
        malformed[0] = (byte)0xff;
        check(LanDiscoveryProtocol.readProbe(malformed, malformed.length) == null,
            "invalid ASCII rejected");
        check(!LanDiscoveryProtocol.validReply(response, -1, token),
            "negative length rejected");
        check(!LanDiscoveryProtocol.validReply(response, 200, token),
            "oversized length rejected");
        for (String invalid : new String[] { "", "123", "0123456789abcdef0",
            "ABCDEF0123456789", "not_a_nonce_0001", token + "\n" }) {
            check(!LanDiscoveryProtocol.validNonce(invalid),
                "invalid nonce " + invalid);
        }

        byte[] local = LanUpdateProtocol.privateAddress("192.168.43.77");
        byte[] peer = LanUpdateProtocol.privateAddress("192.168.43.1");
        byte[] otherSubnet = LanUpdateProtocol.privateAddress("192.168.44.1");
        check(LanDiscoveryProtocol.sameLan(local, peer, 24), "same wifi prefix");
        check(!LanDiscoveryProtocol.sameLan(local, otherSubnet, 24),
            "separate wifi prefix rejected");
        check(!LanDiscoveryProtocol.sameLan(local, peer, 8),
            "carrier/wide network prefix rejected");
        check(!LanDiscoveryProtocol.sameLan(local,
            LanUpdateProtocol.privateAddress("10.0.0.1"), 24),
            "foreign peer rejected");
        byte[] broadcast = LanDiscoveryProtocol.broadcast(local, 24);
        check("192.168.43.255".equals(LanUpdateProtocol.asAddress(broadcast)),
            "directed broadcast");
        check("10.28.255.255".equals(LanUpdateProtocol.asAddress(
            LanDiscoveryProtocol.broadcast(
                LanUpdateProtocol.privateAddress("10.28.4.5"), 16))),
            "16-bit broadcast");
        check("172.20.10.7".equals(LanUpdateProtocol.asAddress(
            LanDiscoveryProtocol.broadcast(
                LanUpdateProtocol.privateAddress("172.20.10.6"), 30))),
            "30-bit broadcast");
        check(LanDiscoveryProtocol.broadcast(local, 32) == null,
            "host-only prefix rejected");
        check(LanDiscoveryProtocol.broadcast(local, 0) == null,
            "wildcard prefix rejected");
        check(LanDiscoveryProtocol.broadcast(null, 24) == null,
            "missing address rejected");
        check(LanDiscoveryProtocol.PORT != LanUpdateProtocol.PORT,
            "not sharing update TCP port");
        System.out.println("PASS LanDiscoveryProtocolTest: " + checked + " checks");
    }
}
