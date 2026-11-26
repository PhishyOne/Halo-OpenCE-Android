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
    private final Button share, repair, receive, pause;
    private final TextView status;
    private volatile boolean stopped = true;
    private boolean active, ownedScreenFlag;
    private volatile Thread worker;
    private volatile LanMapServer server;
    private volatile LanDiscoveryServer discovery;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private long lastProgress;

    LanMapRepair(Activity activity, File root, Button share, Button repair, Button receive, Button pause,
                 TextView status, BooleanSupplier available, Consumer<Boolean> busy, Runnable changed) {
        this.activity = activity; this.root = root; this.share = share; this.repair = repair;
        this.receive = receive; this.pause = pause; this.status = status; this.available = available; this.busy = busy; this.changed = changed;
        share.setOnClickListener(v -> chooseShare());
        repair.setOnClickListener(v -> search(false));
        receive.setOnClickListener(v -> search(true));
        pause.setVisibility(View.GONE); pause.setEnabled(false); pause.setOnClickListener(v -> stop());
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
        share.setEnabled(false); repair.setEnabled(false); receive.setEnabled(false); pause.setVisibility(View.VISIBLE); pause.setEnabled(true);
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
        busy.accept(false); share.setEnabled(true); repair.setEnabled(true); receive.setEnabled(true); pause.setEnabled(false); pause.setVisibility(View.GONE); pause.setText("STOP / PAUSE");
        status.setText(result);
        if (refresh) changed.run();
    }
    void stop() {
        if (!active) return;
        stopped = true; closeSockets();
        pause.setEnabled(false); message("Stopping. Unfinished downloads will be kept for Resume.");
        Thread task = worker;
        if (task != null) task.interrupt();
        else finish("Stopped. Choose the same receive or repair action again to resume.", false);
    }
    void destroy() { stopped = true; closeSockets(); Thread task = worker; if (task != null) task.interrupt(); release(); }

    private void chooseShare() {
        if (active) return;
        if (!available.getAsBoolean()) { message("Close the game and finish the current operation first."); return; }
        {
            List<String> names;
            try { names = LanCustomMapTransfer.names(root); }
            catch (IOException unavailable) {
                message("Custom map selection unavailable: " + unavailable.getMessage());
                names = Collections.emptyList();
            }
            List<String> choices = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            choices.add(""); labels.add("Original maps (for Repair From Host)");
            for (String name : names) {
                if (name.endsWith(".bmp")) continue; // included with its matching map
                choices.add(name);
                labels.add(LanCustomMapTransfer.resource(name) ? "Resource: " + name : name);
            }
            boolean[] selected = new boolean[choices.size()];
            selected[0] = true;
            new HaloDialog.Builder(activity).setTitle("Choose maps to share")
                .setMessage("Friends can receive selected custom maps. Available previews and shared resources are included automatically.")
                .setMultiChoiceItems(labels.toArray(new String[0]), selected,
                    (d, index, checked) -> selected[index] = checked)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SHARE", (d, which) -> {
                    List<String> custom = new ArrayList<>();
                    for (int i = 1; i < choices.size(); i++) if (selected[i]) custom.add(choices.get(i));
                    if (!selected[0] && custom.isEmpty()) { message("Choose at least one map."); return; }
                    share(selected[0], custom);
                }).show();
        }
    }

    private void share(boolean originals, List<String> selected) {
        if (!begin()) return;
        pause.setText("STOP SHARING");
        worker = new Thread(() -> {
            try {
                List<LanMapTransfer.Source> sources = originals ?
                    LanMapTransfer.catalog(root, this::progress, () -> stopped) : Collections.emptyList();
                List<LanCustomMapTransfer.Source> custom =
                    LanCustomMapTransfer.catalog(root, selected, this::progress, () -> stopped);
                LanMapTransfer.canceled(() -> stopped);
                if (sources.isEmpty() && custom.isEmpty()) throw new IOException("No selected maps passed verification.");
                LanMapServer next = new LanMapServer(); server = next;
                LanMapTransfer.canceled(() -> stopped);
                String ips = next.start(sources, custom);
                LanMapTransfer.canceled(() -> stopped);
                discovery = LanDiscoveryServer.start();
                activity.runOnUiThread(() -> {
                    worker = null;
                    if (stopped || activity.isDestroyed()) { finish("Sharing stopped.", false); return; }
                    status.setText("Your IP: " + ips + "\nSharing " + sources.size() + " original and " +
                        custom.size() + " custom files. Friends: use Repair From Host or Receive Maps. Keep this screen open.");
                });
            } catch (Exception error) { activity.runOnUiThread(() -> finish(stopped ? "Sharing stopped." : error.getMessage(), false)); }
        }, "Halo-map-share-start");
        worker.start();
    }
    private void search(boolean custom) {
        if (!begin()) return;
        pause.setText(custom ? "PAUSE RECEIVE" : "PAUSE REPAIR");
        message("Finding phones sharing maps on this hotspot...");
        worker = new Thread(() -> {
            List<String> found = new ArrayList<>();
            for (String host : LanDiscoveryClient.scan(activity.getApplicationContext())) {
                if (stopped) break;
                try {
                    boolean offered = custom ?
                        !LanCustomMapTransfer.list(LanMapTransfer.connect(host)).isEmpty() :
                        !LanMapTransfer.list(LanMapTransfer.repairConnection(host)).isEmpty();
                    if (offered) found.add(host);
                } catch (IOException unavailable) { /* game-only hosts are not map hosts */ }
            }
            activity.runOnUiThread(() -> {
                worker = null;
                if (stopped || activity.isDestroyed()) { finish("Repair paused.", false); return; }
                if (found.size() == 1) receive(found.get(0), custom);
                else if (!found.isEmpty()) new HaloDialog.Builder(activity).setTitle("Choose map host")
                    .setItems(found.toArray(new String[0]), (d, which) -> receive(found.get(which), custom))
                    .setNegativeButton("CANCEL", (d, which) -> finish("Repair canceled.", false))
                    .setOnCancelListener(d -> finish("Repair canceled.", false)).show();
                else manual(custom);
            });
        }, "Halo-map-find");
        worker.start();
    }
    private void manual(boolean custom) {
        EditText address = new EditText(activity);
        address.setSingleLine(true); address.setHint("Sharing phone\u2019s IP address");
        address.setContentDescription("Copy Your IP from the phone sharing the maps.");
        new HaloDialog.Builder(activity).setTitle("Enter sharing phone\u2019s IP address")
            .setMessage("On the other phone, tap SHARE MAPS. Connect to the same hotspot, then copy the address labeled Your IP.")
            .setView(address).setPositiveButton("CONNECT", (d, which) -> receive(address.getText().toString().trim(), custom))
            .setNegativeButton("CANCEL", (d, which) -> finish("Repair canceled.", false))
            .setOnCancelListener(d -> finish("Repair canceled.", false)).show();
    }
    private void chooseReceive(String host) {
        message("Reading shared custom maps...");
        worker = new Thread(() -> {
            try {
                LanMapTransfer.Connect connection = LanMapTransfer.connect(host);
                List<LanCustomMapTransfer.Offer> offers = LanCustomMapTransfer.list(connection);
                activity.runOnUiThread(() -> {
                    worker = null;
                    if (stopped || activity.isDestroyed()) { finish("Receive paused.", false); return; }
                    if (offers.isEmpty()) { finish("No custom maps offered. Original maps use Repair From Host.", false); return; }
                    List<LanCustomMapTransfer.Offer> choices = new ArrayList<>();
                    for (LanCustomMapTransfer.Offer offer : offers)
                        if (!offer.name.endsWith(".bmp")) choices.add(offer);
                    if (choices.isEmpty()) choices.addAll(offers);
                    String[] labels = new String[choices.size()];
                    boolean[] selected = new boolean[choices.size()];
                    for (int i = 0; i < choices.size(); i++) {
                        LanCustomMapTransfer.Offer offer = choices.get(i);
                        labels[i] = offer.name + " (" + String.format(Locale.US, "%.1f MiB", offer.size / 1048576.0) + ")";
                    }
                    new HaloDialog.Builder(activity).setTitle("Choose maps to receive")
                        .setMessage("Matching previews and offered shared resources are included with a map. Existing different files are never overwritten.")
                        .setMultiChoiceItems(labels, selected, (d, index, checked) -> selected[index] = checked)
                        .setNegativeButton("CANCEL", (d, which) -> finish("Receive canceled.", false))
                        .setOnCancelListener(d -> finish("Receive canceled.", false))
                        .setPositiveButton("RECEIVE", (d, which) -> {
                            if (stopped || !active) return;
                            List<String> wanted = new ArrayList<>(), names = new ArrayList<>();
                            for (LanCustomMapTransfer.Offer o : offers) names.add(o.name);
                            for (int i = 0; i < choices.size(); i++)
                                if (selected[i]) wanted.add(choices.get(i).name);
                            List<String> selectedNames = LanCustomMapTransfer.dependencies(names, wanted);
                            List<LanCustomMapTransfer.Offer> selectedOffers = new ArrayList<>();
                            for (String name : selectedNames) for (LanCustomMapTransfer.Offer o : offers)
                                if (name.equals(o.name)) selectedOffers.add(o);
                            if (selectedOffers.isEmpty()) { finish("No maps selected.", false); return; }
                            receiveCustom(connection, selectedOffers);
                        }).show();
                });
            } catch (Exception failure) {
                activity.runOnUiThread(() -> finish(stopped ? "Receive paused." : failure.getMessage(), false));
            }
        }, "Halo-custom-catalog");
        worker.start();
    }

    private void receiveCustom(LanMapTransfer.Connect connection, List<LanCustomMapTransfer.Offer> offers) {
        if (stopped || !active || activity.isDestroyed()) return;
        worker = new Thread(() -> {
            int received = 0, kept = 0;
            String result;
            try {
                for (LanCustomMapTransfer.Offer offer : offers) {
                    LanMapTransfer.canceled(() -> stopped);
                    progress("Checking " + offer.name + "...");
                    if (LanCustomMapTransfer.installed(root, offer, () -> stopped)) { kept++; continue; }
                    File downloaded = LanCustomMapTransfer.download(connection, offer, root,
                        this::progress, () -> stopped);
                    LanCustomMapTransfer.install(downloaded, root, offer, () -> stopped);
                    received++;
                }
                result = "Received " + received + " files; kept " + kept +
                    " identical files. Choose CUSTOM MULTIPLAYER in Halo.";
            } catch (Exception failure) {
                result = (stopped ? "Paused" : "Transfer stopped") + " after " + received +
                    " files. Completed files kept. " + (failure.getMessage() == null ? "" : failure.getMessage()) +
                    "\nReceive the same map again to resume.";
            }
            final String message = result;
            activity.runOnUiThread(() -> finish(message, false));
        }, "Halo-custom-download");
        worker.start();
    }

    private void receive(String host, boolean custom) {
        if (stopped || activity.isDestroyed()) { finish("Transfer paused.", false); return; }
        if (custom) { chooseReceive(host); return; }
        message("Checking maps available from " + host + "...");
        worker = new Thread(() -> {
            StringBuilder report = new StringBuilder(); int repaired = 0, kept = 0;
            try {
                LanMapTransfer.Connect connection = LanMapTransfer.repairConnection(host);
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
