package com.halo.decomp;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.graphics.PixelFormat;
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
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Opt-in real H.264 gameplay footage, using a single Android-approved
 * MediaProjection and hardware encoder. No camera, mic, network or upload.
 * The original MP4 is kept while the bounded highlight reel is cut.
 *
 * This foreground service belongs to the launcher process, never :game,
 * to avoid bloating Halo's native emulator heap. */
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

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private File pending;
    private File completed;
    private Handler main;
    private long startedWallMs;
    private long startedUptime;
    private int capturedWidth, capturedHeight;
    private boolean active, stopping, gameObserved;
    private int emptyGameChecks;

    @Override public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
    }

    @Override public int onStartCommand(Intent command, int flags, int startId) {
        if (command == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(command.getAction())) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(command.getAction()) || active || stopping) {
            if (!active) stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = command.getIntExtra(EXTRA_CONSENT_CODE, 0);
        Intent consent = command.getParcelableExtra(EXTRA_CONSENT_DATA);
        if (resultCode != android.app.Activity.RESULT_OK || consent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForegroundCapture();
            beginRecording(resultCode, consent);
            main.postDelayed(this::checkGameActivity, 15_000);
        } catch (Exception error) {
            Log.e(TAG, "Screen recording unavailable; Halo continues normally", error);
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCapture() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
            "Halo video highlights", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, HaloVideoCaptureService.class)
            .setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 51, stop,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Halo is recording gameplay video")
            .setContentText("Opt-in, silent capture. Max 20 min / 160 MB.")
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP RECORDING", stopAction).build())
            .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void beginRecording(int permissionCode, Intent consent) throws IOException {
        File reels = ReelLibrary.directory(this);
        if (reels.getUsableSpace() < MIN_FREE)
            throw new IOException("Insufficient free space for optional video recording");
        File[] older = reels.listFiles(file -> file.isFile() &&
            ReelSessionIndex.safeFileName(file.getName()));
        if (older != null && older.length >= 2 &&
            ReelLibrary.finishedReels(getExternalFilesDir(null)).length == 0)
            throw new IOException("Two raw captures already exist; export a reel "
                + "or clear old recordings before recording another");
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        int w = Math.max(metrics.widthPixels, metrics.heightPixels);
        int h = Math.min(metrics.widthPixels, metrics.heightPixels);
        capturedWidth = WIDTH;
        capturedHeight = Math.max(240, 16 * Math.round(
            (WIDTH * (float)h / Math.max(1, w)) / 16f));
        long proposed = System.currentTimeMillis();
        completed = new File(reels, "session-" + proposed + ".mp4");
        pending = new File(reels, "session-" + proposed + ".mp4.pending");
        if (completed.exists() || pending.exists())
            throw new IOException("A recording with this timestamp already exists");

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
        recorder.setOnInfoListener((source, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                main.post(this::stopRecording);
        });
        recorder.setOnErrorListener((source, what, extra) ->
            main.post(this::stopRecording));
        recorder.prepare();

        MediaProjectionManager projectionManager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = projectionManager.getMediaProjection(permissionCode, consent);
        if (projection == null) throw new IOException("Screen capture permission unavailable");
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                main.post(HaloVideoCaptureService.this::stopRecording);
            }
        };
        projection.registerCallback(projectionCallback, main);
        virtualDisplay = projection.createVirtualDisplay("Halo Gameplay",
            capturedWidth, capturedHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.getSurface(), null, main);
        if (virtualDisplay == null)
            throw new IOException("Cannot create video capture display");
        recorder.start();
        startedWallMs = System.currentTimeMillis();
        startedUptime = SystemClock.elapsedRealtime();
        active = true;
        Log.i(TAG, "Recording actual screen frames with hardware H.264 encoder");
    }

    /** A game can terminate its native process without a normal Activity
     * onDestroy. Stop recording after it is no longer running. */
    private void checkGameActivity() {
        if (!active || stopping) return;
        boolean present = false, known = false;
        try {
            ActivityManager manager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
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
        } catch (RuntimeException ignored) {
            // Process listing is not guaranteed. The hard duration cap
            // still stops the recording if Android hides process state.
        }
        if (known) {
            if (present) { gameObserved = true; emptyGameChecks = 0; }
            else if (++emptyGameChecks >= 2 &&
                (gameObserved ||
                SystemClock.elapsedRealtime() - startedUptime > 30000L)) {
                // Also stop an early crash before our first 15s process poll.
                stopRecording();
                return;
            }
        }
        if (SystemClock.elapsedRealtime() - startedUptime >
                MAX_MINUTES * 60_000L + 3000L) {
            stopRecording();
            return;
        }
        main.postDelayed(this::checkGameActivity, 5000L);
    }

    private void stopRecording() {
        if (stopping) return;
        stopping = true;
        active = false;
        boolean sound = false;
        try {
            if (recorder != null && startedWallMs > 0) {
                try {
                    recorder.stop();
                    sound = true;
                } catch (RuntimeException incomplete) {
                    Log.w(TAG, "Could not finalize screen video", incomplete);
                }
            }
        } finally {
            if (recorder != null) {
                recorder.reset();
                recorder.release();
                recorder = null;
            }
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (projection != null) {
                MediaProjection old = projection;
                projection = null;
                try { old.stop(); } catch (RuntimeException ignored) { }
            }
        }
        File source = pending;
        pending = null;
        File output = completed;
        completed = null;
        if (!sound || source == null || !source.isFile() ||
            source.length() < 4096 || source.length() > ReelSessionIndex.SOURCE_LIMIT) {
            if (source != null) source.delete();
            stopSelf();
            return;
        }
        try {
            // Valid MP4 parsing is required before moving into the library.
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(source.getAbsolutePath());
                if (extractor.getTrackCount() == 0)
                    throw new IOException("Recorded MP4 has no video tracks");
            } finally { extractor.release(); }
            Files.move(source.toPath(), output.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            ReelSessionIndex.Session session = new ReelSessionIndex.Session(
                output.getName(), startedWallMs,
                Math.min(System.currentTimeMillis(),
                    startedWallMs + ReelSessionIndex.MAX_RECORDING_MS),
                capturedWidth, capturedHeight);
            ReelLibrary.addSession(getExternalFilesDir(null), session);
        } catch (Exception invalid) {
            Log.e(TAG, "Video finalization failed; source kept for inspection", invalid);
            stopSelf();
            return;
        }
        new Thread(() -> {
            try {
                int reels = ReelLibrary.assemble(getExternalFilesDir(null));
                Log.i(TAG, "Rendered " + reels + " new local video reels");
            } finally {
                main.post(this::stopSelf);
            }
        }, "Halo-local-reel-muxer").start();
    }

    @Override public void onDestroy() {
        if (!stopping) stopRecording();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
