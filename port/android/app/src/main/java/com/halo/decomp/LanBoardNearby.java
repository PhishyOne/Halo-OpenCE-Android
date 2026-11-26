package com.halo.decomp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;

/** Explicit stat sync and separate read-only presence probes. Only
 * existing Halo discovery, known peers and Wi-Fi gateways are targeted.
 * No subnet sweep, cloud service or gameplay packet changes. */
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
        return refresh(context, root, manual, Collections.emptyList());
    }

    static Result refresh(Context context, File root, String manual,
                          Collection<String> live) throws IOException {
        Set<String> targets = new LinkedHashSet<>();
        if (manual != null && !manual.trim().isEmpty() &&
            LanUpdateProtocol.privateAddress(manual.trim()) != null)
            targets.add(manual.trim());
        for (String ip : live) if (targets.size() < MAX_DISCOVERED &&
            LanUpdateProtocol.privateAddress(ip) != null) targets.add(ip);
        for (String ip : discover(context)) if (targets.size() < MAX_DISCOVERED) targets.add(ip);
        int reached = 0, attempted = 0;
        IOException last = null;
        List<LanBoardProtocol.Entry> board = null;
        for (String target : targets) {
            if (attempted++ >= MAX_DISCOVERED) break;
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

    static Set<String> discover(Context context) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> ownIps = new HashSet<>();
        List<Inet4Address> sources = new ArrayList<>();
        List<byte[]> destinations = new ArrayList<>();
        List<Integer> prefixes = new ArrayList<>();
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
                    ownIps.add(ip.getHostAddress());
                    if (sources.size() >= 3) continue;
                    sources.add(ip);
                    destinations.add(broadcast);
                    prefixes.add(prefix);
                }
            }
        } catch (Exception unavailable) {
            return targets;
        }
        String token = nonce();
        byte[] request = LanBoardDiscovery.probe(token);
        List<String> gateways = gateways(context);
        for (int i = 0; i < sources.size() && targets.size() < MAX_DISCOVERED; i++) {
            try (DatagramSocket socket = new DatagramSocket(
                    new InetSocketAddress(sources.get(i),0))) {
                socket.setBroadcast(true);
                socket.setSoTimeout(250);
                socket.send(new DatagramPacket(request,request.length,
                    InetAddress.getByAddress(destinations.get(i)),
                    LanBoardDiscovery.PORT));
                // Some Android hotspots suppress directed broadcasts. Also
                // send a discovery probe to the known private Wi-Fi gateway.
                for (String gateway : gateways) {
                    byte[] address = LanUpdateProtocol.privateAddress(gateway);
                    if (address != null && !ownIps.contains(gateway) &&
                        LanUpdateProtocol.sameSubnet(sources.get(i).getAddress(),
                            address, prefixes.get(i))) {
                        try {
                            socket.send(new DatagramPacket(request, request.length,
                                InetAddress.getByAddress(address), LanBoardDiscovery.PORT));
                        } catch (IOException ignored) { }
                    }
                }
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
                            LanUpdateProtocol.sameSubnet(sources.get(i).getAddress(),
                                response.getAddress().getAddress(), prefixes.get(i)) &&
                            LanBoardDiscovery.validReply(response.getData(),
                                response.getLength(),token))
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

    private static String nonce() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(16);
        for (byte b : bytes)
            result.append(Character.forDigit((b >> 4)&15,16))
                .append(Character.forDigit(b&15,16));
        return result.toString();
    }

    private static List<String> gateways(Context context) {
        Set<String> found = new LinkedHashSet<>();
        try {
            ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                for (RouteInfo route : properties.getRoutes()) {
                    InetAddress gateway = route.getGateway();
                    if (route.isDefaultRoute() && gateway instanceof Inet4Address &&
                        LanUpdateProtocol.privateAddress(gateway.getHostAddress()) != null &&
                        found.size() < 4)
                        found.add(gateway.getHostAddress());
                }
            }
        } catch (RuntimeException ignored) { }
        return new ArrayList<>(found);
    }

    private static LanBoardPresence.Peer ping(String ip) throws IOException {
        byte[] address = LanUpdateProtocol.privateAddress(ip);
        if (address == null) throw new IOException("Not a private peer");
        Inet4Address local = LanBoardClient.localInterface(address);
        if (local == null) throw new IOException("Peer not on local Wi-Fi");
        String challenge = nonce();
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(local, 0));
            socket.connect(new InetSocketAddress(LanUpdateProtocol.asAddress(address),
                LanBoardProtocol.PORT), 700);
            return LanBoardPresence.exchange(socket, challenge, ip);
        }
    }

    /** Presence checks never send the caller's career, merge the saved
     * board or change last-sync dates. Named peers answered TCP now; an
     * older discovery-only response is explicitly not confirmed sync-ready. */
    static List<LanBoardPresence.Peer> presence(Context context, String self,
            String manual, Collection<String> previous) {
        Set<String> discovered = discover(context);
        Set<String> targets = new LinkedHashSet<>(discovered);
        for (String ip : previous) if (targets.size() < MAX_DISCOVERED &&
            LanUpdateProtocol.privateAddress(ip) != null) targets.add(ip);
        if (targets.size() < MAX_DISCOVERED && LanUpdateProtocol.privateAddress(manual) != null)
            targets.add(manual);
        for (String ip : gateways(context)) if (targets.size() < MAX_DISCOVERED) targets.add(ip);
        if (targets.isEmpty()) return Collections.emptyList();
        List<String> ips = new ArrayList<>(targets);
        List<Callable<LanBoardPresence.Peer>> tasks = new ArrayList<>();
        for (String ip : ips) tasks.add(() -> {
            try {
                LanBoardPresence.Peer peer = ping(ip);
                return peer.id.equals(self) ? null : peer;
            } catch (IOException | RuntimeException unavailable) {
                return discovered.contains(ip) ? LanBoardPresence.Peer.legacy(ip) : null;
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(4, tasks.size()));
        List<LanBoardPresence.Peer> result = new ArrayList<>();
        try {
            List<Future<LanBoardPresence.Peer>> answers =
                workers.invokeAll(tasks, 4500, TimeUnit.MILLISECONDS);
            for (int i = 0; i < answers.size(); i++) {
                try {
                    LanBoardPresence.Peer peer = answers.get(i).get();
                    if (peer != null) result.add(peer);
                } catch (java.util.concurrent.ExecutionException |
                         java.util.concurrent.CancellationException unavailable) {
                    if (discovered.contains(ips.get(i)))
                        result.add(LanBoardPresence.Peer.legacy(ips.get(i)));
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownNow();
        }
        return result;
    }
}
