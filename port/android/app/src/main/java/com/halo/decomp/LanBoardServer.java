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

/** Explicitly opted-in peer-phone LAN leaderboard on TCP 5164.
 * Any participating device may serve cards while in launcher or game.
 * Receives self-reported cards only, NOT authoritative skill results.
 * Only listens on recognized private hotspot/Wi-Fi interfaces and
 * rejects out-of-subnet peers. Never writes to Nomatoka/Forge server. */
final class LanBoardServer {
    private static final String TAG = "HaloLanBoard";
    private final Context context;
    private final List<ServerSocket> bound = new ArrayList<>();
    private volatile boolean stopped;
    private LanBoardBeacon beacon;

    private LanBoardServer(Context context) {
        this.context = context.getApplicationContext();
    }

    static LanBoardServer start(Context context) {
        LanBoardServer server = new LanBoardServer(context);
        server.beacon = LanBoardBeacon.start();
        Thread thread = new Thread(server::open, "Halo-LAN-board-start");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    void stop() {
        stopped = true;
        if (beacon != null) {
            beacon.stop();
            beacon = null;
        }
        synchronized (bound) {
            for (ServerSocket listener : bound)
                try { listener.close(); } catch (IOException ignored) { }
            bound.clear();
        }
    }

    private void open() {
        try {
            int listeners = 0;
            for (int retry = 0; retry < 6 && !stopped && listeners == 0; retry++) {
                Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
                while (!stopped && interfaces != null && interfaces.hasMoreElements() &&
                       listeners < 6) {
                    NetworkInterface nic = interfaces.nextElement();
                    if (!nic.isUp() || nic.isLoopback() ||
                        !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                    for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                        if (stopped || listeners >= 6) break;
                        if (!(address.getAddress() instanceof Inet4Address)) continue;
                        Inet4Address local = (Inet4Address)address.getAddress();
                        int prefix = LanUpdateProtocol.usableHostPrefix(
                            nic.getName(), address.getNetworkPrefixLength());
                        if (prefix < 0 ||
                            LanUpdateProtocol.privateAddress(local.getHostAddress()) == null)
                            continue;
                        try {
                            ServerSocket listener = new ServerSocket(
                                LanBoardProtocol.PORT, 8, local);
                            listener.setSoTimeout(1000);
                            synchronized (bound) {
                                if (stopped) { listener.close(); break; }
                                bound.add(listener);
                            }
                            listeners++;
                            Thread worker = new Thread(
                                () -> accept(listener, local.getAddress(), prefix),
                                "Halo-LAN-board-listener");
                            worker.setDaemon(true);
                            worker.start();
                            Log.i(TAG, "Opt-in local standings ready on " +
                                local.getHostAddress() + ":" + LanBoardProtocol.PORT);
                        } catch (IOException noPort) {
                            Log.i(TAG, "Leaderboard interface/port not available");
                        }
                    }
                }
                if (listeners == 0 && retry < 5 && !stopped)
                    Thread.sleep(900);
            }
            if (listeners == 0 && !stopped)
                Log.w(TAG, "No private hotspot address available for LAN board");
        } catch (InterruptedException stoppedEarly) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!stopped) Log.w(TAG, "Host LAN board unavailable", failure);
        }
    }

    private void accept(ServerSocket listener, byte[] local, int prefix) {
        while (!stopped) {
            try {
                Socket client = listener.accept();
                try (Socket socket = client) {
                    socket.setSoTimeout(2500);
                    if (!(socket.getInetAddress() instanceof Inet4Address)) continue;
                    String peer = socket.getInetAddress().getHostAddress();
                    if (LanUpdateProtocol.privateAddress(peer) == null ||
                        !LanUpdateProtocol.sameSubnet(local,
                            socket.getInetAddress().getAddress(), prefix))
                        continue;
                    handle(socket);
                }
            } catch (SocketTimeoutException idle) {
                // Recheck stop flag, no thread or socket growth.
            } catch (IOException failed) {
                if (!stopped)
                    Log.i(TAG, "LAN board connection ended: " +
                        failed.getClass().getSimpleName());
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        String request = LanBoardProtocol.boundedLine(socket.getInputStream());
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
