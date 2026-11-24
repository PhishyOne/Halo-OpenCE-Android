package com.halo.decomp;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit refresh: scan local subnet for opted-in Halo peers and
 * exchange tiny cards with each one (no dedicated host or cloud).
 * A manual IP fallback covers Android hotspots blocking UDP broadcast.
 * No automatic background scan, no gameplay packet changes. */
final class LanBoardNearby {
    static final int MAX_DISCOVERED = 16;
    static final int SCAN_MS = 1400;
    static final class Result {
        final List<LanBoardProtocol.Entry> board;
        final int peersReached;
        Result(List<LanBoardProtocol.Entry> board, int peers) {
            this.board = board;
            this.peersReached = peers;
        }
    }
    private LanBoardNearby() { }

    static Result refresh(Context context, File root, String manual)
            throws IOException {
        Set<String> targets = discover();
        if (manual != null && !manual.trim().isEmpty() &&
            LanUpdateProtocol.privateAddress(manual.trim()) != null)
            targets.add(manual.trim());
        int reached = 0;
        IOException last = null;
        List<LanBoardProtocol.Entry> board = null;
        for (String target : targets) {
            if (reached >= LanBoardProtocol.MAX_PEERS ||
                reached >= MAX_DISCOVERED) break;
            try {
                board = LanBoardClient.sync(context,root,target);
                reached++;
            } catch (IOException unavailable) {
                last = unavailable;
                // One stale peer must not discard usable standings.
            }
        }
        if (reached == 0 && last != null &&
            manual != null && !manual.isEmpty()) throw last;
        LanBoardProtocol.Entry local = LanBoardIdentity.card(context,root);
        board = LanBoardStore.merge(root,local,null,
            System.currentTimeMillis());
        return new Result(board,reached);
    }

    static Set<String> discover() {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> ownIps = new HashSet<>();
        List<Inet4Address> sources = new ArrayList<>();
        List<byte[]> destinations = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets =
                NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface nic = nets.nextElement();
                if (!nic.isUp() || nic.isLoopback() ||
                    !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address ip=(Inet4Address)ia.getAddress();
                    int prefix=LanUpdateProtocol.usableHostPrefix(
                        nic.getName(),ia.getNetworkPrefixLength());
                    if (prefix < 0 ||
                        LanUpdateProtocol.privateAddress(ip.getHostAddress()) == null)
                        continue;
                    byte[] broadcast = LanDiscoveryProtocol.broadcast(
                        ip.getAddress(),prefix);
                    if (broadcast == null) continue;
                    sources.add(ip);
                    destinations.add(broadcast);
                    ownIps.add(ip.getHostAddress());
                }
            }
        } catch (Exception unavailable) {
            return targets;
        }
        byte[] rand = new byte[8];
        new SecureRandom().nextBytes(rand);
        StringBuilder token = new StringBuilder(16);
        for (byte b : rand)
            token.append(Character.forDigit((b >> 4)&15,16))
                .append(Character.forDigit(b&15,16));
        byte[] request = LanBoardDiscovery.probe(token.toString());
        for (int i = 0; i < sources.size() && targets.size() < MAX_DISCOVERED; i++) {
            try (DatagramSocket socket = new DatagramSocket(
                    new InetSocketAddress(sources.get(i),0))) {
                socket.setBroadcast(true);
                socket.setSoTimeout(250);
                socket.send(new DatagramPacket(request,request.length,
                    java.net.InetAddress.getByAddress(destinations.get(i)),
                    LanBoardDiscovery.PORT));
                long end = System.nanoTime() + SCAN_MS * 1_000_000L;
                byte[] buffer = new byte[LanBoardDiscovery.MAX_PACKET+1];
                while (System.nanoTime() < end &&
                    targets.size() < MAX_DISCOVERED) {
                    try {
                        DatagramPacket response =
                            new DatagramPacket(buffer,buffer.length);
                        socket.receive(response);
                        String ip = response.getAddress().getHostAddress();
                        if (!ownIps.contains(ip) &&
                            LanUpdateProtocol.privateAddress(ip) != null &&
                            LanBoardDiscovery.validReply(response.getData(),
                                response.getLength(),token.toString()))
                            targets.add(ip);
                    } catch (SocketTimeoutException idle) {
                        // Continue until scan deadline.
                    }
                }
            } catch (IOException ignored) {
                // The manually entered host IP remains available.
            }
        }
        return targets;
    }
}
