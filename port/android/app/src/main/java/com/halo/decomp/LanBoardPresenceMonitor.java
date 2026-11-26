package com.halo.decomp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Foreground-only discovery controller for the launcher masthead.
 * One bounded presence scan and one consented automatic-sync batch at a
 * time. Presence remains independent of saved career timestamps. */
final class LanBoardPresenceMonitor {
    private static final long REFRESH_MS = 6000;
    private final LauncherActivity activity;
    private final Button indicator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::scan;
    private LanBoardPresence.Snapshot snapshot = LanBoardPresence.Snapshot.empty();
    private volatile boolean active, closed;
    private boolean busy, autoBusy;
    private volatile int generation;
    private final LanBoardAutoSync auto = new LanBoardAutoSync();
    private final List<Runnable> observers = new ArrayList<>();

    LanBoardPresenceMonitor(LauncherActivity activity, Button indicator) {
        this.activity = activity;
        this.indicator = indicator;
        indicator.setOnClickListener(view -> LanBoardUi.show(activity,
            activity.getExternalFilesDir(null)));
        render();
    }

    void resume() {
        if (closed || active) return;
        active = true;
        generation++;
        auto.reset();
        snapshot = LanBoardPresence.Snapshot.empty();
        render();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void pause() {
        active = false;
        generation++;
        handler.removeCallbacks(tick);
        snapshot = LanBoardPresence.Snapshot.empty();
        render();
    }

    void close() {
        pause();
        closed = true;
        observers.clear();
    }

    LanBoardPresence.Snapshot snapshot() { return snapshot; }
    void observe(Runnable listener) {
        if (listener != null && !observers.contains(listener)) observers.add(listener);
    }
    void removeObserver(Runnable listener) { observers.remove(listener); }
    void sharingChanged() {
        auto.reset();
        if (active && !closed) {
            maybeAutoSync();
            handler.removeCallbacks(tick);
            if (!busy) handler.post(tick);
        }
    }

    List<String> liveTargets() {
        return snapshot.targets(SystemClock.elapsedRealtime());
    }

    private void scan() {
        if (!active || closed || busy) return;
        busy = true;
        int run = generation;
        List<String> previous = new ArrayList<>(liveTargets());
        String remembered = activity.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
            .getString("last_host", "");
        File root = activity.getExternalFilesDir(null);
        Context context = activity.getApplicationContext();
        render();
        new Thread(() -> {
            List<LanBoardPresence.Peer> peers = Collections.emptyList();
            try {
                String manual = remembered;
                if (root != null && LanUpdateProtocol.privateAddress(manual) == null) {
                    try {
                        manual = LanConfig.load(new File(root, "config.toml")).peer;
                    } catch (IOException ignored) { }
                }
                // The own ID is only used to exclude self. The scan sends
                // random challenges, never the local name or career.
                String self = LanBoardIdentity.id(context);
                peers = LanBoardNearby.presence(context, self, manual, previous);
            } catch (IOException | RuntimeException unavailable) {
                // No response is not proof that a device is disconnected.
            }
            LanBoardPresence.Snapshot found = new LanBoardPresence.Snapshot(
                peers, SystemClock.elapsedRealtime(), true);
            handler.post(() -> {
                busy = false;
                if (closed || !active) return;
                if (run == generation) {
                    snapshot = found;
                    render();
                    maybeAutoSync();
                    handler.postDelayed(tick, REFRESH_MS);
                } else {
                    handler.post(tick);
                }
            });
        }, "Halo-live-peer-scan").start();
    }

    private String summary() {
        if (!snapshot.fresh(SystemClock.elapsedRealtime()))
            return active ? "HALO PEERS · CHECKING…" : "HALO PEERS";
        if (snapshot.peers.isEmpty()) return "0 HALO PEERS";
        List<String> names = new ArrayList<>();
        for (LanBoardPresence.Peer peer : snapshot.peers)
            if (peer.named()) names.add(peer.name);
        int unnamed = snapshot.peers.size() - snapshot.ready();
        if (unnamed > 0) names.add(unnamed + " unnamed");
        return snapshot.peers.size() + " HALO PEERS · " +
            android.text.TextUtils.join(", ", names);
    }

    /** Auto sync sends tiny career cards only after the local user's
     * persistent sharing opt-in. Never block the independent presence scan. */
    private void maybeAutoSync() {
        if (closed || !active || autoBusy || !LanShareSettings.career(activity)) return;
        File root = activity.getExternalFilesDir(null);
        if (root == null) return;
        List<LanBoardPresence.Peer> due = auto.due(snapshot, SystemClock.elapsedRealtime());
        if (due.isEmpty()) return;
        int run = generation;
        Context context = activity.getApplicationContext();
        autoBusy = true;
        new Thread(() -> {
            java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(2, due.size()));
            try {
                List<java.util.concurrent.Callable<Void>> jobs = new ArrayList<>();
                for (LanBoardPresence.Peer peer : due) jobs.add(() -> {
                    if (closed || !active || run != generation ||
                        !LanShareSettings.career(context)) return null;
                    try {
                        LanBoardClient.sync(context, root, peer.ip, peer.id);
                    } catch (IOException | RuntimeException unavailable) {
                        // No invented sync time or replacement of saved stats.
                    }
                    return null;
                });
                workers.invokeAll(jobs, 15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } finally {
                workers.shutdownNow();
                handler.post(() -> {
                    autoBusy = false;
                    if (!closed && active) {
                        render();
                        if (run != generation) maybeAutoSync();
                    }
                });
            }
        }, "Halo-auto-career-sync").start();
    }

    private void render() {
        String text = summary();
        indicator.setText(text);
        indicator.setContentDescription(text + ". Tap to see live players and leaderboard.");
        indicator.setTextColor(snapshot.fresh(SystemClock.elapsedRealtime()) &&
            snapshot.ready() > 0 ? android.graphics.Color.rgb(124, 229, 174) : HaloTheme.MUTED);
        for (Runnable observer : new ArrayList<>(observers)) observer.run();
    }
}
