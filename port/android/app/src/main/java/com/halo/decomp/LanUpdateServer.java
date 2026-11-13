package com.halo.decomp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serves ONLY this installed signed APK on the selected LAN host's Wi-Fi or
 * hotspot interfaces, while HaloActivity runs. Never a file manager, proxy
 * or internet-facing listener. No extra download copy is kept on the host. */
final class LanUpdateServer {
    private static final String LOG = "HaloLanUpdate";
    private final Context context;
    private final List<ServerSocket> listeners = Collections.synchronizedList(
        new ArrayList<>());
    private final List<Socket> connections = Collections.synchronizedList(
        new ArrayList<>());
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
        task -> {
            Thread thread = new Thread(task, "Halo-LAN-update-client");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    private LanUpdateServer(Context context) {
        this.context = context.getApplicationContext();
    }

    static LanUpdateServer start(Context context) {
        LanUpdateServer server = new LanUpdateServer(context);
        Thread boot = new Thread(server::startWorker, "Halo-LAN-update-start");
        boot.setDaemon(true);
        boot.start();
        return server;
    }

    private static String hash(File apk) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 missing", failure);
        }
        byte[] buffer = new byte[65536];
        try (FileInputStream in = new FileInputStream(apk)) {
            int n;
            while ((n = in.read(buffer)) > 0)
                digest.update(buffer, 0, n);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest())
            value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private void startWorker() {
        try {
            if (closed) return;
            String source = context.getApplicationInfo().sourceDir;
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (source == null || (splits != null && splits.length > 0)) {
                Log.w(LOG, "Split/unknown APK cannot be shared as a single update");
                return;
            }
            File apk = new File(source);
            long size = apk.length();
            if (!apk.isFile() || size < 1024 ||
                size > LanUpdateProtocol.MAX_APK_BYTES)
                throw new IOException("Installed APK is not shareable");
            PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(), 0);
            int version = (int)installed.getLongVersionCode();
            LanUpdateProtocol.Manifest manifest = new LanUpdateProtocol.Manifest(
                context.getPackageName(), version, size, hash(apk));
            if (closed) return;

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int bound = 0;
            while (!closed && interfaces != null && interfaces.hasMoreElements() &&
                    bound < 8) {
                NetworkInterface nic = interfaces.nextElement();
                if (!LanUpdateProtocol.lanInterface(nic.getName()) ||
                    nic.isLoopback() || !nic.isUp())
                    continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (closed || bound >= 8) break;
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address local = (Inet4Address) address.getAddress();
                    int prefix = address.getNetworkPrefixLength();
                    if (prefix < 16 || prefix > 30 ||
                        LanUpdateProtocol.privateAddress(local.getHostAddress()) == null)
                        continue;
                    try {
                        ServerSocket listener = new ServerSocket(LanUpdateProtocol.PORT,
                            4, local);
                        listeners.add(listener);
                        if (closed) {
                            listener.close();
                            return;
                        }
                        bound++;
                        Log.i(LOG, "LAN updates available on " + local.getHostAddress()
                            + ":" + LanUpdateProtocol.PORT);
                        Thread worker = new Thread(
                            () -> acceptLoop(listener, apk, manifest, local.getAddress(), prefix),
                            "Halo-LAN-update-listen");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (IOException bindError) {
                        Log.w(LOG, "Couldn't bind LAN update interface: " + bindError);
                    }
                }
            }
            if (bound == 0)
                Log.w(LOG, "LAN APK share unavailable: no eligible Wi-Fi/hotspot interface");
        } catch (Exception problem) {
            Log.w(LOG, "LAN APK share unavailable", problem);
        }
    }

    private void acceptLoop(ServerSocket listener, File apk,
            LanUpdateProtocol.Manifest manifest, byte[] local, int prefix) {
        while (!closed) {
            try {
                Socket incoming = listener.accept();
                if (closed) { incoming.close(); break; }
                connections.add(incoming);
                try {
                    clients.execute(() -> {
                        try {
                            LanUpdateSocket.serve(incoming, apk, manifest, local, prefix);
                        } catch (IOException problem) {
                            if (!closed) Log.i(LOG, "Client transfer ended: " + problem);
                        } finally {
                            connections.remove(incoming);
                            try { incoming.close(); } catch (IOException ignored) { }
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException busy) {
                    connections.remove(incoming);
                    incoming.close();
                }
            } catch (IOException failure) {
                if (!closed)
                    Log.w(LOG, "LAN update listener stopped", failure);
                break;
            }
        }
    }

    void stop() {
        closed = true;
        synchronized (listeners) {
            for (ServerSocket listener : listeners) {
                try { listener.close(); } catch (IOException ignored) { }
            }
            listeners.clear();
        }
        synchronized (connections) {
            for (Socket socket : connections) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            connections.clear();
        }
        clients.shutdownNow();
    }
}
