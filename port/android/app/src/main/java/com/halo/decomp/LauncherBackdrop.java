package com.halo.decomp;

import android.content.Context;
import android.graphics.*;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.View;
import java.io.File;

/** Replay the locally generated, authentic title render behind the native
 * Android launcher. Only a small decoder and music player run here. */
final class LauncherBackdrop extends View {
    private final File directory;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private volatile Bitmap frame;
    private volatile int generation;
    private Thread decoder;
    private MediaPlayer music;
    private AudioFocusRequest focus;
    private boolean active, prepared, focused;
    private final AudioManager audio;

    LauncherBackdrop(Context context, File root) {
        super(context);
        directory = TitleBackdropFiles.directory(root);
        audio = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false); setFocusable(false);
    }

    void resumeTitle() {
        if (active) return;
        active = true;
        final int token = ++generation;
        decoder = new Thread(() -> decode(token), "Halo-title-loop");
        decoder.start();
        if (audio == null) return;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                    if (!active || token != generation || music == null) return;
                    focused = change == AudioManager.AUDIOFOCUS_GAIN;
                    try {
                        if (prepared && focused) music.start();
                        else if (prepared && music.isPlaying()) music.pause();
                    } catch (RuntimeException ignored) { }
                }).build();
            focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            MediaPlayer player = new MediaPlayer();
            music = player;
            player.setAudioAttributes(attributes);
            player.setDataSource(new File(directory, "audio.wav").getAbsolutePath());
            player.setLooping(true);
            player.setOnPreparedListener(p -> {
                if (!active || token != generation || p != music) return;
                prepared = true;
                if (focused) try { p.start(); } catch (RuntimeException ignored) { }
            });
            player.setOnErrorListener((p, what, extra) -> {
                if (p == music) releaseMusic();
                return true;
            });
            player.prepareAsync();
        } catch (Exception unavailable) { releaseMusic(); }
    }

    private void decode(int token) {
        try (TitleBackdropFiles.Reader reader = new TitleBackdropFiles.Reader(
                new File(directory, "frames.hbt"))) {
            long start = SystemClock.elapsedRealtime();
            int previous = -1;
            while (generation == token && !Thread.currentThread().isInterrupted()) {
                long now = SystemClock.elapsedRealtime();
                int index = (int)((now - start) * reader.fps / 1000 % reader.count);
                if (index != previous) {
                    byte[] bytes = reader.read(index);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    if (options.outWidth != reader.width || options.outHeight != reader.height)
                        throw new java.io.IOException("Unexpected title JPEG dimensions");
                    Bitmap next = BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                    if (next == null) throw new java.io.IOException("Unreadable title frame");
                    if (generation != token) { next.recycle(); break; }
                    frame = next; previous = index;
                    postInvalidateOnAnimation();
                }
                Thread.sleep(1000 / reader.fps / 2);
            }
        } catch (Exception unavailable) {
            // Fall back to the existing gradient behind the View.
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap bitmap = frame;
        if (bitmap == null) return;
        float scale = Math.max(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        dst.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
        canvas.drawBitmap(bitmap,null,dst,paint);
    }

    private void releaseMusic() {
        prepared = false; focused = false;
        MediaPlayer player = music; music = null;
        if (player != null) try { player.release(); } catch (RuntimeException ignored) { }
        if (audio != null && focus != null) try { audio.abandonAudioFocusRequest(focus); }
            catch (RuntimeException ignored) { }
        focus = null;
    }

    void pauseTitle() {
        active = false;
        ++generation;
        Thread thread = decoder; decoder = null;
        if (thread != null) thread.interrupt();
        frame = null;
        releaseMusic();
        invalidate();
    }
}
