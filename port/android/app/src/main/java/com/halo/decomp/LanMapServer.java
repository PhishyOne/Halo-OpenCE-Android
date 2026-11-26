package com.halo.decomp;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in, map-only listener on eligible private Wi-Fi/hotspot addresses. */
final class LanMapServer implements AutoCloseable {
    private final List<ServerSocket> listeners = new ArrayList<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
    private volatile boolean closed;

    synchronized String start(List<LanMapTransfer.Source> sources) throws IOException {
        return start(sources, Collections.emptyList());
    }
    synchronized String start(List<LanMapTransfer.Source> sources,
            List<LanCustomMapTransfer.Source> custom) throws IOException {
        if (closed) throw new IOException("Map sharing stopped");
        deadlines.setRemoveOnCancelPolicy(true);
        List<String> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all != null && all.hasMoreElements() && addresses.size() < 8) {
            NetworkInterface nic = all.nextElement();
            if (!nic.isUp() || nic.isLoopback() || !LanUpdateProtocol.lanInterface(nic.getName())) continue;
            for (InterfaceAddress item : nic.getInterfaceAddresses()) {
                if (addresses.size() >= 8) break;
                InetAddress ip = item.getAddress();
                int prefix = LanUpdateProtocol.usableHostPrefix(nic.getName(), item.getNetworkPrefixLength());
                if (!(ip instanceof Inet4Address) || prefix < 0 || LanUpdateProtocol.privateAddress(ip.getHostAddress()) == null) continue;
                try {
                    ServerSocket listener = new ServerSocket(LanMapTransfer.PORT, 6, ip);
                    listeners.add(listener); addresses.add(ip.getHostAddress());
                    Thread thread = new Thread(() -> accept(listener, ip.getAddress(), prefix, sources, custom), "Halo-map-listener");
                    thread.setDaemon(true); thread.start();
                } catch (IOException unavailable) { /* another eligible interface may work */ }
            }
        }
        if (addresses.isEmpty()) { close(); throw new IOException("Turn on your hotspot or join Wi-Fi, then try Share my maps again."); }
        return String.join(", ", addresses);
    }
    private void accept(ServerSocket listener, byte[] local, int prefix, List<LanMapTransfer.Source> sources, List<LanCustomMapTransfer.Source> custom) {
        while (!closed) {
            Socket incoming = null;
            try {
                incoming = listener.accept();
                if (closed || !LanUpdateProtocol.sameSubnet(local, incoming.getInetAddress().getAddress(), prefix)) {
                    incoming.close(); continue;
                }
                incoming.setSoTimeout(8000);
                final Socket socket = incoming;
                sockets.add(socket);
                ScheduledFuture<?> expiry = deadlines.schedule(() -> shut(socket), 30, TimeUnit.SECONDS);
                try {
                    workers.execute(() -> {
                        try (Socket connection = socket) {
                            BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 65536);
                            input.mark(4);
                            int magic = new DataInputStream(input).readInt();
                            input.reset();
                            if (magic == LanCustomMapTransfer.MAGIC)
                                LanCustomMapTransfer.serve(input, connection.getOutputStream(), custom);
                            else
                                LanMapTransfer.serve(input, connection.getOutputStream(), sources);
                        } catch (IOException ignored) { /* client retains any partial download */ }
                        finally { expiry.cancel(false); sockets.remove(socket); }
                    });
                } catch (RejectedExecutionException busy) { expiry.cancel(false); sockets.remove(socket); shut(socket); }
            } catch (IOException | RejectedExecutionException stopped) {
                if (incoming != null) { sockets.remove(incoming); shut(incoming); }
                break;
            }
        }
    }
    private static void shut(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    @Override public synchronized void close() {
        closed = true;
        for (ServerSocket listener : listeners) try { listener.close(); } catch (IOException ignored) { }
        listeners.clear();
        for (Socket socket : sockets) shut(socket);
        sockets.clear(); workers.shutdownNow(); deadlines.shutdownNow();
    }
}
