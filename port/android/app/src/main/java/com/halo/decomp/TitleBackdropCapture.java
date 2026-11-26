package com.halo.decomp;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-time local generation from the native SDL game surface and the
 * game's own stereo mixer. No MediaProjection or microphone. */
final class TitleBackdropCapture {
    private final HaloActivity activity;
    private final View root;
    private final TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private Thread worker;
    private long surfaceDeadline;
    private int copyFailures, copiedFrames, changedFrames, writtenFrames;
    private int blankFrames, waitingFrames, lastReadError;
    private int capturedAudioMs;
    private String phase = "Waiting for native title";
    private final Runnable timeout = () -> {
        if (worker == null && !finished.get())
            fail("The original title scene did not become ready.");
    };

    TitleBackdropCapture(HaloActivity activity, View root, TextView status) {
        this.activity = activity; this.root = root; this.status = status;
        TitleBackdropFiles.writeStatus(activity.getFilesDir(), "Preparing: " + phase);
        ui.postDelayed(timeout, 60000);
    }

    private static SurfaceView surface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = surface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    void ready(boolean audioReady) {
        if (finished.get() || canceled.get() || worker != null) return;
        ui.removeCallbacks(timeout);
        if (!audioReady) { fail("Could not open the original title audio cache."); return; }
        phase = "Waiting for SDL surface";
        TitleBackdropFiles.writeStatus(activity.getFilesDir(), "Preparing: " + phase);
        surfaceDeadline = SystemClock.elapsedRealtime() + 12000;
        awaitSurface();
    }

    /** The music tag can become ready before the first SDL surface buffer.
     * Do not discard the entire preparation on this transient startup race. */
    private void awaitSurface() {
        if (finished.get() || canceled.get() || worker != null) return;
        SurfaceView source = surface(root);
        if (source != null && source.getWidth() > 0 && source.getHeight() > 0 &&
                source.getHolder().getSurface().isValid()) {
            phase = "Capturing native title";
            TitleBackdropFiles.writeStatus(activity.getFilesDir(), "Preparing: " + phase);
            final int sourceWidth = source.getWidth(), sourceHeight = source.getHeight();
            worker = new Thread(() -> capture(sourceWidth, sourceHeight), "Halo-title-capture");
            worker.start();
        } else if (SystemClock.elapsedRealtime() < surfaceDeadline) {
            ui.postDelayed(this::awaitSurface, 150);
        } else fail("Halo's title rendering surface is unavailable.");
    }

    void cancel() {
        canceled.set(true);
        if (worker == null) fail("Title setup canceled.");
        else status.setText("Canceling title setup...");
    }

    private void fail(String message) {
        try {
            capturedAudioMs = Math.max(capturedAudioMs, activity.titleAudioDuration());
            activity.finishTitleAudio(false);
        } catch (RuntimeException | LinkageError ignored) { }
        try { TitleBackdropFiles.clearPending(activity.getFilesDir()); } catch (IOException ignored) { }
        finish(false, message);
    }

    private void finish(boolean success, String message) {
        if (!finished.compareAndSet(false, true)) return;
        ui.removeCallbacks(timeout);
        String detail = success ? "Ready" : "Title failed: " + message +
            " (frames " + copiedFrames + "/" + writtenFrames + ", changes " +
            changedFrames + ", blank " + blankFrames + ", waiting " + waitingFrames +
            ", GL errors " + copyFailures + "/" + lastReadError + ", audio " +
            capturedAudioMs + "ms)";
        TitleBackdropFiles.writeStatus(activity.getFilesDir(), detail);
        android.util.Log.i("HaloTitle", detail);
        ui.post(() -> activity.returnFromTitleCapture(success, success ? message : detail));
    }

    /** Even a successful native readback can contain a genuinely black
     * loading frame. Require visible, changing original frames. */
    private static long signature(Bitmap image) {
        long hash = 1469598103934665603L;
        int bright = 0;
        for (int y = 1; y <= 8; y++) for (int x = 1; x <= 12; x++) {
            int color = image.getPixel(x * (image.getWidth()-1)/13,
                y * (image.getHeight()-1)/9);
            int luminance = (((color>>16)&255)*3 + ((color>>8)&255)*6 + (color&255))/10;
            if (luminance > 12) bright++;
            hash ^= (color & 0x00f0f0f0);
            hash *= 1099511628211L;
        }
        return bright < 3 ? 0 : hash;
    }

    private void capture(int sourceWidth, int sourceHeight) {
        Bitmap bitmap = null;
        boolean committed = false;
        String result = "Title setup failed.";
        try {
            if (sourceWidth <= 0 || sourceHeight <= 0)
                throw new IOException("Title surface has no dimensions.");
            int width = 480;
            int height = Math.max(90, Math.min(480,
                Math.round(width * sourceHeight / (float)sourceWidth))) & ~1;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setHasAlpha(false);
            int[] pixels = new int[width * height];
            File root = activity.getFilesDir();
            long start = SystemClock.elapsedRealtime();
            long lastSignature = 0;
            int changes = 0, copied = 0, lastProgress = -1;
            byte[] last = null;
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(32 * 1024);
            try (TitleBackdropFiles.Writer writer = new TitleBackdropFiles.Writer(
                    new File(TitleBackdropFiles.pending(root), "frames.hbt"), width, height)) {
                for (int tick = 0; tick < TitleBackdropFiles.FRAMES && !canceled.get(); tick++) {
                    long due = start + tick * 1000L / TitleBackdropFiles.FPS;
                    long wait = due - SystemClock.elapsedRealtime();
                    if (wait > 0) Thread.sleep(wait);
                    if (SystemClock.elapsedRealtime() - start > 75000)
                        throw new IOException("The phone could not capture the title at a usable rate.");
                    // The GL thread services at most one demand-driven request
                    // per tick. Never capture a compositor surface or queue.
                    int code = activity.copyTitleFrame(pixels, width, height);
                    if (code == 1) {
                        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
                        long sample = signature(bitmap);
                        if (sample != 0) {
                            if (lastSignature != 0 && sample != lastSignature) changes++;
                            lastSignature = sample;
                            jpeg.reset();
                            // Keep the private cache within its fixed budget even for
                            // detailed frames. Preserve the real scene, not a synthetic image.
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 65, jpeg))
                                throw new IOException("Cannot encode title frame.");
                            last = jpeg.toByteArray();
                            copied++;
                            copiedFrames = copied;
                            changedFrames = changes;
                        } else blankFrames++;
                    } else if (code == 0) waitingFrames++;
                    else { copyFailures++; lastReadError = code; }
                    if (last != null) {
                        writer.frame(last);
                        writtenFrames = writer.count();
                    }
                    else if (tick > 10 * TitleBackdropFiles.FPS)
                        throw new IOException("No visible native GL title frame received.");
                    int seconds = tick / TitleBackdropFiles.FPS;
                    if (seconds != lastProgress) {
                        lastProgress = seconds;
                        ui.post(() -> status.setText("Preparing original Halo title: " + seconds +
                            "/" + TitleBackdropFiles.SECONDS + " • Cancel anytime"));
                    }
                }
                if (canceled.get()) throw new IOException("Title setup canceled.");
                if (copied < TitleBackdropFiles.FRAMES / 6 || changes < 12)
                    throw new IOException("The title capture was black, static or too slow.");
                writer.complete();
            }
            int audioMs = activity.finishTitleAudio(true);
            capturedAudioMs = audioMs;
            if (audioMs < 55000) throw new IOException("The title music was silent or incomplete.");
            if (canceled.get()) throw new IOException("Title setup canceled.");
            TitleBackdropFiles.commit(root);
            committed = true;
            result = "Original Halo title prepared. Returning to the launcher.";
        } catch (Exception | LinkageError failure) {
            result = failure.getMessage() == null ? "Original title capture failed." : failure.getMessage();
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (!committed) {
                try {
                    capturedAudioMs = Math.max(capturedAudioMs, activity.titleAudioDuration());
                    activity.finishTitleAudio(false);
                } catch (RuntimeException | LinkageError ignored) { }
                try { TitleBackdropFiles.clearPending(activity.getFilesDir()); } catch (IOException ignored) { }
            }
            finish(committed, result);
        }
    }
}
