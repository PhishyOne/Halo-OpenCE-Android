package com.halo.decomp;

import android.app.*;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Launcher-owned repair session. Close/stop releases sockets and leases; partial bytes persist. */
final class LanMapRepair {
    private final Activity activity;
    private final File root;
    private final BooleanSupplier available;
    private final Consumer<Boolean> busy;
    private final Runnable changed;
    private final Button share, repair, pause;
    private final TextView status;
    private volatile boolean stopped = true;
    private boolean active, ownedScreenFlag;
    private volatile Thread worker;
    private volatile LanMapServer server;
    private volatile LanDiscoveryServer discovery;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private long lastProgress;

    LanMapRepair(Activity activity, File root, Button share, Button repair, Button pause,
                 TextView status, BooleanSupplier available, Consumer<Boolean> busy, Runnable changed) {
        this.activity = activity; this.root = root; this.share = share; this.repair = repair;
        this.pause = pause; this.status = status; this.available = available; this.busy = busy; this.changed = changed;
        share.setOnClickListener(v -> share()); repair.setOnClickListener(v -> search());
        pause.setEnabled(false); pause.setOnClickListener(v -> stop());
    }
    private void message(String text) {
        activity.runOnUiThread(() -> { if (!activity.isDestroyed()) status.setText(text); });
    }
    private void progress(String text) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastProgress < 200 && text.contains("%")) return;
        lastProgress = now; message(text);
    }
    private boolean begin() {
        if (active) return false;
        if (!available.getAsBoolean()) { message("Close the game and wait for any map check to finish first."); return false; }
        active = true; stopped = false; busy.accept(true);
        share.setEnabled(false); repair.setEnabled(false); pause.setEnabled(true);
        try {
            cpu = activity.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Halo:map-repair");
            cpu.setReferenceCounted(false); cpu.acquire();
            WifiManager manager = (WifiManager)activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null) { wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Halo:map-repair");
                wifi.setReferenceCounted(false); wifi.acquire(); }
            ownedScreenFlag = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0;
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return true;
        } catch (RuntimeException unavailable) { finish("Could not keep the repair session awake. Try again.", false); return false; }
    }
    private void release() {
        if (wifi != null && wifi.isHeld()) wifi.release(); wifi = null;
        if (cpu != null && cpu.isHeld()) cpu.release(); cpu = null;
        if (ownedScreenFlag) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ownedScreenFlag = false;
    }
    private void closeSockets() {
        LanMapServer s = server; if (s != null) s.close(); server = null;
        LanDiscoveryServer d = discovery; if (d != null) d.stop(); discovery = null;
    }
    private void finish(String result, boolean refresh) {
        stopped = true; closeSockets(); worker = null; active = false; release();
        if (activity.isDestroyed()) return;
        busy.accept(false); share.setEnabled(true); repair.setEnabled(true); pause.setEnabled(false); pause.setText("STOP / PAUSE");
        status.setText(result);
        if (refresh) changed.run();
    }
    void stop() {
        if (!active) return;
        stopped = true; closeSockets();
        pause.setEnabled(false); message("Stopping. Unfinished downloads will be kept for Resume.");
        Thread task = worker;
        if (task != null) task.interrupt();
        else finish("Stopped. Choose Repair from host again to resume an unfinished download.", false);
    }
    void destroy() { stopped = true; closeSockets(); Thread task = worker; if (task != null) task.interrupt(); release(); }

    private void share() {
        if (!begin()) return;
        pause.setText("STOP SHARING");
        worker = new Thread(() -> {
            try {
                List<LanMapTransfer.Source> sources = LanMapTransfer.catalog(root, this::progress, () -> stopped);
                LanMapTransfer.canceled(() -> stopped);
                if (sources.isEmpty()) throw new IOException("No maps passed verification. Repair your maps before sharing.");
                LanMapServer next = new LanMapServer(); server = next;
                LanMapTransfer.canceled(() -> stopped);
                String ips = next.start(sources);
                LanMapTransfer.canceled(() -> stopped);
                discovery = LanDiscoveryServer.start();
                activity.runOnUiThread(() -> {
                    worker = null;
                    if (stopped || activity.isDestroyed()) { finish("Sharing stopped.", false); return; }
                    status.setText("Sharing " + sources.size() + "/11 verified maps at " + ips +
                        ". Friends: connect to the same hotspot and tap REPAIR FROM HOST. Keep this screen open.");
                });
            } catch (Exception error) { activity.runOnUiThread(() -> finish(stopped ? "Sharing stopped." : error.getMessage(), false)); }
        }, "Halo-map-share-start");
        worker.start();
    }
    private void search() {
        if (!begin()) return;
        pause.setText("PAUSE REPAIR"); message("Finding phones sharing maps on this hotspot...");
        worker = new Thread(() -> {
            List<String> found = new ArrayList<>();
            for (String host : LanDiscoveryClient.scan(activity.getApplicationContext())) {
                if (stopped) break;
                try { if (!LanMapTransfer.list(LanMapTransfer.connect(host)).isEmpty()) found.add(host); }
                catch (IOException unavailable) { /* game-only hosts are not repair hosts */ }
            }
            activity.runOnUiThread(() -> {
                worker = null;
                if (stopped || activity.isDestroyed()) { finish("Repair paused.", false); return; }
                if (found.size() == 1) receive(found.get(0));
                else if (!found.isEmpty()) new HaloDialog.Builder(activity).setTitle("Choose map host")
                    .setItems(found.toArray(new String[0]), (d, which) -> receive(found.get(which)))
                    .setNegativeButton("CANCEL", (d, which) -> finish("Repair canceled.", false))
                    .setOnCancelListener(d -> finish("Repair canceled.", false)).show();
                else manual();
            });
        }, "Halo-map-find");
        worker.start();
    }
    private void manual() {
        EditText address = new EditText(activity);
        address.setSingleLine(true); address.setHint("Host IP shown beside Share my maps");
        new HaloDialog.Builder(activity).setTitle("No map host found")
            .setMessage("Have the host tap SHARE MY MAPS and wait for verification. Connect to their hotspot. You can enter the IP shown on their phone here.")
            .setView(address).setPositiveButton("CONNECT", (d, which) -> receive(address.getText().toString().trim()))
            .setNegativeButton("CANCEL", (d, which) -> finish("Repair canceled.", false))
            .setOnCancelListener(d -> finish("Repair canceled.", false)).show();
    }
    private void receive(String host) {
        if (stopped || activity.isDestroyed()) { finish("Repair paused.", false); return; }
        message("Checking maps available from " + host + "...");
        worker = new Thread(() -> {
            StringBuilder report = new StringBuilder(); int repaired = 0, kept = 0;
            try {
                LanMapTransfer.Connect connection = LanMapTransfer.connect(host);
                List<LanMapTransfer.Offer> offers = LanMapTransfer.list(connection);
                if (offers.isEmpty()) throw new IOException("This host has no verified maps to share");
                for (LanMapTransfer.Offer offer : offers) {
                    LanMapTransfer.canceled(() -> stopped);
                    progress("Checking your " + SidewinderRepair.titleFor(LanMapTransfer.name(offer.id)) + "...");
                    if (LanMapTransfer.healthy(root, offer)) { kept++; continue; }
                    File downloaded = LanMapTransfer.download(connection, offer, root, this::progress, () -> stopped);
                    LanMapTransfer.install(downloaded, root, () -> stopped); repaired++;
                }
                report.append("✓ Repaired ").append(repaired).append(" maps; kept ").append(kept)
                    .append(" healthy maps. Host offered ").append(offers.size()).append("/11 maps.\n");
            } catch (Exception failure) {
                report.append(stopped ? "Paused" : "Transfer stopped").append(" after ").append(repaired)
                    .append(" repairs. Completed repairs are kept.\n")
                    .append(failure.getMessage() == null ? "Connection failed." : failure.getMessage())
                    .append("\nChoose REPAIR FROM HOST again to resume.");
            }
            String result = report.toString();
            activity.runOnUiThread(() -> finish(result, true));
        }, "Halo-map-download");
        worker.start();
    }
}
