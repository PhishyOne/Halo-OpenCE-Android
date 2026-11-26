package com.halo.decomp;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private final Runnable timeout = () -> {
        if (worker == null && !finished.get())
            fail("The original title scene did not become ready.");
    };

    TitleBackdropCapture(HaloActivity activity, View root, TextView status) {
        this.activity = activity; this.root = root; this.status = status;
        ui.postDelayed(timeout, 45000);
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
        SurfaceView source = surface(root);
        if (source == null || !source.getHolder().getSurface().isValid()) {
            fail("Halo's title rendering surface is unavailable."); return;
        }
        worker = new Thread(() -> capture(source), "Halo-title-capture");
        worker.start();
    }

    void cancel() {
        canceled.set(true);
        if (worker == null) fail("Title setup canceled.");
        else status.setText("Canceling title setup...");
    }

    private void fail(String message) {
        try { activity.finishTitleAudio(false); } catch (RuntimeException | LinkageError ignored) { }
        try { TitleBackdropFiles.clearPending(activity.getFilesDir()); } catch (IOException ignored) { }
        finish(false, message);
    }

    private void finish(boolean success, String message) {
        if (!finished.compareAndSet(false, true)) return;
        ui.removeCallbacks(timeout);
        ui.post(() -> activity.returnFromTitleCapture(success, message));
    }

    /** A successful PixelCopy alone can still be black. Require visible,
     * changing original frames before committing a cache. */
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

    private void capture(SurfaceView source) {
        HandlerThread callbacks = new HandlerThread("Halo-title-pixelcopy");
        callbacks.start();
        Handler callback = new Handler(callbacks.getLooper());
        Bitmap bitmap = null;
        boolean committed = false;
        String result = "Title setup failed.";
        try {
            if (source.getWidth() <= 0 || source.getHeight() <= 0)
                throw new IOException("Title surface has no dimensions.");
            int width = 480;
            int height = Math.max(90, Math.min(480,
                Math.round(width * source.getHeight() / (float)source.getWidth()))) & ~1;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
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
                    // If encoding is behind the capture clock, repeat the
                    // last frame rather than queueing unbounded PixelCopies.
                    boolean behind = last != null && SystemClock.elapsedRealtime() - due > 120;
                    if (!behind) {
                        CountDownLatch done = new CountDownLatch(1);
                        int[] code = {PixelCopy.ERROR_UNKNOWN};
                        PixelCopy.request(source, bitmap, value -> { code[0] = value; done.countDown(); }, callback);
                        if (!done.await(1200, TimeUnit.MILLISECONDS))
                            throw new IOException("Title frame capture timed out.");
                        if (code[0] == PixelCopy.SUCCESS) {
                            long sample = signature(bitmap);
                            if (sample != 0) {
                                if (lastSignature != 0 && sample != lastSignature) changes++;
                                lastSignature = sample;
                                jpeg.reset();
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 76, jpeg))
                                    throw new IOException("Cannot encode title frame.");
                                last = jpeg.toByteArray();
                                copied++;
                            }
                        }
                    }
                    if (last != null) writer.frame(last);
                    int seconds = tick / TitleBackdropFiles.FPS;
                    if (seconds != lastProgress) {
                        lastProgress = seconds;
                        ui.post(() -> status.setText("Preparing original Halo title: " + seconds +
                            "/" + TitleBackdropFiles.SECONDS + " • Cancel anytime"));
                    }
                }
                if (canceled.get()) throw new IOException("Title setup canceled.");
                if (copied < TitleBackdropFiles.FRAMES / 3 || changes < 12)
                    throw new IOException("The title capture was black, static or too slow.");
                writer.complete();
            }
            int audioMs = activity.finishTitleAudio(true);
            if (audioMs < 55000) throw new IOException("The title music was silent or incomplete.");
            if (canceled.get()) throw new IOException("Title setup canceled.");
            TitleBackdropFiles.commit(root);
            committed = true;
            result = "Original Halo title prepared. Returning to the launcher.";
        } catch (Exception | LinkageError failure) {
            result = failure.getMessage() == null ? "Original title capture failed." : failure.getMessage();
        } finally {
            callbacks.quitSafely();
            // A timed-out PixelCopy may still own the bitmap. Do not recycle it.
            if (!committed) {
                try { activity.finishTitleAudio(false); } catch (RuntimeException | LinkageError ignored) { }
                try { TitleBackdropFiles.clearPending(activity.getFilesDir()); } catch (IOException ignored) { }
            }
            finish(committed, result);
        }
    }
}
