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
        // The full original MP4 is the evidence we need when a reel is
        // black or mistimed. Never hide it behind Android/data permissions.
        File[] originals = HaloVideoFiles.rawRecordings(appRoot);
        if (reels.length == 0 && originals.length == 0) {
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
        String[] titles = new String[reels.length + originals.length];
        File[] shown = new File[titles.length];
        for (int i = 0; i < reels.length; i++) {
            titles[i] = String.format(Locale.US, "HIGHLIGHT REEL %d  •  %.1f MB",
                i + 1, reels[i].length() / 1048576.0);
            shown[i] = reels[i];
        }
        for (int i = 0; i < originals.length; i++) {
            int index = reels.length + i;
            titles[index] = String.format(Locale.US,
                "ORIGINAL FULL RECORDING %d  •  %.1f MB",
                i + 1, originals[i].length() / 1048576.0);
            shown[index] = originals[i];
        }
        // AlertDialog message and items are mutually exclusive on
        // Android's default layout. Use only the list so selections
        // stay reachable on short landscape screens.
        new AlertDialog.Builder(activity)
            .setTitle("REELS + ORIGINAL RECORDINGS")
            .setItems(titles, (dialog, which) ->
                play(activity, shown[which]))
            .setNegativeButton("CLOSE", null)
            .show();
    }

    private static void play(LauncherActivity activity, File clip) {
        if (!clip.isFile() || !HaloVideoFiles.validName(clip.getName()) ||
            clip.length() < 2048 ||
            clip.length() > ReelSessionIndex.SOURCE_LIMIT)
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
        boolean original = ReelSessionIndex.safeFileName(clip.getName());
        title.setText(original ? "ORIGINAL GAMEPLAY RECORDING" :
            "SPARTAN MATCH REEL");
        title.setTextColor(HaloTheme.FOCUS_TEXT);
        title.setTextSize(17);
        HaloTheme.font(title, true);
        body.addView(title);
        TextView note = new TextView(activity);
        note.setText(original
            ? "Full original recording • preserved for troubleshooting"
            : "Selected highlight clip • compare with original recording");
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
        // Register callbacks BEFORE setting URI, then start after the
        // dialog/surface exists. Avoid playback's initial-black-frame race.
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

        Button inspect = new Button(activity);
        inspect.setText("CHECK FRAMES");
        HaloTheme.menuButtonColors(inspect);
        HaloTheme.font(inspect, true);
        LinearLayout.LayoutParams inspectParams =
            new LinearLayout.LayoutParams(0, dp(activity, 43), 1f);
        inspectParams.leftMargin = dp(activity, 6);
        footer.addView(inspect, inspectParams);
        inspect.setOnClickListener(view -> {
            inspect.setEnabled(false);
            note.setText("Checking video frames locally...");
            new Thread(() -> {
                VideoCaptureDiagnostic.Result result =
                    VideoCaptureDiagnostic.inspect(clip);
                activity.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    inspect.setEnabled(true);
                    note.setText(result.summary());
                    // A black source is a recording problem; a good source
                    // but black reel is a cutter or playback problem.
                });
            }, "Halo-video-frame-diagnostic").start();
        });

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
        player.setVideoURI(Uri.fromFile(clip));
        player.start(); // queues playback until MediaPlayer prepares surface
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(activity.getResources().getDisplayMetrics().widthPixels * .95),
                (int)(activity.getResources().getDisplayMetrics().heightPixels * .94));
        }
    }
}
