package com.halo.decomp;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Small Halo-style LAN standings view. Not a server or competitive ladder. */
final class LanBoardUi {
    private final Activity activity;
    private final File root;
    private final LinearLayout rows;
    private final TextView status;
    private final EditText hostIp;
    private final Button sync;
    private final CheckBox sharing;
    private final Dialog dialog;
    private final String localId;

    private LanBoardUi(Activity activity, File root) throws IOException {
        this.activity = activity;
        this.root = root;
        this.localId = LanBoardIdentity.id(activity);
        dialog = new Dialog(activity);
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(14), dp(10), dp(14), dp(9));
        wrapper.setBackground(HaloTheme.surface(
            HaloTheme.PANEL_TOP, HaloTheme.BACKGROUND_BOTTOM,
            HaloTheme.BORDER, 7, activity.getResources().getDisplayMetrics().density));

        TextView title = text("LAN LEADERBOARD", 19, HaloTheme.FOCUS_TEXT, true);
        wrapper.addView(title);
        wrapper.addView(text("PRACTICE SR / CAREER  •  PHONE-TO-PHONE", 11,
            HaloTheme.MUTED, false));
        wrapper.addView(text("Unverified: each phone reports its own "
            + "career. No central server or authenticated match receipts.", 11,
            HaloTheme.WARNING, false));
        // Initialize immutable UI status before registering a checkbox
        // listener; Java checks definite assignment for captured finals.
        status = text("Loading local standings...", 11,
            HaloTheme.MUTED, false);
        sharing = new CheckBox(activity);
        sharing.setText("SHARE MY CAREER WITH NEARBY HALO PHONES");
        sharing.setTextColor(HaloTheme.TEXT);
        sharing.setTextSize(12);
        HaloTheme.font(sharing, true);
        sharing.setButtonTintList(HaloTheme.CHECK_TINT);
        sharing.setChecked(activity.getSharedPreferences("launcher_ui",
            Context.MODE_PRIVATE).getBoolean("lan_board_share", false));
        sharing.setOnCheckedChangeListener((button, enabled) -> {
            activity.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
                .edit().putBoolean("lan_board_share", enabled).apply();
            if (activity instanceof LauncherActivity)
                ((LauncherActivity)activity).refreshBoardSharing();
            if (enabled) synchronize();
            else status.setText("Sharing off. Existing saved standings remain "
                + "on this phone.");
        });
        wrapper.addView(sharing);

        ScrollView scroll = new ScrollView(activity);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(6), 0, dp(6));
        scroll.addView(rows);
        wrapper.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        wrapper.addView(status);
        LinearLayout action = new LinearLayout(activity);
        action.setGravity(Gravity.CENTER_VERTICAL);
        hostIp = new EditText(activity);
        hostIp.setSingleLine(true);
        hostIp.setTextSize(13);
        hostIp.setInputType(InputType.TYPE_CLASS_PHONE);
        hostIp.setTextColor(HaloTheme.TEXT);
        hostIp.setHintTextColor(HaloTheme.MUTED);
        hostIp.setHint("Peer IP (optional)");
        SharedPreferences prefs = activity.getSharedPreferences(
            "launcher_ui", Context.MODE_PRIVATE);
        String remembered = prefs.getString("last_host", "");
        try {
            LanConfig.Settings settings =
                LanConfig.load(new File(root, "config.toml"));
            if (LanUpdateProtocol.privateAddress(settings.peer) != null)
                remembered = settings.peer;
        } catch (IOException ignored) { }
        if (LanUpdateProtocol.privateAddress(remembered) != null)
            hostIp.setText(remembered);
        action.addView(hostIp, new LinearLayout.LayoutParams(0, dp(43), 2f));

        sync = new Button(activity);
        sync.setText("REFRESH LAN");
        sync.setAllCaps(false);
        HaloTheme.menuButtonColors(sync);
        HaloTheme.font(sync, true);
        LinearLayout.LayoutParams syncParams =
            new LinearLayout.LayoutParams(0, dp(43), 2f);
        syncParams.leftMargin = dp(7);
        action.addView(sync, syncParams);
        sync.setOnClickListener(view -> synchronize());
        Button close = new Button(activity);
        close.setText("CLOSE");
        close.setAllCaps(false);
        HaloTheme.menuButtonColors(close);
        HaloTheme.font(close, true);
        LinearLayout.LayoutParams closeParams =
            new LinearLayout.LayoutParams(0, dp(43), 1f);
        closeParams.leftMargin = dp(7);
        action.addView(close, closeParams);
        close.setOnClickListener(view -> dialog.dismiss());
        wrapper.addView(action);
        dialog.setContentView(wrapper);
        showCached();
    }

    static void show(Activity activity, File root) {
        if (activity == null || root == null ||
            activity.isFinishing() || activity.isDestroyed()) return;
        try {
            LanBoardUi ui = new LanBoardUi(activity, root);
            ui.dialog.show();
            Window window = ui.dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout((int)(activity.getResources()
                    .getDisplayMetrics().widthPixels * .96f),
                    (int)(activity.getResources()
                    .getDisplayMetrics().heightPixels * .94f));
            }
        } catch (IOException failure) {
            android.widget.Toast.makeText(activity, failure.getMessage(),
                android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources()
            .getDisplayMetrics().density);
    }
    private TextView text(String content, int size, int color, boolean heading) {
        TextView item = new TextView(activity);
        item.setText(content);
        item.setTextSize(size);
        item.setTextColor(color);
        item.setPadding(dp(3), dp(3), dp(3), dp(3));
        HaloTheme.font(item, heading);
        return item;
    }

    private void showCached() {
        new Thread(() -> {
            try {
                LanBoardProtocol.Entry local = LanBoardIdentity.card(activity, root);
                List<LanBoardProtocol.Entry> board = LanBoardStore.merge(
                    root, local, null, System.currentTimeMillis());
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        draw(board, "Saved on this phone. Searching nearby "
                            + "peers when sharing is enabled.");
                        if (sharing.isChecked()) synchronize();
                    }
                });
            } catch (IOException failure) {
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing())
                        status.setText("Couldn't load board: " + failure.getMessage());
                });
            }
        }, "Halo-board-local").start();
    }

    private void draw(List<LanBoardProtocol.Entry> board, String message) {
        rows.removeAllViews();
        int place = 1;
        for (LanBoardProtocol.Entry e : board) {
            boolean mine = e.id.equals(localId);
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(9), dp(6), dp(9), dp(6));
            card.setBackground(HaloTheme.surface(
                mine ? HaloTheme.CONTROL_TOP : HaloTheme.PANEL_TOP,
                HaloTheme.PANEL_BOTTOM,
                mine ? HaloTheme.FOCUS : HaloTheme.BORDER,
                5, activity.getResources().getDisplayMetrics().density));
            String rating = e.ratedGames > 0 ? Integer.toString(e.rating) : "—";
            card.addView(text(String.format(Locale.US,
                "#%d  %s%s     PRACTICE SR %s",
                place++, e.name, mine ? " (YOU)" : "", rating),
                15, mine ? HaloTheme.FOCUS_TEXT : HaloTheme.TEXT, true));
            double winRate = e.matches > 0 ?
                100.0 * e.wins / e.matches : 0.0;
            String kd = e.deaths == 0
                ? (e.kills == 0 ? "0.00" : "Perfect")
                : String.format(Locale.US,"%.2f",
                    (double)e.kills / e.deaths);
            card.addView(text(String.format(Locale.US,
                "XP %,d  •  W %,d / M %,d (%.0f%%)  •  Eligible %d",
                e.xp, e.wins, e.matches, winRate, e.ratedGames),
                11, HaloTheme.MUTED, false));
            card.addView(text(String.format(Locale.US,
                "K %,d  •  D %,d  •  A %,d  •  K/D %s",
                e.kills, e.deaths, e.assists, kd),
                11, HaloTheme.MUTED, false));
            if (!mine) {
                long ageMinutes = Math.max(0,
                    (System.currentTimeMillis() - e.seenMs) / 60000);
                card.addView(text(ageMinutes < 2
                    ? "Recently seen on LAN"
                    : "Cached peer data • " + ageMinutes + " min old",
                    10, HaloTheme.MUTED, false));
            }
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            layout.bottomMargin = dp(6);
            rows.addView(card, layout);
        }
        if (board.isEmpty()) rows.addView(text("No saved careers yet", 13,
            HaloTheme.MUTED, false));
        status.setText(message);
    }

    private void synchronize() {
        if (!sharing.isChecked()) {
            status.setText("Enable sharing to exchange stats with nearby "
                + "phones. Your career never leaves this device otherwise.");
            return;
        }
        String ip = hostIp.getText().toString().trim();
        if (!ip.isEmpty() && LanUpdateProtocol.privateAddress(ip) == null) {
            status.setText("Optional peer IP must be a private hotspot address.");
            return;
        }
        sync.setEnabled(false);
        status.setText("Scanning nearby Halo phones over local Wi-Fi...");
        new Thread(() -> {
            try {
                LanBoardNearby.Result result =
                    LanBoardNearby.refresh(activity, root, ip);
                if (!ip.isEmpty()) {
                    activity.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
                        .edit().putString("last_host", ip).apply();
                }
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing())
                        draw(result.board, result.peersReached == 0
                            ? "No other opted-in Halo phone answered. "
                              + "Both devices must be on the same hotspot."
                            : result.peersReached + " Halo peers exchanged "
                              + "local career summaries. Tap refresh to update.");
                    sync.setEnabled(true);
                });
            } catch (IOException failure) {
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing())
                        status.setText("No nearby peer answered. Try its private "
                            + "hotspot IP, or check SHARE MY CAREER "
                            + "on that phone.");
                    sync.setEnabled(true);
                });
            }
        }, "Halo-board-peer-refresh").start();
    }
}
