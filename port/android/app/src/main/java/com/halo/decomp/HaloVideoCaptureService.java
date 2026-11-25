package com.halo.decomp;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One explicitly consented MediaProjection per Halo launch. No encoder
 * or video file outside an active, visible competitive multiplayer match.
 * Recorder surfaces are replaced on the same virtual display; the Android
 * 14+ single-use consent token is never reused. All footage stays local. */
public final class HaloVideoCaptureService extends Service {
    static final String ACTION_START = "com.halo.decomp.RECORD_SCREEN";
    static final String ACTION_STOP = "com.halo.decomp.STOP_SCREEN_RECORDING";
    static final String EXTRA_CONSENT_CODE = "consent-code";
    static final String EXTRA_CONSENT_DATA = "consent-intent";
    private static final String TAG = "HaloVideo";
    private static final String CHANNEL = "halo-video";
    private static final int NOTIFICATION_ID = 51;
    private static final int MAX_MINUTES = 20;
    private static final int BIT_RATE = 1_000_000;
    private static final int FRAME_RATE = 20;
    private static final int WIDTH = 640;
    private static final long MIN_FREE = 500L * 1024 * 1024;
    private static final long MAX_RAW_BYTES = 512L * 1024 * 1024;
    private static final long POLL_MS = 250;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private File pending, completed, root, reels;
    private Handler main;
    private ExecutorService worker;
    private String token, attemptedMatch, activeMatch;
    private MatchVideoTimeline.Builder timeline;
    private long armedAt, captureStarted, lastProcessCheck, retryUntil, lastAssembly;
    private int capturedWidth, capturedHeight, densityDpi, emptyGameChecks;
    private boolean armed, stopping, active, gameObserved, keepFull, visibilityBlocked;
    private boolean assemblyBusy, receiverRegistered;
    private final Runnable pollTask = this::poll;
    private final BroadcastReceiver visibility = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!armed || !MatchVideoState.VISIBILITY.equals(intent.getAction()) ||
                !token.equals(intent.getStringExtra(MatchVideoState.TOKEN))) return;
            visibilityBlocked = !intent.getBooleanExtra("visible", false);
            if (visibilityBlocked && active) finishCapture(false);
            main.removeCallbacks(pollTask);
            poll();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Halo-local-reel-muxer"));
        IntentFilter filter = new IntentFilter(MatchVideoState.VISIBILITY);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(visibility, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(visibility, filter);
        receiverRegistered = true;
    }

    @Override public int onStartCommand(Intent command, int flags, int startId) {
        if (command == null) { stopSession(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(command.getAction())) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(command.getAction()) || stopping) {
            if (!armed) stopSession();
            return START_NOT_STICKY;
        }
        int resultCode = command.getIntExtra(EXTRA_CONSENT_CODE, 0);
        Intent consent = command.getParcelableExtra(EXTRA_CONSENT_DATA);
        String nextToken = command.getStringExtra(MatchVideoState.TOKEN);
        if (resultCode != android.app.Activity.RESULT_OK || consent == null ||
            !MatchVideoState.id(nextToken)) {
            if (!armed) stopSession();
            return START_NOT_STICKY;
        }
        if (armed && nextToken.equals(token)) return START_NOT_STICKY;
        try {
            // A new consented launch may replace an older session, but must
            // not reuse its projection token or abandon its active MP4.
            releaseProjection();
            token = nextToken;
            startForegroundCapture();
            armProjection(resultCode, consent);
            main.post(pollTask);
        } catch (Exception error) {
            Log.e(TAG, "Screen recording unavailable; Halo continues normally", error);
            stopSession();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String title, String detail) {
        Intent stop = new Intent(this, HaloVideoCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 51, stop,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP RECORDING", stopAction).build())
            .build();
    }

    private void startForegroundCapture() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Halo video highlights", NotificationManager.IMPORTANCE_LOW));
        Notification n = notification("Halo match recording armed",
            "Waiting for multiplayer. Campaign and lobby are not recorded.");
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void updateNotification(String title, String detail) {
        if (!armed) return;
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                notification(title, detail));
        } catch (RuntimeException ignored) { }
    }

    private void armProjection(int permissionCode, Intent consent) throws IOException {
        root = getExternalFilesDir(null);
        if (root == null) throw new IOException("Halo storage unavailable");
        reels = ReelLibrary.directory(this);
        recoverAbandonedCaptures();
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        int w = Math.max(metrics.widthPixels, metrics.heightPixels);
        int h = Math.min(metrics.widthPixels, metrics.heightPixels);
        capturedWidth = WIDTH;
        capturedHeight = Math.max(240, 16 * Math.round(
            (WIDTH * (float)h / Math.max(1, w)) / 16f));
        densityDpi = metrics.densityDpi;
        keepFull = getSharedPreferences("reel_settings", MODE_PRIVATE)
            .getBoolean("keep_full_match", false);
        MediaProjectionManager projectionManager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = projectionManager.getMediaProjection(permissionCode, consent);
        if (projection == null) throw new IOException("Screen capture permission unavailable");
        final MediaProjection current = projection;
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                if (projection == current) stopSession();
            }
        };
        projection.registerCallback(projectionCallback, main);
        // Exactly one createVirtualDisplay call for each fresh consent.
        // The documented null surface leaves capture idle until a match.
        virtualDisplay = projection.createVirtualDisplay("Halo Gameplay",
            capturedWidth, capturedHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, main);
        if (virtualDisplay == null) throw new IOException("Cannot create video display");
        armedAt = SystemClock.uptimeMillis();
        armed = true;
        stopping = false;
        attemptedMatch = null;
        activeMatch = null;
        visibilityBlocked = false;
        gameObserved = false;
        emptyGameChecks = 0;
        lastProcessCheck = 0;
        retryUntil = armedAt + 30000;
        Log.i(TAG, "Armed for actual competitive multiplayer matches");
    }

    /** Preserve files left by an interrupted recorder. No recorder is
     * active when arming; expose recognized orphans through Manage Videos. */
    private void recoverAbandonedCaptures() {
        File[] abandoned = reels.listFiles(f -> {
            String name = f.getName();
            return name.endsWith(".pending") &&
                ReelSessionIndex.safeFileName(name.substring(0, name.length() - 8)) &&
                Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS);
        });
        if (abandoned == null) return;
        for (File part : abandoned) {
            File raw = new File(reels, part.getName().substring(0, part.getName().length() - 8));
            if (raw.exists()) continue;
            try { Files.move(part.toPath(), raw.toPath()); }
            catch (IOException ignored) { Log.w(TAG, "Interrupted capture retained"); }
        }
    }

    private boolean hasRecordingSpace() {
        if (reels.getUsableSpace() < MIN_FREE) return false;
        File[] raw = reels.listFiles(file -> {
            String name = file.getName();
            return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                (ReelSessionIndex.safeFileName(name) || (name.endsWith(".pending") &&
                    ReelSessionIndex.safeFileName(name.substring(0, name.length() - 8))));
        });
        if (raw == null) return false;
        long total = 0;
        for (File file : raw) {
            total += file.length();
            if (total > MAX_RAW_BYTES - ReelSessionIndex.SOURCE_LIMIT) return false;
        }
        return true;
    }

    private boolean visible() {
        return !visibilityBlocked && MatchVideoState.foreground(root, token);
    }

    private void poll() {
        if (!armed || stopping) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastProcessCheck >= 5000 || lastProcessCheck == 0) {
            lastProcessCheck = now;
            if (!checkGameActivity(now)) { stopSession(); return; }
        }
        MatchVideoState state = MatchVideoState.read(root);
        boolean fresh = state != null && state.fresh(token, now, armedAt);
        boolean foreground = visible();
        if (active) {
            if (fresh && activeMatch.equals(state.id)) {
                timeline.observe(state);
                if (!state.active && foreground) finishCapture(true);
                else if (!foreground) finishCapture(false);
            } else finishCapture(false);
            if (active && (now - captureStarted >= MAX_MINUTES * 60000L ||
                    reels.getUsableSpace() < 200L * 1024 * 1024)) {
                finishCapture(false);
            }
        }
        if (!active && foreground && fresh && state.active &&
            !state.id.equals(attemptedMatch)) {
            attemptedMatch = state.id; // do not start repeatedly on a failed/capped match
            if (!hasRecordingSpace()) {
                Log.w(TAG, "Match recording skipped; insufficient reserved video space");
                updateNotification("Halo recording paused",
                    "Storage limit reached. Delete old recordings in Profile.");
            } else {
                try { beginRecording(state); }
                catch (Exception unavailable) {
                    Log.e(TAG, "Could not start this match recording", unavailable);
                    finishCapture(false);
                    updateNotification("Halo recording unavailable",
                        "This match skipped. Will try the next multiplayer match.");
                }
            }
        }
        if (!active && now <= retryUntil && now - lastAssembly >= 5000) requestAssembly(false);
        main.postDelayed(pollTask, POLL_MS);
    }

    /** Keep one encoder per match and do not consume a second consent. */
    private void beginRecording(MatchVideoState state) throws IOException {
        long proposed = System.currentTimeMillis();
        completed = new File(reels, "session-" + proposed + ".mp4");
        pending = new File(reels, "session-" + proposed + ".mp4.pending");
        if (completed.exists() || pending.exists() ||
            MatchVideoTimeline.metadata(completed).exists()) {
            pending = completed = null;
            throw new IOException("A recording with this timestamp exists");
        }
        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(capturedWidth, capturedHeight);
        recorder.setVideoEncodingBitRate(BIT_RATE);
        recorder.setVideoFrameRate(FRAME_RATE);
        recorder.setMaxDuration(MAX_MINUTES * 60_000);
        recorder.setMaxFileSize(ReelSessionIndex.SOURCE_LIMIT);
        recorder.setOutputFile(pending.getAbsolutePath());
        final MediaRecorder current = recorder;
        recorder.setOnInfoListener((source, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                main.post(() -> { if (recorder == current) finishCapture(false); });
        });
        recorder.setOnErrorListener((source, what, extra) ->
            main.post(() -> { if (recorder == current) finishCapture(false); }));
        recorder.prepare();
        MatchVideoState latest = MatchVideoState.read(root);
        long now = SystemClock.uptimeMillis();
        if (!armed || !visible() || latest == null || !latest.active ||
            !latest.id.equals(state.id) || !latest.fresh(token, now, armedAt))
            throw new IOException("Match changed during encoder preparation");
        recorderSurface = recorder.getSurface();
        virtualDisplay.setSurface(recorderSurface);
        recorder.start();
        captureStarted = SystemClock.uptimeMillis();
        activeMatch = state.id;
        timeline = new MatchVideoTimeline.Builder(activeMatch, keepFull, captureStarted);
        timeline.observe(state);
        timeline.observe(latest);
        active = true;
        updateNotification("Halo is recording a multiplayer match",
            "Local silent video. Max 20 minutes / 160 MB per match.");
        Log.i(TAG, "Recording actual multiplayer match " + activeMatch);
    }

    private boolean checkGameActivity(long now) {
        boolean present = false, known = false;
        try {
            ActivityManager manager = getSystemService(ActivityManager.class);
            java.util.List<ActivityManager.RunningAppProcessInfo> processes =
                manager == null ? null : manager.getRunningAppProcesses();
            if (processes != null) {
                known = true;
                for (ActivityManager.RunningAppProcessInfo process : processes)
                    if (process.uid == android.os.Process.myUid() &&
                        (getPackageName() + ":game").equals(process.processName)) {
                        present = true; break;
                    }
            }
        } catch (RuntimeException ignored) { }
        if (!known) return true;
        if (present) { gameObserved = true; emptyGameChecks = 0; }
        else if (++emptyGameChecks >= 2 &&
            (gameObserved || now - armedAt > 30000L)) return false;
        return true;
    }

    private long videoDuration(File source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(source.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") &&
                    format.containsKey(MediaFormat.KEY_DURATION))
                    return format.getLong(MediaFormat.KEY_DURATION) / 1000;
            }
            throw new IOException("Recorded MP4 has no timed video track");
        } finally { extractor.release(); }
    }

    private void finishCapture(boolean normal) {
        if (recorder == null && pending == null) return;
        boolean wasActive = active;
        long elapsed = wasActive ? SystemClock.uptimeMillis() - captureStarted : 0;
        active = false;
        boolean finalized = false;
        try {
            if (virtualDisplay != null) {
                try { virtualDisplay.setSurface(null); }
                catch (RuntimeException detached) { normal = false; }
            }
            if (recorder != null && wasActive) {
                try { recorder.stop(); finalized = true; }
                catch (RuntimeException incomplete) {
                    Log.w(TAG, "Could not finalize full match; keeping file", incomplete);
                }
            }
        } finally {
            if (recorder != null) {
                try { recorder.reset(); } catch (RuntimeException ignored) { }
                try { recorder.release(); } catch (RuntimeException ignored) { }
                recorder = null;
            }
            if (recorderSurface != null) {
                recorderSurface.release();
                recorderSurface = null;
            }
        }
        File source = pending, output = completed;
        MatchVideoTimeline.Builder finished = timeline;
        pending = null; completed = null; timeline = null; activeMatch = null;
        // Even failed/non-playable captures are preserved and exposed for
        // manual deletion. Never delete a source simply because muxing failed.
        if (source != null && output != null && source.isFile() && !output.exists()) {
            try {
                Files.move(source.toPath(), output.toPath());
                if (finalized && output.length() >= 2048 &&
                    output.length() <= ReelSessionIndex.SOURCE_LIMIT && finished != null) {
                    long duration = videoDuration(output);
                    boolean trustworthy = normal && Math.abs(duration - elapsed) <= 2000;
                    finished.finish(output, duration, trustworthy).write(output);
                }
            } catch (IOException | RuntimeException invalid) {
                Log.w(TAG, "Original kept; match metadata or validation failed", invalid);
            }
        }
        retryUntil = SystemClock.uptimeMillis() + 45000;
        lastAssembly = 0;
        updateNotification("Halo match recording armed",
            normal ? "Processing highlights. Waiting for the next match."
                : "Recording retained if incomplete. Waiting for the next match.");
        requestAssembly(false);
    }

    private void requestAssembly(boolean finalPass) {
        if (root == null || worker == null || worker.isShutdown() ||
            (!finalPass && assemblyBusy)) return;
        File data = root;
        assemblyBusy = true;
        lastAssembly = SystemClock.uptimeMillis();
        worker.execute(() -> {
            try {
                int count = ReelLibrary.assemble(data);
                if (count > 0) Log.i(TAG, "Rendered " + count + " new local video reels");
            } finally { main.post(() -> assemblyBusy = false); }
        });
    }

    private void releaseProjection() {
        armed = false;
        main.removeCallbacks(pollTask);
        finishCapture(false);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            if (projectionCallback != null) {
                try { old.unregisterCallback(projectionCallback); }
                catch (RuntimeException ignored) { }
            }
            projectionCallback = null;
            try { old.stop(); } catch (RuntimeException ignored) { }
        }
    }

    private void stopSession() {
        if (stopping) return;
        stopping = true;
        releaseProjection();
        requestAssembly(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (!stopping) {
            stopping = true;
            releaseProjection();
            requestAssembly(true);
        }
        if (receiverRegistered) {
            unregisterReceiver(visibility);
            receiverRegistered = false;
        }
        worker.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
