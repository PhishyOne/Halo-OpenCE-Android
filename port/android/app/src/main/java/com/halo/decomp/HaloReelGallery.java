package com.halo.decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.MediaController;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.Locale;

/** In-app player for finished, local MP4 reels. Never sends footage online. */
final class HaloReelGallery {
    private HaloReelGallery() { }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources()
            .getDisplayMetrics().density);
    }

    static void show(LauncherActivity activity, File appRoot) {
        File[] reels = ReelLibrary.finishedReels(appRoot);
        if (reels.length == 0) {
            new AlertDialog.Builder(activity)
                .setTitle("HALO VIDEO HIGHLIGHTS")
                .setMessage("No playable MP4 reel has been saved yet.\n\n"
                    + "In game setup, enable RECORD MATCH VIDEO before Play "
                    + "and approve Android's screen capture prompt. Finish a "
                    + "match with highlight moments, then return here.\n\n"
                    + "Videos are local, silent during this first version, "
                    + "and are never uploaded.")
                .setPositiveButton("CLOSE", null)
                .show();
            return;
        }
        String[] titles = new String[reels.length];
        for (int i = 0; i < reels.length; i++)
            titles[i] = String.format(Locale.US, "REEL %d  •  %.1f MB",
                i + 1, reels[i].length() / 1048576.0);
        new AlertDialog.Builder(activity)
            .setTitle("HALO VIDEO HIGHLIGHTS")
            .setItems(titles, (dialog, which) ->
                play(activity, reels[which]))
            .setNegativeButton("CLOSE", null)
            .show();
    }

    private static void play(LauncherActivity activity, File clip) {
        if (!clip.isFile() || !clip.getName()
                .matches("reel-[0-9a-f]{32}\\.mp4"))
            return;
        Dialog dialog = new Dialog(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 12), dp(activity, 8),
            dp(activity, 12), dp(activity, 10));
        body.setBackground(HaloTheme.surface(
            HaloTheme.PANEL_TOP, HaloTheme.BACKGROUND_BOTTOM, HaloTheme.BORDER,
            7, activity.getResources().getDisplayMetrics().density));
        TextView title = new TextView(activity);
        title.setText("SPARTAN MATCH REEL");
        title.setTextColor(HaloTheme.FOCUS_TEXT);
        title.setTextSize(17);
        HaloTheme.font(title, true);
        body.addView(title);
        TextView note = new TextView(activity);
        note.setText("Real gameplay capture  •  video only  •  local");
        note.setTextColor(HaloTheme.MUTED);
        note.setTextSize(11);
        HaloTheme.font(note, false);
        body.addView(note);

        VideoView player = new VideoView(activity);
        player.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams video = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        video.topMargin = dp(activity, 7);
        video.bottomMargin = dp(activity, 5);
        body.addView(player, video);
        MediaController controls = new MediaController(activity);
        controls.setAnchorView(player);
        player.setMediaController(controls);
        player.setVideoURI(Uri.fromFile(clip));
        player.setOnPreparedListener(mp -> {
            if (dialog.isShowing()) player.start();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            note.setText("This MP4 could not be decoded by the phone.");
            return true;
        });

        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.CENTER);
        Button export = new Button(activity);
        export.setText("SAVE A COPY");
        HaloTheme.menuButtonColors(export);
        HaloTheme.font(export, true);
        export.setOnClickListener(view -> activity.exportReelVideo(clip));
        footer.addView(export, new LinearLayout.LayoutParams(
            0, dp(activity, 43), 1f));
        Button close = new Button(activity);
        close.setText("CLOSE");
        HaloTheme.menuButtonColors(close);
        HaloTheme.font(close, true);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            0, dp(activity, 43), 1f);
        closeParams.leftMargin = dp(activity, 8);
        footer.addView(close, closeParams);
        close.setOnClickListener(view -> dialog.dismiss());
        body.addView(footer);
        dialog.setContentView(body);
        dialog.setOnDismissListener(unused -> {
            controls.hide();
            player.stopPlayback();
        });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(activity.getResources().getDisplayMetrics().widthPixels * .95),
                (int)(activity.getResources().getDisplayMetrics().heightPixels * .94));
        }
    }
}
