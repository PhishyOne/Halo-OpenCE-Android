package com.halo.decomp;

import android.app.AlertDialog;
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
 * One bounded scan at a time, no stats transfers, no persistent peer cache. */
final class LanBoardPresenceMonitor {
    private static final long REFRESH_MS = 6000;
    private final LauncherActivity activity;
    private final Button indicator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::scan;
    private LanBoardPresence.Snapshot snapshot = LanBoardPresence.Snapshot.empty();
    private boolean active, busy, closed;
    private int generation;
    private AlertDialog details;

    LanBoardPresenceMonitor(LauncherActivity activity, Button indicator) {
        this.activity = activity;
        this.indicator = indicator;
        indicator.setOnClickListener(view -> showDetails());
        render();
    }

    void resume() {
        if (closed || active) return;
        active = true;
        generation++;
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
        if (details != null) details.dismiss();
        details = null;
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

    private String detailText() {
        long now = SystemClock.elapsedRealtime();
        if (!snapshot.fresh(now))
            return "Checking the local hotspot…\n\nNo career data is exchanged by this scan.";
        StringBuilder content = new StringBuilder();
        content.append(snapshot.ready()).append(" ready to sync");
        if (snapshot.peers.size() != snapshot.ready())
            content.append(" • ").append(snapshot.peers.size() - snapshot.ready())
                .append(" discovery only");
        content.append("\n");
        if (snapshot.peers.isEmpty())
            content.append("\nNo opted-in Halo phones detected on your local network.");
        for (LanBoardPresence.Peer peer : snapshot.peers) {
            content.append("\n").append(peer.named() ? "● " : "○ ")
                .append(peer.name).append(" — ").append(peer.location())
                .append("\n    ").append(peer.ip);
        }
        content.append("\n\nNames require the new version and leaderboard sharing enabled. ")
            .append("Discovery-only phones may be older or temporarily unavailable. ")
            .append("This is not the hotspot's complete device list or an active-match roster. ")
            .append("Scan is read-only; leaderboard stats are updated separately.");
        return content.toString();
    }

    private void render() {
        String text = summary();
        indicator.setText(text);
        indicator.setContentDescription(text + ". Tap to see live peers.");
        indicator.setTextColor(snapshot.fresh(SystemClock.elapsedRealtime()) &&
            snapshot.ready() > 0 ? android.graphics.Color.rgb(124, 229, 174) : HaloTheme.MUTED);
        if (details != null && details.isShowing()) details.setMessage(detailText());
    }

    private void showDetails() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        details = new AlertDialog.Builder(activity)
            .setTitle("LIVE HALO PEERS")
            .setMessage(detailText())
            .setNeutralButton("SCAN NOW", null)
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("LEADERBOARD", (dialog, button) ->
                LanBoardUi.show(activity, activity.getExternalFilesDir(null)))
            .create();
        details.setOnDismissListener(dialog -> details = null);
        details.show();
        details.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            handler.removeCallbacks(tick);
            if (!busy) handler.post(tick);
            else render();
        });
    }
}
