package com.halo.decomp;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

/** Streams a selected saved reel through MediaDataSource. No Internet URLs,
 * external player, downloads, global cleartext-policy change or raw video. */
final class LanReelPlayer {
    private final LauncherActivity activity;
    private final LanReelClient.Source source;
    private final Dialog dialog;
    private final FrameLayout viewport;
    private final TextureView texture;
    private final TextView status;
    private final Button play;
    private final SeekBar seek;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread decoder = new HandlerThread("Halo-LAN-reel-player");
    private final Handler worker;
    private volatile boolean closed;
    private boolean dragging;
    // The decoder thread owns all MediaPlayer calls.
    private MediaPlayer player;
    private Surface surface;
    private boolean prepared;
    private int videoWidth, videoHeight;

    static void play(LauncherActivity activity, LanBoardPresence.Peer peer,
                     LanReelProtocol.Reel reel) {
        if (peer == null || !peer.reels || reel == null || !activity.launcherVisible()) return;
        new LanReelPlayer(activity, peer, reel).show();
    }

    private LanReelPlayer(LauncherActivity activity, LanBoardPresence.Peer peer,
                          LanReelProtocol.Reel reel) {
        this.activity = activity;
        this.source = new LanReelClient.Source(peer, reel);
        decoder.start();
        worker = new Handler(decoder.getLooper());
        dialog = new HaloWindow(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(6), dp(10), dp(6));
        body.setBackgroundColor(HaloTheme.BACKGROUND_BOTTOM);
        TextView title = new TextView(activity);
        title.setText(peer.name + " — LAN HIGHLIGHT");
        title.setTextColor(HaloTheme.FOCUS_TEXT);
        title.setTextSize(16);
        HaloTheme.font(title, true);
        body.addView(title);
        viewport = new FrameLayout(activity);
        viewport.setBackgroundColor(Color.BLACK);
        texture = new TextureView(activity);
        viewport.addView(texture, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER));
        body.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(activity);
        status.setText("Connecting… • streamed, not saved");
        status.setTextColor(HaloTheme.MUTED);
        status.setTextSize(12);
        body.addView(status);
        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        play = button("PAUSE");
        play.setEnabled(false);
        controls.addView(play, new LinearLayout.LayoutParams(dp(94), dp(40)));
        seek = new SeekBar(activity);
        seek.setEnabled(false);
        controls.addView(seek, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button close = button("CLOSE");
        controls.addView(close, new LinearLayout.LayoutParams(dp(94), dp(40)));
        body.addView(controls);
        close.setOnClickListener(v -> close());
        play.setOnClickListener(v -> worker.post(() -> {
            if (closed || !prepared || player == null) return;
            try {
                if (player.isPlaying()) { player.pause(); setPlay("PLAY"); }
                else { player.start(); setPlay("PAUSE"); }
            } catch (RuntimeException failed) { fail(); }
        }));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                int position = bar.getProgress();
                worker.post(() -> {
                    if (!closed && prepared && player != null)
                        try { player.seekTo(position); } catch (RuntimeException failed) { fail(); }
                });
            }
        });
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture image, int w, int h) {
                Surface output = new Surface(image);
                if (closed || !worker.post(() -> start(output))) output.release();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture image, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture image) {
                close(); return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture image) { }
        });
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fit());
        dialog.setContentView(body);
        dialog.setOnDismissListener(d -> close());
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private Button button(String label) {
        Button b = new Button(activity); b.setText(label); b.setAllCaps(false);
        HaloTheme.menuButtonColors(b); HaloTheme.font(b, true); return b;
    }
    private void show() {
        if (!activity.launcherVisible()) { close(); return; }
        activity.setRemoteReelPlayer(this);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(HaloTheme.BACKGROUND_BOTTOM));
            w.setLayout((int)(activity.getResources().getDisplayMetrics().widthPixels * .96f),
                (int)(activity.getResources().getDisplayMetrics().heightPixels * .94f));
        }
    }

    private void fit() {
        int w = viewport.getWidth(), h = viewport.getHeight();
        if (w <= 0 || h <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        double scale = Math.min((double)w / videoWidth, (double)h / videoHeight);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams)texture.getLayoutParams();
        int width = Math.max(1, (int)(videoWidth * scale)), height = Math.max(1, (int)(videoHeight * scale));
        if (p.width != width || p.height != height) {
            p.width = width; p.height = height; p.gravity = Gravity.CENTER;
            texture.setLayoutParams(p);
        }
    }

    private void start(Surface output) {
        if (closed || player != null) { output.release(); return; }
        surface = output;
        try {
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setOnVideoSizeChangedListener((mp,w,h) -> ui.post(() -> {
                if (!closed) { videoWidth = w; videoHeight = h; fit(); }
            }));
            player.setOnPreparedListener(mp -> {
                if (closed) return;
                prepared = true;
                try {
                    int duration = mp.getDuration();
                    mp.start();
                    ui.post(() -> {
                        if (closed) return;
                        seek.setMax(Math.max(1, duration));
                        seek.setEnabled(true);
                        play.setEnabled(true);
                        play.setText("PAUSE");
                        status.setText("Streaming over LAN • not saved");
                    });
                    worker.post(progress);
                } catch (RuntimeException failed) { fail(); }
            });
            player.setOnCompletionListener(mp -> setPlay("PLAY"));
            player.setOnErrorListener((mp, what, extra) -> { fail(); return true; });
            player.setDataSource(source);
            player.prepareAsync();
        } catch (RuntimeException unavailable) { fail(); }
    }
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (closed || !prepared || player == null) return;
            try {
                int current = player.getCurrentPosition();
                ui.post(() -> { if (!closed && !dragging) seek.setProgress(current); });
                worker.postDelayed(this, 350);
            } catch (RuntimeException failed) { fail(); }
        }
    };
    private void setPlay(String label) {
        ui.post(() -> { if (!closed) play.setText(label); });
    }
    private void fail() {
        prepared = false;
        source.close();
        release();
        ui.post(() -> {
            if (closed) return;
            status.setText("Stream unavailable. The player may have disconnected, stopped sharing, or removed this reel.");
            play.setEnabled(false); seek.setEnabled(false);
        });
    }
    private void release() {
        if (player != null) { try { player.release(); } catch (RuntimeException ignored) { } player = null; }
        if (surface != null) { surface.release(); surface = null; }
    }
    void close() {
        if (closed) return;
        closed = true;
        source.close();
        activity.remoteReelClosed(this);
        if (dialog.isShowing()) dialog.dismiss();
        worker.removeCallbacks(progress);
        worker.post(() -> { release(); decoder.quitSafely(); });
    }
}
