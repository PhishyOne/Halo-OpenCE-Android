package com.halo.decomp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Three actual launcher content pages, using the existing activity and
 * Halo theme. No new Activity, nested scroll or generic player dialog. */
final class LanBoardUi {
    private enum Page { BOARD, PLAYER, VIDEOS }
    private final LauncherActivity activity;
    private final File root;
    private final LinearLayout panel;
    private final ScrollView scroll;
    private final Runnable observer = this::reload;
    private Page page = Page.BOARD;
    private boolean visible, closed, loading, dirty, syncing, changing;
    private int generation;
    private String selectedId, selectedName;
    private boolean mine;
    private LanBoardProtocol.Entry own;
    private List<LanBoardProtocol.Entry> shown = new ArrayList<>();
    private LinearLayout rows;
    private TextView live, status, detailStatus;
    private LinearLayout detailBody;
    private String detailFingerprint;
    private CheckBox sharing;
    private Button sync;
    private EditText hostIp;
    private int boardScroll;
    private static final int GREEN = Color.rgb(124, 229, 174);

    LanBoardUi(LauncherActivity activity, File root, LinearLayout panel, ScrollView scroll) {
        this.activity = activity;
        this.root = root;
        this.panel = panel;
        this.scroll = scroll;
        buildBoard();
    }

    static void show(Activity activity, File root) {
        if (activity instanceof LauncherActivity)
            ((LauncherActivity)activity).openLeaderboard();
    }

    void setVisible(boolean value) {
        if (closed || visible == value) return;
        visible = value;
        if (value) {
            activity.observeLiveBoard(observer);
            reload();
        } else {
            activity.removeLiveBoardObserver(observer);
            generation++;
        }
    }

    void close() {
        setVisible(false);
        closed = true;
        generation++;
    }

    void home() {
        if (closed) return;
        if (page != Page.BOARD) {
            buildBoard();
            scroll.post(() -> scroll.scrollTo(0, boardScroll));
        }
        reload();
    }

    boolean back() {
        if (!visible || page == Page.BOARD) return false;
        if (page == Page.VIDEOS) buildPlayer();
        else home();
        return true;
    }

    void myVideos() {
        mine = true;
        selectedId = own == null ? null : own.id;
        selectedName = own == null ? LanBoardIdentity.name(activity) : own.name;
        buildVideos();
    }

    private int dp(int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(dp(3), dp(2), dp(3), dp(2));
        HaloTheme.font(t, bold);
        return t;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(activity);
        b.setText(label); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0);
        HaloTheme.menuButtonColors(b); HaloTheme.font(b, true);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private LinearLayout box(boolean selected) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(4), dp(6), dp(4));
        card.setBackground(HaloTheme.surface(
            selected ? HaloTheme.CONTROL_TOP : HaloTheme.PANEL_TOP,
            HaloTheme.PANEL_BOTTOM, selected ? HaloTheme.FOCUS : HaloTheme.BORDER,
            4, activity.getResources().getDisplayMetrics().density));
        return card;
    }
    private void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(4);
        parent.addView(card, p);
    }
    private void action(LinearLayout parent, String label, Runnable run) {
        parent.addView(button(label, run), new LinearLayout.LayoutParams(-1, dp(40)));
    }
    private void begin(Page next, String title, String subtitle, Runnable back) {
        generation++;
        page = next;
        panel.removeAllViews();
        LinearLayout heading = horizontal();
        if (back != null) {
            Button arrow = button("‹ BACK", back);
            heading.addView(arrow, new LinearLayout.LayoutParams(dp(86), dp(36)));
        }
        heading.addView(text(title, 19, HaloTheme.FOCUS_TEXT, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(heading);
        panel.addView(text(subtitle, 11, HaloTheme.MUTED, false));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void buildBoard() {
        begin(Page.BOARD, "LAN LEADERBOARD", "UNSC SERVICE RANK / PRACTICE SR • UNVERIFIED", null);
        LinearLayout header = horizontal();
        live = text("CHECKING…", 12, HaloTheme.MUTED, true);
        header.addView(live, new LinearLayout.LayoutParams(0, -2, 1));
        sharing = new CheckBox(activity);
        sharing.setText("LAN SHARING • STATS + HIGHLIGHTS");
        sharing.setTextColor(HaloTheme.TEXT); sharing.setTextSize(11);
        sharing.setButtonTintList(HaloTheme.CHECK_TINT); HaloTheme.font(sharing, true);
        sharing.setChecked(LanShareSettings.career(activity));
        sharing.setOnCheckedChangeListener((v, enabled) -> {
            if (changing) return;
            boolean saved = LanShareSettings.set(activity, enabled);
            changing = true;
            sharing.setChecked(LanShareSettings.career(activity));
            changing = false;
            if (saved) activity.boardSharingChanged();
            else status.setText("Couldn't save sharing setting.");
        });
        header.addView(sharing);
        panel.addView(header);
        panel.addView(text("Automatic on trusted local Wi-Fi. Saved reels can be viewed by reachable LAN devices. "
            + "Not encrypted. Full recordings stay private.", 10, HaloTheme.MUTED, false));
        LinearLayout headings = horizontal();
        headings.setPadding(dp(9), 0, dp(20), 0);
        cell(headings, "PLAYER / STATUS", 3.4f, 10, HaloTheme.MUTED, true);
        cell(headings, "PRACTICE SR", 1.25f, 10, HaloTheme.MUTED, true);
        cell(headings, "K/D", .9f, 10, HaloTheme.MUTED, true);
        cell(headings, "WINS", .85f, 10, HaloTheme.MUTED, true);
        cell(headings, "XP", 1.15f, 10, HaloTheme.MUTED, true);
        panel.addView(headings);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rows);
        status = text("Loading service records…", 11, HaloTheme.MUTED, false);
        panel.addView(status);
        LinearLayout actions = horizontal();
        hostIp = new EditText(activity);
        hostIp.setSingleLine(true); hostIp.setTextSize(12);
        hostIp.setInputType(InputType.TYPE_CLASS_PHONE);
        hostIp.setTextColor(HaloTheme.TEXT); hostIp.setHintTextColor(HaloTheme.MUTED);
        hostIp.setHint("Peer IP (optional)");
        String remembered = activity.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
            .getString("last_host", "");
        if (LanUpdateProtocol.privateAddress(remembered) != null) hostIp.setText(remembered);
        actions.addView(hostIp, new LinearLayout.LayoutParams(0, dp(38), 2));
        sync = button("REFRESH LAN", this::synchronize);
        sync.setEnabled(!syncing);
        actions.addView(sync, new LinearLayout.LayoutParams(0, dp(38), 1));
        panel.addView(actions);
        drawBoard();
    }

    private void cell(LinearLayout row, String value, float weight, int size, int color, boolean bold) {
        TextView t = text(value, size, color, bold);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, weight));
    }
    private LanBoardPresence.Snapshot snapshot() { return activity.liveBoardSnapshot(); }
    private LanBoardPresence.Peer current(String id) {
        return snapshot().byId(id, SystemClock.elapsedRealtime());
    }
    private static int rank(long xp) {
        int index = 0;
        while (index + 1 < CareerRecords.rankCount() &&
            xp >= CareerRecords.rankThreshold(index + 1)) index++;
        return index;
    }
    private static String kd(LanBoardProtocol.Entry e) {
        return e.deaths == 0 ? (e.kills == 0 ? "0.00" : "Perfect") :
            String.format(Locale.US, "%.2f", (double)e.kills / e.deaths);
    }
    private static String count(long n) { return String.format(Locale.US, "%,d", n); }
    private static String date(long time) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT, Locale.getDefault()).format(new java.util.Date(time));
    }

    void reload() {
        if (closed || !visible || !activity.launcherVisible()) return;
        // Do not rebuild player pages or video catalogs during live updates.
        if (page == Page.PLAYER) updateDetailStatus();
        if (loading) { dirty = true; return; }
        loading = true;
        new Thread(() -> {
            List<LanBoardProtocol.Entry> result = null;
            LanBoardProtocol.Entry self = null;
            try {
                self = LanBoardIdentity.card(activity, root);
                result = LanBoardStore.read(root);
                final String id = self.id;
                result.removeIf(e -> e.id.equals(id));
                result.add(self);
                LanBoardStore.order(result);
            } catch (IOException | RuntimeException unavailable) { result = null; }
            final List<LanBoardProtocol.Entry> board = result;
            final LanBoardProtocol.Entry local = self;
            activity.runOnUiThread(() -> {
                loading = false;
                if (closed || !visible) return;
                if (board != null) {
                    own = local; shown = board;
                    if (page == Page.BOARD) drawBoard();
                    else if (page == Page.PLAYER) {
                        updateDetailStatus();
                        if (!mine) renderRemoteReport();
                    }
                } else if (page == Page.BOARD && status != null)
                    status.setText("Couldn't read local standings. Saved records kept.");
                if (dirty) { dirty = false; reload(); }
            });
        }, "Halo-board-local").start();
    }

    private void drawBoard() {
        if (page != Page.BOARD || rows == null) return;
        LanBoardPresence.Snapshot presence = snapshot();
        long now = SystemClock.elapsedRealtime();
        boolean fresh = presence.fresh(now);
        live.setText(fresh ? presence.ready() + " LIVE" : "CHECKING…");
        live.setTextColor(fresh && presence.ready() > 0 ? GREEN : HaloTheme.MUTED);
        int oldY = scroll.getScrollY();
        rows.removeAllViews();
        Set<String> known = new HashSet<>();
        int position = 1;
        for (LanBoardProtocol.Entry e : shown) {
            known.add(e.id);
            boolean self = own != null && own.id.equals(e.id);
            LanBoardPresence.Peer peer = presence.byId(e.id, now);
            String name = peer != null ? peer.name : e.name;
            LinearLayout card = box(self);
            LinearLayout row = horizontal();
            LinearLayout identity = horizontal();
            identity.addView(new RankInsignia(activity, rank(e.xp)),
                new LinearLayout.LayoutParams(dp(34), dp(34)));
            LinearLayout names = new LinearLayout(activity);
            names.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = text("#" + position++ + "  " + name + (self ? " (YOU)" : ""),
                14, self ? HaloTheme.FOCUS_TEXT : HaloTheme.TEXT, true);
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            names.addView(nameView);
            names.addView(text(self ? "LOCAL • " + CareerRecords.rankName(rank(e.xp)) :
                peer != null ? "● LIVE • " + peer.location() :
                "○ NOT DETECTED • saved career", 10, peer != null ? GREEN : HaloTheme.MUTED, false));
            identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(identity, new LinearLayout.LayoutParams(0, -2, 3.4f));
            cell(row, e.ratedGames > 0 ? Integer.toString(e.rating) : "—", 1.25f, 14, HaloTheme.TEXT, true);
            cell(row, kd(e), .9f, 13, HaloTheme.TEXT, true);
            cell(row, count(e.wins), .85f, 13, HaloTheme.TEXT, true);
            cell(row, count(e.xp), 1.15f, 13, HaloTheme.TEXT, true);
            row.addView(text("›", 23, HaloTheme.FOCUS_TEXT, true));
            card.addView(row);
            card.setOnClickListener(v -> select(e.id, name, self));
            card.setContentDescription(name + ", " + (self ? "your profile" : "player report"));
            addCard(rows, card);
        }
        if (fresh) for (LanBoardPresence.Peer peer : presence.peers) {
            if (peer.named() && known.contains(peer.id)) continue;
            LinearLayout card = box(false);
            LinearLayout row = horizontal();
            row.addView(text(peer.name + " • " + peer.location(), 14,
                peer.named() ? GREEN : HaloTheme.MUTED, true),
                new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text(peer.ip + " • " + (peer.named() ? "Sync pending" : "discovery only"),
                11, HaloTheme.MUTED, false));
            card.addView(row);
            if (peer.named()) card.setOnClickListener(v -> select(peer.id, peer.name, false));
            addCard(rows, card);
        }
        if (rows.getChildCount() == 0)
            rows.addView(text("Loading players…", 12, HaloTheme.MUTED, false));
        if (!syncing) status.setText(LanShareSettings.career(activity) ?
            "Automatic career sync • select a player for their service record" :
            "Sharing off • saved standings remain available");
        scroll.post(() -> { if (page == Page.BOARD) scroll.scrollTo(0, oldY); });
    }

    private void select(String id, String name, boolean self) {
        if (page == Page.BOARD) boardScroll = scroll.getScrollY();
        selectedId = id; selectedName = name; mine = self;
        buildPlayer();
    }
    private LanBoardProtocol.Entry entry() {
        if (mine) return own;
        for (LanBoardProtocol.Entry e : shown) if (e.id.equals(selectedId)) return e;
        return null;
    }

    private void buildPlayer() {
        begin(Page.PLAYER, selectedName == null ? "SERVICE RECORD" : selectedName,
            mine ? "YOUR UNSC SERVICE RECORD" : "UNSC SERVICE RECORD / LAN PLAYER", this::home);
        detailStatus = text("", 12, HaloTheme.MUTED, true);
        panel.addView(detailStatus);
        updateDetailStatus();
        action(panel, "VIEW HIGHLIGHTS / VIDEOS ›", this::buildVideos);
        if (mine) {
            LinearLayout career = new LinearLayout(activity);
            career.setOrientation(LinearLayout.VERTICAL);
            panel.addView(career);
            // Preserve the complete own career, achievements, history and
            // rename tools; this is now the detail page instead of home.
            CareerProfile.dashboard(activity, root, career);
            return;
        }
        detailBody = new LinearLayout(activity);
        detailBody.setOrientation(LinearLayout.VERTICAL);
        panel.addView(detailBody);
        detailFingerprint = null;
        renderRemoteReport();
    }

    private void renderRemoteReport() {
        if (page != Page.PLAYER || mine || detailBody == null) return;
        LanBoardProtocol.Entry e = entry();
        String fingerprint = e == null ? "pending" : LanBoardProtocol.row(e);
        if (fingerprint.equals(detailFingerprint)) return;
        detailFingerprint = fingerprint;
        detailBody.removeAllViews();
        if (e == null) {
            detailBody.addView(text("Career sync pending. This report updates automatically when stats arrive.",
                13, HaloTheme.MUTED, false));
            return;
        }
        LinearLayout identity = horizontal();
        identity.addView(new RankInsignia(activity, rank(e.xp)),
            new LinearLayout.LayoutParams(dp(66), dp(66)));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(e.name, 22, HaloTheme.TEXT, true));
        titles.addView(text(CareerRecords.rankName(rank(e.xp)) + " • " + count(e.xp) + " XP",
            15, HaloTheme.FOCUS_TEXT, true));
        identity.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        detailBody.addView(identity);
        if (rank(e.xp) + 1 < CareerRecords.rankCount())
            detailBody.addView(text("Next promotion: " +
                count(CareerRecords.rankThreshold(rank(e.xp) + 1) - e.xp) + " XP",
                12, HaloTheme.MUTED, false));
        detailBody.addView(text("CAREER TOTALS", 14, HaloTheme.FOCUS_TEXT, true));
        metricRow("MATCHES", count(e.matches), "WINS", count(e.wins), "WIN RATE",
            e.matches == 0 ? "—" : String.format(Locale.US, "%.0f%%", 100.0 * e.wins / e.matches));
        metricRow("KILLS", count(e.kills), "DEATHS", count(e.deaths), "ASSISTS", count(e.assists));
        metricRow("K/D", kd(e), "PRACTICE SR", e.ratedGames > 0 ? Integer.toString(e.rating) : "UNRATED",
            "ELIGIBLE MATCHES", count(e.ratedGames));
        detailBody.addView(text("Last synced: " + date(e.seenMs), 11, HaloTheme.MUTED, false));
        detailBody.addView(text("Self-reported LAN career summary. Individual matches, accuracy and achievements "
            + "are not transmitted by the current protocol.", 11, HaloTheme.MUTED, false));
    }
    private void metricRow(String a, String av, String b, String bv, String c, String cv) {
        LinearLayout row = horizontal();
        String[] labels = {a,b,c}, values = {av,bv,cv};
        for (int i = 0; i < 3; i++) {
            LinearLayout metric = box(false);
            metric.addView(text(labels[i], 10, HaloTheme.MUTED, true));
            metric.addView(text(values[i], 20, HaloTheme.TEXT, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = i == 2 ? 0 : dp(5);
            row.addView(metric, lp);
        }
        addCard(detailBody, row);
    }
    private void updateDetailStatus() {
        if (page != Page.PLAYER || detailStatus == null) return;
        LanBoardPresence.Peer peer = current(selectedId);
        detailStatus.setText(mine ? "● LOCAL PLAYER" : peer != null ?
            "● LIVE • " + peer.location() + " • " + peer.ip : "○ NOT DETECTED • saved career");
        detailStatus.setTextColor(mine || peer != null ? GREEN : HaloTheme.MUTED);
    }

    private void buildVideos() {
        begin(Page.VIDEOS, (selectedName == null ? "PLAYER" : selectedName) + " / VIDEOS",
            mine ? "SAVED ON THIS PHONE" : "STREAM OVER LAN • NO LOCAL COPY", this::buildPlayer);
        if (mine) action(panel, "MANAGE LOCAL RECORDINGS", () ->
            HaloReelGallery.manage(activity, root, () -> {
                if (!closed && visible && page == Page.VIDEOS && mine) buildVideos();
            }));
        TextView message = text("Loading saved videos…", 13, HaloTheme.MUTED, false);
        panel.addView(message);
        final int request = generation;
        final LanBoardPresence.Peer peer = mine ? null : current(selectedId);
        final boolean local = mine;
        if (!local && (peer == null || !peer.reels)) {
            message.setText("Player not detected, or highlight sharing is off.");
            action(panel, "TRY AGAIN", this::buildVideos);
            return;
        }
        new Thread(() -> {
            File[] clips = null, originals = null;
            List<LanReelProtocol.Reel> remote = null;
            try {
                if (local) {
                    clips = ReelLibrary.finishedReels(root);
                    originals = HaloVideoFiles.rawRecordings(root);
                } else remote = LanReelClient.list(peer);
            } catch (IOException | RuntimeException unavailable) { }
            final File[] saved = clips, raw = originals;
            final List<LanReelProtocol.Reel> found = remote;
            activity.runOnUiThread(() -> {
                if (closed || !visible || generation != request || page != Page.VIDEOS) return;
                panel.removeView(message);
                if (local && saved != null && raw != null) {
                    panel.addView(text("HIGHLIGHT REELS", 13, HaloTheme.FOCUS_TEXT, true));
                    if (saved.length == 0) panel.addView(text("No saved highlights yet.", 12, HaloTheme.MUTED, false));
                    int n = 1;
                    for (File file : saved)
                        videoRow("HIGHLIGHT " + n++, file.length(), file.lastModified(),
                            () -> HaloReelGallery.play(activity, file));
                    if (raw.length > 0) {
                        panel.addView(text("PRIVATE FULL RECORDINGS", 13, HaloTheme.FOCUS_TEXT, true));
                        for (File file : raw)
                            videoRow("ORIGINAL RECORDING", file.length(), file.lastModified(),
                                () -> HaloReelGallery.play(activity, file));
                    }
                } else if (found != null) {
                    if (found.isEmpty()) panel.addView(text("No saved highlights yet.", 12, HaloTheme.MUTED, false));
                    int n = 1;
                    for (LanReelProtocol.Reel clip : found)
                        videoRow("HIGHLIGHT " + n++, clip.size, clip.modified, () -> {
                            LanBoardPresence.Peer latest = current(selectedId);
                            if (latest != null && latest.reels)
                                LanReelPlayer.play(activity, latest, clip);
                            else buildVideos();
                        });
                } else panel.addView(text("Video library unavailable. Check the other phone or try again.",
                    12, HaloTheme.MUTED, false));
                action(panel, "REFRESH VIDEOS", this::buildVideos);
            });
        }, "Halo-video-library").start();
    }
    private void videoRow(String label, long bytes, long modified, Runnable play) {
        LinearLayout card = box(false);
        LinearLayout row = horizontal();
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(text(label, 15, HaloTheme.TEXT, true));
        info.addView(text(date(modified) + " • " +
            String.format(Locale.US, "%.1f MiB", bytes / 1048576.0), 11, HaloTheme.MUTED, false));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("▶ PLAY", play), new LinearLayout.LayoutParams(dp(94), dp(38)));
        card.addView(row);
        card.setOnClickListener(v -> play.run());
        addCard(panel, card);
    }

    private void synchronize() {
        if (!LanShareSettings.career(activity)) {
            status.setText("Enable LAN SHARING to exchange statistics."); return;
        }
        if (syncing) return;
        String ip = hostIp.getText().toString().trim();
        if (!ip.isEmpty() && LanUpdateProtocol.privateAddress(ip) == null) {
            status.setText("Optional peer IP must be a private hotspot address."); return;
        }
        List<String> targets = activity.liveBoardTargets();
        syncing = true; sync.setEnabled(false);
        status.setText("Synchronizing local Halo peers…");
        new Thread(() -> {
            int reached = -1;
            try {
                LanBoardNearby.Result result = LanBoardNearby.refresh(activity, root, ip, targets);
                reached = result.peersReached;
                if (!ip.isEmpty()) activity.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
                    .edit().putString("last_host", ip).apply();
            } catch (IOException | RuntimeException unavailable) { }
            final int found = reached;
            activity.runOnUiThread(() -> {
                syncing = false;
                if (closed || !visible) return;
                if (page == Page.BOARD) {
                    sync.setEnabled(true);
                    status.setText(found < 0 ? "Sync unavailable. Saved stats kept." :
                        found + " peers synchronized.");
                }
                reload();
            });
        }, "Halo-board-peer-refresh").start();
    }
}
