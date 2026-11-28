package com.halo.decomp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(6),
        task -> {
            Thread thread = new Thread(task, "Halo-LAN-update-client");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    /** A small, private-app-storage diagnostic snapshot survives returning
     * to the launcher. Other apps cannot choose its path or content. */
    private void status(String state, String detail) {
        File root = context.getExternalFilesDir(null);
        if (root == null || !root.isDirectory())
            return;
        File temporary = null;
        try {
            temporary = File.createTempFile("halo-lan-update-", ".tmp", root);
            File target = new File(root, "lan-update-status.txt");
            String body = "LAN update server: " + state + "\n" +
                "Details: " + detail + "\n";
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unavailable) {
            Log.i(LOG, "Cannot save LAN status: " +
                unavailable.getClass().getSimpleName());
        } finally {
            if (temporary != null)
                temporary.delete();
        }
    }

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

    static String hash(File apk) throws IOException {
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
            status("STARTING", "Inspecting installed APK and hotspot interfaces");
            String source = context.getApplicationInfo().sourceDir;
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            if (source == null || (splits != null && splits.length > 0)) {
                Log.w(LOG, "Split/unknown APK cannot be shared as a single update");
                status("UNAVAILABLE", "Split or unknown APK package");
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

            int bound = 0;
            /* Android hotspots can bring up their IPv4 address slightly
             * after the game activity starts. v23-v30 scanned only once
             * and stayed unavailable for the whole match. Retry for a
             * bounded period, never open a wildcard or cellular socket. */
            for (int attempt = 0; !closed && attempt < 6 && bound == 0; attempt++) {
              Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
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
                    int prefix = LanUpdateProtocol.usableHostPrefix(
                        nic.getName(), address.getNetworkPrefixLength());
                    if (prefix < 0 ||
                        LanUpdateProtocol.privateAddress(local.getHostAddress()) == null)
                        continue;
                    try {
                        ServerSocket listener = new ServerSocket(LanUpdateProtocol.PORT,
                            8, local);
                        listeners.add(listener);
                        if (closed) {
                            listener.close();
                            return;
                        }
                        bound++;
                        if (bound == 1)
                            status("READY", "Listening on " +
                                local.getHostAddress() + ":" + LanUpdateProtocol.PORT +
                                " (interface " + nic.getName() + ", prefix /" + prefix + ")");
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
              if (bound == 0 && !closed && attempt < 5)
                  Thread.sleep(900);
            }
            if (bound == 0 && !closed) {
                status("UNAVAILABLE", "No eligible private hotspot/Wi-Fi address or port 5162");
                Log.w(LOG, "LAN APK share unavailable: no eligible Wi-Fi/hotspot interface");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception problem) {
            if (!closed) {
                status("UNAVAILABLE", "Startup error: " +
                    problem.getClass().getSimpleName());
                Log.w(LOG, "LAN APK share unavailable", problem);
            }
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
                    LanUpdateSocket.busy(incoming);
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
        status("STOPPED", "Halo host game closed or stopped sharing");
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
