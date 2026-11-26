package com.halo.decomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicitly opted-in peer-phone LAN leaderboard on TCP 5164.
 * Any participating device may serve cards while in launcher or game.
 * Receives self-reported cards only, NOT authoritative skill results.
 * Only listens on recognized private hotspot/Wi-Fi interfaces and
 * rejects out-of-subnet peers. Never writes to Nomatoka/Forge server. */
final class LanBoardServer {
    private static final String TAG = "HaloLanBoard";
    private final Context context;
    private final Map<String, ServerSocket> bound = new LinkedHashMap<>();
    private final boolean gameOpen;
    private volatile boolean stopped;
    private Thread maintenance;
    private LanBoardBeacon beacon;

    private LanBoardServer(Context context) {
        this.context = context.getApplicationContext();
        gameOpen = context instanceof HaloActivity;
    }

    static LanBoardServer start(Context context) {
        LanBoardServer server = new LanBoardServer(context);
        server.beacon = LanBoardBeacon.start(context);
        server.maintenance = new Thread(server::open, "Halo-LAN-board-start");
        server.maintenance.setDaemon(true);
        server.maintenance.start();
        return server;
    }

    void stop() {
        stopped = true;
        if (beacon != null) {
            beacon.stop();
            beacon = null;
        }
        if (maintenance != null) maintenance.interrupt();
        synchronized (bound) {
            for (ServerSocket listener : bound.values())
                try { listener.close(); } catch (IOException ignored) { }
            bound.clear();
        }
    }

    /** Refresh only local interface bindings, not remote clients. Allows
     * a phone to join a hotspot after opening Halo, without relaunching. */
    private void open() {
        while (!stopped) {
            try {
                Map<String, Inet4Address> current = new LinkedHashMap<>();
                Map<String, Integer> prefixes = new LinkedHashMap<>();
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (!stopped && interfaces != null && interfaces.hasMoreElements() &&
                       current.size() < 6) {
                    NetworkInterface nic = interfaces.nextElement();
                    if (!nic.isUp() || nic.isLoopback() ||
                        !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                    for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                        if (stopped || current.size() >= 6) break;
                        if (!(address.getAddress() instanceof Inet4Address)) continue;
                        Inet4Address local = (Inet4Address)address.getAddress();
                        int prefix = LanUpdateProtocol.usableHostPrefix(
                            nic.getName(), address.getNetworkPrefixLength());
                        if (prefix < 0 ||
                            LanUpdateProtocol.privateAddress(local.getHostAddress()) == null) continue;
                        String key = local.getHostAddress() + "/" + prefix;
                        current.put(key, local);
                        prefixes.put(key, prefix);
                    }
                }
                synchronized (bound) {
                    if (stopped) return;
                    java.util.Iterator<Map.Entry<String, ServerSocket>> old = bound.entrySet().iterator();
                    while (old.hasNext()) {
                        Map.Entry<String, ServerSocket> e = old.next();
                        if (!current.containsKey(e.getKey()) || e.getValue().isClosed()) {
                            try { e.getValue().close(); } catch (IOException ignored) { }
                            old.remove();
                        }
                    }
                    for (Map.Entry<String, Inet4Address> address : current.entrySet()) {
                        if (stopped || bound.containsKey(address.getKey())) continue;
                        Inet4Address local = address.getValue();
                        int prefix = prefixes.get(address.getKey());
                        ServerSocket listener = null;
                        try {
                            listener = new ServerSocket(LanBoardProtocol.PORT, 8, local);
                            listener.setSoTimeout(1000);
                            final ServerSocket accepted = listener;
                            bound.put(address.getKey(), listener);
                            Thread worker = new Thread(
                                () -> accept(accepted, local.getAddress(), prefix),
                                "Halo-LAN-board-listener");
                            worker.setDaemon(true);
                            worker.start();
                            Log.i(TAG, "Opt-in local standings ready on " +
                                local.getHostAddress() + ":" + LanBoardProtocol.PORT);
                        } catch (IOException noPort) {
                            if (listener != null)
                                try { listener.close(); } catch (IOException ignored) { }
                        }
                    }
                }
            } catch (Exception unavailable) {
                if (!stopped) Log.i(TAG, "Local board interface temporarily unavailable");
            }
            if (!stopped)
                try { Thread.sleep(3000); }
                catch (InterruptedException done) { return; }
        }
    }

    private void accept(ServerSocket listener, byte[] local, int prefix) {
        while (!stopped && !listener.isClosed()) {
            try {
                Socket client = listener.accept();
                try (Socket socket = client) {
                    socket.setSoTimeout(2500);
                    if (!(socket.getInetAddress() instanceof Inet4Address)) continue;
                    String peer = socket.getInetAddress().getHostAddress();
                    if (LanUpdateProtocol.privateAddress(peer) == null ||
                        java.util.Arrays.equals(local, socket.getInetAddress().getAddress()) ||
                        !LanUpdateProtocol.sameSubnet(local,
                            socket.getInetAddress().getAddress(), prefix))
                        continue;
                    handle(socket);
                }
            } catch (SocketTimeoutException idle) {
                // Recheck stop flag, no thread or socket growth.
            } catch (IOException failed) {
                if (!stopped && !listener.isClosed())
                    Log.i(TAG, "LAN board connection ended: " +
                        failed.getClass().getSimpleName());
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        String request = LanBoardProtocol.boundedLine(socket.getInputStream());
        if (stopped) return;
        String ping = LanBoardPresence.readQuery(request);
        if (ping != null) {
            // A live presence reply uses identity only, no career read,
            // LanBoardStore merge, stats transmission or sync timestamp.
            String reply = LanBoardPresence.answer(ping, LanBoardIdentity.id(context),
                LanBoardIdentity.name(context), gameOpen);
            socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return;
        }
        String[] parts = request.split("\\|", -1);
        if (parts.length != 12 || !LanBoardProtocol.CARD.equals(parts[0]) ||
            !LanBoardProtocol.nonceValid(parts[1])) return;
        String nonce = parts[1];
        LanBoardProtocol.Entry offered = LanBoardProtocol.readCard(request, nonce);
        File root = context.getExternalFilesDir(null);
        LanBoardProtocol.Entry local = LanBoardIdentity.card(context, root);
        if (offered.id.equals(local.id)) return; // Never overwrite host identity.
        List<LanBoardProtocol.Entry> incoming = new ArrayList<>();
        incoming.add(offered.seen(System.currentTimeMillis()));
        List<LanBoardProtocol.Entry> board = LanBoardStore.merge(
            root, local, incoming, System.currentTimeMillis());
        StringBuilder reply = new StringBuilder(LanBoardProtocol.header(nonce, board.size()));
        for (LanBoardProtocol.Entry e : board)
            reply.append(LanBoardProtocol.row(e));
        if (reply.length() > LanBoardProtocol.MAX_STORED_BYTES)
            throw new IOException("Host leaderboard response exceeded quota");
        socket.getOutputStream().write(reply.toString()
            .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
}
