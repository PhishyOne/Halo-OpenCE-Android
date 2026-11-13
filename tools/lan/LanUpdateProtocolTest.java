package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Pure Java, no Android SDK or device required. Exercise the real wire
 * response, private-subnet guard and fixed-route parser, not a mock server. */
public final class LanUpdateProtocolTest {
    private static int assertions;
    private static void check(boolean good, String why) {
        assertions++;
        if (!good) throw new AssertionError(why);
    }
    private static void fails(String name, RunnableIo call) {
        assertions++;
        try { call.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Expected refusal: " + name);
    }
    interface RunnableIo { void run() throws IOException; }

    private static String sha(byte[] bytes) throws Exception {
        byte[] sum = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte b : sum) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private static byte[] request(String text, File apk,
            LanUpdateProtocol.Manifest m) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LanUpdateSocket.serveHttp(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)),
            output, apk, m);
        return output.toByteArray();
    }

    private static byte[] body(byte[] wire) throws IOException {
        byte[] term = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < wire.length - term.length; i++) {
            boolean match = true;
            for (int j = 0; j < term.length; j++)
                if (wire[i+j] != term[j]) match = false;
            if (match) return Arrays.copyOfRange(wire, i + term.length, wire.length);
        }
        throw new IOException("Missing headers");
    }

    private static void addresses() {
        for (String host : new String[]{
            "192.168.43.1", "10.28.111.42", "172.20.10.1", "172.31.255.254"})
            check(LanUpdateProtocol.privateAddress(host) != null, "private: " + host);
        for (String host : new String[]{
            "127.0.0.1", "8.8.8.8", "100.64.0.1", "169.254.1.1",
            "172.32.1.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "192.168.001.1", "10.1.1", "10.1.1.256", "10.1.1.1\r\n",
            "localhost", "192.168.0.1/24", "2001:db8::1", ""})
            check(LanUpdateProtocol.privateAddress(host) == null, "reject: " + host);
        byte[] a = LanUpdateProtocol.privateAddress("192.168.43.1");
        byte[] b = LanUpdateProtocol.privateAddress("192.168.43.14");
        byte[] c = LanUpdateProtocol.privateAddress("192.168.44.14");
        check(LanUpdateProtocol.sameSubnet(a, b, 24), "same subnet");
        check(!LanUpdateProtocol.sameSubnet(a, c, 24), "adjacent subnet");
        check(!LanUpdateProtocol.sameSubnet(a, b, 0), "reject wildcard");
        check(!LanUpdateProtocol.sameSubnet(a, b, 8), "reject very broad prefix");
        check(!LanUpdateProtocol.sameSubnet(a, b, 32), "reject host-only prefix");
        check(!LanUpdateProtocol.sameSubnet(a, null, 24), "reject unknown peer");
        check(!LanUpdateProtocol.lanInterface("rmnet_data0"), "no carrier listener");
        check(!LanUpdateProtocol.lanInterface("tun0"), "no VPN listener");
        check(LanUpdateProtocol.lanInterface("ap0"), "hotspot");
        check(LanUpdateProtocol.lanInterface("wlan0"), "wifi");
        check(LanUpdateProtocol.lanInterface("swlan0"), "wifi 2");
        check(LanUpdateProtocol.lanInterface("eth0"), "ethernet");
    }

    private static void messages() throws Exception {
        byte[] apkBytes = new byte[8192];
        for (int i = 0; i < apkBytes.length; i++)
            apkBytes[i] = (byte)(i * 31 + 7);
        LanUpdateProtocol.Manifest offer = new LanUpdateProtocol.Manifest(
            "com.halo.decomp.experiment", 23, apkBytes.length, sha(apkBytes));
        String encoded = LanUpdateProtocol.encode(offer);
        LanUpdateProtocol.Manifest parsed = LanUpdateProtocol.parse(encoded);
        check(parsed.version == 23, "version");
        check(parsed.size == apkBytes.length, "file size");
        check(parsed.sha256.equals(offer.sha256), "sha");
        for (String bad : new String[] {
            encoded.replace("HALO-LAN-UPDATE-1", "WRONG-PROTOCOL"),
            encoded.replace("\n23\n", "\n-1\n"),
            encoded.replace("\n23\n", "\n2147483648\n"),
            encoded.replace("\n8192\n", "\n999999999999\n"),
            encoded.replace(offer.sha256, "bogus"),
            encoded.replace("com.halo.decomp.experiment", "other\r\nHost: attacker"),
            encoded + "\n",
            "x".repeat(800),
        })
            fails("malformed manifest", () -> LanUpdateProtocol.parse(bad));

        File verified = File.createTempFile("halo-lan-verified-", ".apk");
        try {
            final long[] bytesSeen = { 0 };
            LanUpdateSocket.transferVerified(new ByteArrayInputStream(apkBytes),
                apkBytes.length, offer, verified,
                (sent, total) -> bytesSeen[0] = sent);
            check(bytesSeen[0] == apkBytes.length, "stream progress reaches end");
            check(Arrays.equals(Files.readAllBytes(verified.toPath()), apkBytes),
                "verified APK stream bytes");
            byte[] tampered = apkBytes.clone();
            tampered[1024] ^= 0x20;
            fails("same size, invalid SHA", () ->
                LanUpdateSocket.transferVerified(new ByteArrayInputStream(tampered),
                    tampered.length, offer, verified, null));
            fails("truncated transfer", () ->
                LanUpdateSocket.transferVerified(new ByteArrayInputStream(apkBytes),
                    apkBytes.length + 4, offer, verified, null));
            fails("wrong response length", () ->
                LanUpdateSocket.transferVerified(new ByteArrayInputStream(apkBytes),
                    apkBytes.length + 1, offer, verified, null));
        } finally {
            Files.deleteIfExists(verified.toPath());
        }

        File apk = File.createTempFile("halo-lan-update-test-", ".apk");
        try {
            try (FileOutputStream out = new FileOutputStream(apk)) { out.write(apkBytes); }
            byte[] metadata = request("GET /halo/manifest HTTP/1.0\r\nHost: 192.168.43.1\r\n\r\n", apk, offer);
            check(new String(metadata, StandardCharsets.US_ASCII).startsWith(
                "HTTP/1.0 200 OK\r\n"), "manifest status");
            check(Arrays.equals(body(metadata), encoded.getBytes(StandardCharsets.US_ASCII)),
                "manifest bytes");

            byte[] download = request("GET /halo/apk HTTP/1.0\r\n\r\n", apk, offer);
            check(new String(download, 0, 15, StandardCharsets.US_ASCII)
                .startsWith("HTTP/1.0 200"), "APK status");
            check(Arrays.equals(body(download), apkBytes), "exact APK bytes");

            for (String bad : new String[] {
                "GET /../../config.toml HTTP/1.0\r\n\r\n",
                "POST /halo/apk HTTP/1.0\r\n\r\n",
                "GET /halo/apk HTTP/2.0\r\n\r\n",
                "GET /halo/apk?path=../../ HTTP/1.0\r\n\r\n"})
                check(new String(request(bad, apk, offer), StandardCharsets.US_ASCII)
                    .startsWith("HTTP/1.0 404"), "reject arbitrary routes");

            fails("bare LF", () ->
                request("GET /halo/apk HTTP/1.0\n\n", apk, offer));
            fails("unterminated headers", () ->
                request("GET /halo/apk HTTP/1.0\r\n", apk, offer));
            fails("overlong header", () ->
                request("GET /halo/apk HTTP/1.0\r\nX:" + "a".repeat(530) + "\r\n\r\n",
                    apk, offer));
            StringBuilder tooMany = new StringBuilder("GET /halo/apk HTTP/1.0\r\n");
            for (int n = 0; n < 17; n++) tooMany.append("X: ok\r\n");
            tooMany.append("\r\n");
            fails("too many headers", () -> request(tooMany.toString(), apk, offer));
            apk.delete();
            fails("APK changed or missing", () ->
                request("GET /halo/apk HTTP/1.0\r\n\r\n", apk, offer));
        } finally {
            Files.deleteIfExists(apk.toPath());
        }
    }

    public static void main(String[] args) throws Exception {
        addresses();
        messages();
        System.out.println("PASS " + assertions +
            " Halo LAN update policy, fixed routes, metadata, APK transfer checks");
    }
}
