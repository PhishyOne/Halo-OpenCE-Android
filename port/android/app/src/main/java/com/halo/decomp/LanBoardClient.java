package com.halo.decomp;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;

/** User-initiated peer-to-peer push of one's *unverified* local career
 * card to another Halo phone, with bounded reply. Wi-Fi/hotspot only. */
final class LanBoardClient {
    private LanBoardClient() { }

    static List<LanBoardProtocol.Entry> sync(
            Context context, File root, String ip) throws IOException {
        return sync(context, root, ip, null);
    }

    static List<LanBoardProtocol.Entry> sync(Context context, File root, String ip,
                                             String expectedId) throws IOException {
        if (!LanShareSettings.career(context)) throw new IOException("Career sharing is disabled");
        byte[] address = LanUpdateProtocol.privateAddress(ip);
        if (address == null)
            throw new IOException("Enter a private hotspot host IP (10.x, "
                + "172.16–31.x or 192.168.x)");
        Inet4Address local = localInterface(address);
        if (local == null)
            throw new IOException("Connect to the same Wi-Fi/hotspot as the host.");
        LanBoardProtocol.Entry self = LanBoardIdentity.card(context, root);
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder nonce = new StringBuilder(16);
        for (byte b : bytes)
            nonce.append(Character.forDigit((b >> 4) & 15, 16))
                .append(Character.forDigit(b & 15, 16));
        String token = nonce.toString();
        List<LanBoardProtocol.Entry> received = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(local, 0));
            socket.connect(new InetSocketAddress(
                LanUpdateProtocol.asAddress(address), LanBoardProtocol.PORT), 2800);
            socket.setSoTimeout(3500);
            if (!LanShareSettings.career(context)) throw new IOException("Career sharing disabled");
            socket.getOutputStream().write(
                LanBoardProtocol.card(token, self).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            long deadline = System.nanoTime() + 4_000_000_000L;
            int count = LanBoardProtocol.readHeader(
                timedLine(socket, deadline), token);
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < count; i++) {
                LanBoardProtocol.Entry e = LanBoardProtocol.readRow(
                    timedLine(socket, deadline));
                if (!ids.add(e.id))
                    throw new IOException("Host repeated a leaderboard identity");
                received.add(e);
            }
            boolean hostRespondedWithSelf = false;
            for (LanBoardProtocol.Entry e : received)
                if (e.id.equals(self.id)) hostRespondedWithSelf = true;
            if (!hostRespondedWithSelf)
                throw new IOException("Host did not acknowledge this player");
            if (expectedId != null && !ids.contains(expectedId))
                throw new IOException("Expected nearby player did not respond");
        }
        if (!LanShareSettings.career(context)) throw new IOException("Career sharing disabled");
        return LanBoardStore.merge(root, self, received,
            System.currentTimeMillis());
    }

    private static String timedLine(Socket socket, long deadline) throws IOException {
        java.io.InputStream stream = socket.getInputStream();
        // Bound the whole reply, not just an individual read.
        java.io.InputStream timed = new java.io.InputStream() {
            @Override public int read() throws IOException {
                long left = deadline - System.nanoTime();
                if (left <= 0 || Thread.currentThread().isInterrupted())
                    throw new IOException("Leaderboard response timed out");
                socket.setSoTimeout((int)Math.max(1, left / 1_000_000L));
                return stream.read();
            }
        };
        return LanBoardProtocol.boundedLine(timed);
    }

    static Inet4Address localInterface(byte[] target) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (nic.isLoopback() || !nic.isUp() ||
                    !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                for (InterfaceAddress entry : nic.getInterfaceAddresses()) {
                    if (!(entry.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address local = (Inet4Address)entry.getAddress();
                    if (LanUpdateProtocol.privateAddress(local.getHostAddress()) == null ||
                        java.util.Arrays.equals(local.getAddress(), target)) continue;
                    int prefix = LanUpdateProtocol.usableHostPrefix(
                        nic.getName(), entry.getNetworkPrefixLength());
                    if (prefix >= 0 &&
                        LanUpdateProtocol.sameSubnet(local.getAddress(), target, prefix))
                        return local;
                }
            }
        } catch (SocketException ignored) { }
        return null;
    }
}
