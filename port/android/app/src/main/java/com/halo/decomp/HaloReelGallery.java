package com.halo.decomp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** In-app player for finished, local MP4 reels. Never sends footage online. */
final class HaloReelGallery {
    private HaloReelGallery() { }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources()
            .getDisplayMetrics().density);
    }

    static void show(LauncherActivity activity, File appRoot) {
        if (appRoot == null) return;
        File[] reels = ReelLibrary.finishedReels(appRoot);
        // The full original MP4 is the evidence we need when a reel is
        // black or mistimed. Never hide it behind Android/data permissions.
        File[] originals = HaloVideoFiles.rawRecordings(appRoot);
        if (reels.length == 0 && originals.length == 0) {
            new HaloDialog.Builder(activity)
                .setTitle("HALO VIDEO HIGHLIGHTS")
                .setMessage("No playable MP4 reel has been saved yet.\n\n"
                    + "In game setup, enable RECORD MATCH VIDEO before Play "
                    + "and approve Android's screen capture prompt. Finish a "
                    + "match with highlight moments, then return here.\n\n"
                    + activity.getSharedPreferences("reel_settings",Activity.MODE_PRIVATE)
                        .getString("last_recording_status","No recording status reported yet."))
                .setNeutralButton("MANAGE VIDEOS", (d, which) -> manage(activity, appRoot))
                .setPositiveButton("CLOSE", null)
                .show();
            return;
        }
        String[] titles = new String[reels.length + originals.length];
        File[] shown = new File[titles.length];
        for (int i = 0; i < reels.length; i++) {
            titles[i] = String.format(Locale.US, reels[i].getName().startsWith("ending-") ?
                "ROUND ENDING %d  •  %.1f MB" : "HIGHLIGHT REEL %d  •  %.1f MB",
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
        // HaloDialog message and items are mutually exclusive on
        // Android's default layout. Use only the list so selections
        // stay reachable on short landscape screens.
        new HaloDialog.Builder(activity)
            .setTitle("REELS + ORIGINAL RECORDINGS")
            .setItems(titles, (dialog, which) ->
                play(activity, shown[which]))
            .setNeutralButton("MANAGE VIDEOS", (d, which) -> manage(activity, appRoot))
            .setNegativeButton("CLOSE", null)
            .show();
    }

    private static String size(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void manage(LauncherActivity activity, File appRoot) {
        manage(activity, appRoot, () -> show(activity, appRoot));
    }

    static void manage(LauncherActivity activity, File appRoot, Runnable onDone) {
        HaloVideoFiles.Entry[] entries = HaloVideoFiles.deletable(appRoot);
        if (entries.length == 0) {
            new HaloDialog.Builder(activity)
                .setTitle("MANAGE VIDEOS")
                .setMessage("No completed local recordings to delete.")
                .setPositiveButton("CLOSE", null).show();
            return;
        }
        String[] labels = new String[entries.length];
        boolean[] checked = new boolean[entries.length];
        long total = 0;
        for (int i = 0; i < entries.length; i++) {
            HaloVideoFiles.Entry entry = entries[i];
            total += entry.bytes;
            labels[i] = (entry.original() ? "ORIGINAL RECORDING" : "HIGHLIGHT REEL")
                + "  •  " + size(entry.bytes) + "\n"
                + java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(new java.util.Date(entry.modified));
        }
        HaloDialog manager = new HaloDialog.Builder(activity)
            .setTitle("MANAGE VIDEOS  •  " + size(total))
            .setMultiChoiceItems(labels, checked, (d, which, selected) ->
                checked[which] = selected)
            .setPositiveButton("DELETE SELECTED", null)
            .setNeutralButton("DELETE ALL", null)
            .setNegativeButton("CANCEL", null)
            .create();
        manager.show();
        Button selectedButton = manager.getButton(HaloDialog.BUTTON_POSITIVE);
        selectedButton.setOnClickListener(view -> {
            List<HaloVideoFiles.Entry> selected = new ArrayList<>();
            for (int i = 0; i < entries.length; i++)
                if (checked[i]) selected.add(entries[i]);
            if (selected.isEmpty()) {
                HaloNotice.makeText(activity, "Select at least one video.",
                    HaloNotice.LENGTH_SHORT).show();
                return;
            }
            confirmDelete(activity, appRoot, manager,
                selected.toArray(new HaloVideoFiles.Entry[0]), onDone);
        });
        manager.getButton(HaloDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
            confirmDelete(activity, appRoot, manager, entries, onDone));
    }

    private static void confirmDelete(LauncherActivity activity, File appRoot,
            HaloDialog manager, HaloVideoFiles.Entry[] confirmed, Runnable onDone) {
        long bytes = 0;
        boolean originals = false;
        for (HaloVideoFiles.Entry entry : confirmed) {
            bytes += entry.bytes;
            originals |= entry.original();
        }
        new HaloDialog.Builder(activity)
            .setTitle("PERMANENTLY DELETE " + confirmed.length + " VIDEO(S)?")
            .setMessage("Recover up to " + size(bytes) + ".\n\n"
                + (originals ? "Deleted full recordings cannot be used to create more reels.\n" : "")
                + "Existing unselected videos, career stats, maps and exported copies are kept. "
                + "Deleted reels will not be automatically rebuilt.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE", (d, which) -> {
                manager.dismiss();
                HaloDialog working = new HaloDialog.Builder(activity)
                    .setMessage("Removing selected local videos...")
                    .setCancelable(false).create();
                working.show();
                new Thread(() -> {
                    HaloVideoFiles.DeleteResult result = ReelLibrary.deleteVideos(appRoot, confirmed);
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        working.dismiss();
                        new HaloDialog.Builder(activity)
                            .setTitle("VIDEO STORAGE")
                            .setMessage("Deleted " + result.removed + " video(s). Freed "
                                + size(result.bytes) + "."
                                + (result.skipped > 0 ? "\n" + result.skipped
                                    + " file(s) could not be deleted or changed. Refresh and try again." : ""))
                            .setPositiveButton("DONE", (dialog, button) -> onDone.run())
                            .show();
                    });
                }, "Halo-delete-local-videos").start();
            }).show();
    }

    static void play(LauncherActivity activity, File clip) {
        if (!clip.isFile() || !HaloVideoFiles.validName(clip.getName()) ||
            clip.length() < 2048 ||
            clip.length() > ReelSessionIndex.SOURCE_LIMIT)
            return;
        Dialog dialog = new HaloWindow(activity);
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

        // The video SurfaceView must be transparent to its own surface.
        // Black belongs behind it, not on the VideoView itself.
        FrameLayout viewport = new FrameLayout(activity);
        viewport.setBackgroundColor(Color.BLACK);
        VideoView player = new VideoView(activity);
        player.setBackground(null);
        viewport.addView(player, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        LinearLayout.LayoutParams video = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        video.topMargin = dp(activity, 7);
        video.bottomMargin = dp(activity, 5);
        body.addView(viewport, video);

        TextView playbackNote = new TextView(activity);
        playbackNote.setTextColor(HaloTheme.MUTED);
        playbackNote.setTextSize(11);
        HaloTheme.font(playbackNote, false);
        body.addView(playbackNote);

        LinearLayout transport = new LinearLayout(activity);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        Button toggle = transportButton(activity, "PAUSE");
        transport.addView(toggle, new LinearLayout.LayoutParams(
            dp(activity, 92), dp(activity, 40)));
        SeekBar seek = new SeekBar(activity);
        seek.setContentDescription("Video position");
        transport.addView(seek, new LinearLayout.LayoutParams(
            0, dp(activity, 40), 1f));
        TextView time = new TextView(activity);
        time.setTextColor(HaloTheme.TEXT);
        time.setTextSize(12);
        time.setGravity(Gravity.CENTER);
        HaloTheme.font(time, false);
        transport.addView(time, new LinearLayout.LayoutParams(
            dp(activity, 100), dp(activity, 40)));
        Button fullscreen = transportButton(activity, "FULLSCREEN");
        transport.addView(fullscreen, new LinearLayout.LayoutParams(
            dp(activity, 116), dp(activity, 40)));
        body.addView(transport);

        Playback playback = new Playback(player, toggle, seek, time, playbackNote);
        player.setOnPreparedListener(playback::prepared);
        player.setOnInfoListener(playback::info);
        player.setOnErrorListener(playback::error);
        player.setOnCompletionListener(mp -> playback.completed());

        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.CENTER);
        Button export = new Button(activity);
        export.setText("SAVE A COPY");
        HaloTheme.menuButtonColors(export);
        HaloTheme.font(export, true);
        export.setOnClickListener(view -> {
            playback.pauseForExport();
            activity.exportReelVideo(clip);
        });
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
        dialog.setOnDismissListener(unused -> playback.close());
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            // Use an opaque dialog window so SurfaceView composition does
            // not depend on a translucent floating window.
            w.setBackgroundDrawable(new ColorDrawable(HaloTheme.BACKGROUND_BOTTOM));
            resize(activity, w, false);
            final int normalSystemUi = w.getDecorView().getSystemUiVisibility();
            fullscreen.setOnClickListener(view -> {
                boolean full = !fullscreen.isSelected();
                fullscreen.setSelected(full);
                fullscreen.setText(full ? "EXIT FULL" : "FULLSCREEN");
                title.setVisibility(full ? View.GONE : View.VISIBLE);
                note.setVisibility(full ? View.GONE : View.VISIBLE);
                footer.setVisibility(full ? View.GONE : View.VISIBLE);
                resize(activity, w, full);
                w.getDecorView().setSystemUiVisibility(full ?
                    normalSystemUi | View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : normalSystemUi);
            });
        } else {
            fullscreen.setEnabled(false);
        }
        player.setVideoURI(Uri.fromFile(clip));
        player.start(); // queues playback until MediaPlayer prepares surface
        playback.begin();
    }

    private static Button transportButton(Activity activity, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(12);
        button.setPadding(dp(activity, 3), 0, dp(activity, 3), 0);
        HaloTheme.menuButtonColors(button);
        HaloTheme.font(button, true);
        return button;
    }

    private static void resize(Activity activity, Window window, boolean full) {
        window.setLayout(full ? WindowManager.LayoutParams.MATCH_PARENT :
            (int)(activity.getResources().getDisplayMetrics().widthPixels * .95),
            full ? WindowManager.LayoutParams.MATCH_PARENT :
            (int)(activity.getResources().getDisplayMetrics().heightPixels * .94));
    }

    /** All player state and controls stay on the main thread and are
     * scoped to this one dialog. A recreated surface resumes the last
     * known position; closing the dialog releases the player and ticker. */
    private static final class Playback implements SurfaceHolder.Callback {
        final VideoView player;
        final Button toggle;
        final SeekBar seek;
        final TextView time, status;
        final Handler handler = new Handler(Looper.getMainLooper());
        boolean ready, closed, failed, dragging, seeking, ended, rendered;
        boolean wantsPlay = true;
        int duration, position;
        long firstFrameDeadline;
        final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (closed) return;
                if (ready && !failed) {
                    if (!ended && !dragging && !seeking) {
                        int current = player.getCurrentPosition();
                        position = Math.max(0, Math.min(duration, current));
                        updateTime(position);
                    }
                    if (wantsPlay && !ended && !rendered &&
                        firstFrameDeadline != 0 &&
                        SystemClock.elapsedRealtime() >= firstFrameDeadline) {
                        firstFrameDeadline = 0;
                        status.setText("No first-frame signal yet. You can save a copy.");
                    }
                }
                handler.postDelayed(this, 250);
            }
        };

        Playback(VideoView player, Button toggle, SeekBar seek,
                 TextView time, TextView status) {
            this.player = player;
            this.toggle = toggle;
            this.seek = seek;
            this.time = time;
            this.status = status;
            toggle.setEnabled(false);
            seek.setEnabled(false);
            updateTime(0);
            status.setText("Preparing video...");
            player.getHolder().addCallback(this);
            toggle.setOnClickListener(view -> togglePlay());
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                }
                @Override public void onProgressChanged(SeekBar bar, int value,
                                                        boolean fromUser) {
                    if (!fromUser) return;
                    time.setText(clock(value) + " / " + clock(duration));
                    if (!dragging) requestSeek(value);
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    requestSeek(bar.getProgress());
                }
            });
        }

        void begin() {
            handler.post(ticker);
        }

        void prepared(MediaPlayer mp) {
            if (closed || failed) return;
            duration = Math.max(0, player.getDuration());
            position = Math.min(duration, position);
            seek.setMax(Math.max(1, duration));
            ready = true;
            rendered = false;
            toggle.setEnabled(true);
            seek.setEnabled(duration > 0);
            mp.setOnSeekCompleteListener(unused -> {
                if (!closed && ready) seeking = false;
            });
            if (position > 0 && !ended) requestSeek(position);
            updateTime(position);
            if (wantsPlay && !ended) start();
            else {
                player.pause();
                toggle.setText(ended ? "REPLAY" : "PLAY");
                status.setText(ended ? "Playback complete" : "Paused");
            }
        }

        boolean info(MediaPlayer mp, int what, int extra) {
            if (closed || failed) return false;
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                rendered = true;
                firstFrameDeadline = 0;
                status.setText(wantsPlay ? "Playing" : "Paused");
                return true;
            }
            return false;
        }

        boolean error(MediaPlayer mp, int what, int extra) {
            if (closed) return true;
            failed = true;
            ready = false;
            toggle.setEnabled(false);
            seek.setEnabled(false);
            status.setText("Playback error (" + what + "/" + extra +
                "). Save a copy to try another player.");
            return true;
        }

        void start() {
            wantsPlay = true;
            player.start();
            toggle.setText("PAUSE");
            status.setText(rendered ? "Playing" : "Waiting for first video frame...");
            firstFrameDeadline = rendered ? 0 :
                SystemClock.elapsedRealtime() + 4000;
        }

        void togglePlay() {
            if (!ready || failed || closed) return;
            if (wantsPlay && !ended) {
                wantsPlay = false;
                player.pause();
                toggle.setText("PLAY");
                status.setText("Paused");
            } else {
                if (ended) requestSeek(0);
                start();
            }
        }

        void pauseForExport() {
            if (closed || failed) return;
            wantsPlay = false;
            // Also clears VideoView's queued start while still preparing.
            player.pause();
            toggle.setText(ended ? "REPLAY" : "PLAY");
            if (ready) status.setText(ended ? "Playback complete" : "Paused");
        }

        void requestSeek(int ms) {
            if (!ready || closed || failed || duration <= 0) return;
            position = Math.max(0, Math.min(duration, ms));
            ended = false;
            seeking = true;
            player.seekTo(position);
            if (!wantsPlay) toggle.setText("PLAY");
            updateTime(position);
        }

        void completed() {
            if (closed || failed) return;
            wantsPlay = false;
            ended = true;
            seeking = false;
            position = duration;
            updateTime(position);
            toggle.setText("REPLAY");
            status.setText("Playback complete");
        }

        void updateTime(int ms) {
            if (!dragging) seek.setProgress(ms);
            time.setText(clock(ms) + " / " + clock(duration));
        }

        private static String clock(int ms) {
            int seconds = Math.max(0, ms / 1000);
            return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {
            if (!closed && !failed) status.setText("Preparing video...");
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format,
                                             int width, int height) { }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            if (closed || failed) return;
            ready = false;
            rendered = false;
            seeking = false;
            dragging = false;
            firstFrameDeadline = 0;
            toggle.setEnabled(false);
            seek.setEnabled(false);
        }

        void close() {
            closed = true;
            handler.removeCallbacks(ticker);
            player.getHolder().removeCallback(this);
            player.stopPlayback();
        }
    }
}
