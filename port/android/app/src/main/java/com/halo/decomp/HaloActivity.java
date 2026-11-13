package com.halo.decomp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL3's activity, running libmain.so (port/android/host), which
 * loads the game image from the APK's assets.
 */
public class HaloActivity extends SDLActivity {
    static final String OFFLINE_BOT_ARGUMENTS = "com.halo.decomp.OFFLINE_BOT_ARGUMENTS";
    static final String LAN_UPDATE_HOST = "com.halo.decomp.LAN_UPDATE_HOST";
    private LanUpdateServer lanUpdateServer;
    /** lets system link's broadcasts in over Wi-Fi while the game runs */
    private WifiManager.MulticastLock multicastLock;
    /**
     * the latest system gesture insets {left, top, right, bottom}, in pixels;
     * written on the UI thread, read by the game's thread: replaced as a
     * whole, never changed in place
     */
    private volatile int[] gestureInsets = new int[] { 0, 0, 0, 0 };
    /** the on-screen touch controls, over SDL's surface */
    private TouchControls touchControls;
    private static final int EXPORT_LAYOUT = 401, IMPORT_LAYOUT = 402;
    private String pendingLayoutExport;

    /** Local, bundled original voice samples. Android SoundPool needs no
     * TTS engine, voice download, microphone or network to play them. */
    private SoundPool rewardCallouts;
    private int rewardEnhancementsSample;
    private int rewardReconSample;
    private int rewardOrbitalAuthorizedSample;
    private int rewardOrbitalIncomingSample;
    private boolean rewardOrbitalAuthorizedLoaded;
    private boolean rewardOrbitalIncomingLoaded;
    private int rewardActiveStream;
    private boolean rewardEnhancementsLoaded;
    private boolean rewardReconLoaded;
    private boolean rewardAudioClosed;
    private boolean rewardActivityResumed;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected String[] getArguments() {
        String[] arguments = getIntent().getStringArrayExtra(OFFLINE_BOT_ARGUMENTS);
        return arguments == null ? super.getArguments() : arguments;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null)
            pendingLayoutExport = savedInstanceState.getString("pending-layout-export");
        if (mLayout != null) {
            touchControls = new TouchControls(this);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferHighestRefreshRate();
        trackGestureInsets();
        acquireMulticastLock();
        initializeRewardCallouts();
        // The existing public-release updater is separate from local LAN sharing.
        Updater.start(this);
        if (getIntent() != null && getIntent().getBooleanExtra(LAN_UPDATE_HOST, false))
            lanUpdateServer = LanUpdateServer.start(this);
    }

    /** Load four *original* WAV callouts from the APK. Unlike Android TTS,
     * this works even if the phone has no installed offline speech voices.
     * Each sample is under 200 KB; asynchronous load completes long before
     * the player can normally earn a three-kill streak. */
    private void initializeRewardCallouts() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            rewardCallouts = new SoundPool.Builder()
                    .setAudioAttributes(attrs).setMaxStreams(1).build();
            rewardCallouts.setOnLoadCompleteListener((pool, sample, status) ->
                    runOnUiThread(() -> {
                        if (rewardAudioClosed || pool != rewardCallouts)
                            return;
                        if (sample == rewardEnhancementsSample)
                            rewardEnhancementsLoaded = status == 0;
                        if (sample == rewardOrbitalAuthorizedSample)
                            rewardOrbitalAuthorizedLoaded = status == 0;
                        if (sample == rewardOrbitalIncomingSample)
                            rewardOrbitalIncomingLoaded = status == 0;
                        if (sample == rewardReconSample)
                            rewardReconLoaded = status == 0;
                        if (status != 0)
                            Log.w("HaloCallouts", "Bundled reward sample failed to load: " + status);
                    }));
            rewardEnhancementsSample =
                    rewardCallouts.load(this, R.raw.halo_reward_enhancements, 1);
            rewardReconSample =
                    rewardCallouts.load(this, R.raw.halo_reward_recon, 1);
            rewardOrbitalAuthorizedSample =
                    rewardCallouts.load(this, R.raw.halo_reward_orbital_authorized, 1);
            rewardOrbitalIncomingSample =
                    rewardCallouts.load(this, R.raw.halo_reward_orbital_incoming, 1);
            if (rewardEnhancementsSample == 0 || rewardReconSample == 0 ||
                    rewardOrbitalAuthorizedSample == 0 || rewardOrbitalIncomingSample == 0)
                Log.w("HaloCallouts", "Could not queue bundled reward samples");
        } catch (RuntimeException failure) {
            Log.w("HaloCallouts", "Could not initialize bundled reward audio", failure);
            if (rewardCallouts != null) {
                rewardCallouts.release();
                rewardCallouts = null;
            }
        }
    }

    /** Invoked only from the already validated local recipient's HUD.
     * The gameplay engine gates privacy, life, streak and announcement mode.
     * Do not use Activity window focus as a gate: some Android devices
     * don't mark the SDL surface focused while touch input is active. */
    public void announceKillstreak(int award) {
        if (award != 3 && award != 5 && award != 10 && award != 11)
            return;
        runOnUiThread(() -> {
            if (rewardAudioClosed || !rewardActivityResumed ||
                    rewardCallouts == null || isFinishing() || isDestroyed())
                return;
            int sample = award == 3 ? rewardEnhancementsSample :
                    award == 5 ? rewardReconSample :
                    award == 10 ? rewardOrbitalAuthorizedSample : rewardOrbitalIncomingSample;
            boolean loaded = award == 3 ? rewardEnhancementsLoaded :
                    award == 5 ? rewardReconLoaded :
                    award == 10 ? rewardOrbitalAuthorizedLoaded : rewardOrbitalIncomingLoaded;
            if (sample == 0 || !loaded)
                return;  // Accurate HUD text is still displayed.
            if (rewardActiveStream != 0)
                rewardCallouts.stop(rewardActiveStream);
            rewardActiveStream = rewardCallouts.play(sample, 0.9f, 0.9f, 1, 0, 1.0f);
            if (rewardActiveStream == 0)
                Log.w("HaloCallouts", "Bundled reward sample playback unavailable");
        });
    }

    /** the system's file picker, for the touch layout; no storage permission */
    public void chooseLayoutFile(boolean export, String configuration) {
        String message = export
            ? "Choose the folder and the name of the touch layout file."
            : "Choose a touch layout file. It replaces your buttons, the look sensitivity and the General settings.";
        new AlertDialog.Builder(this)
            .setTitle(export ? "Export the layout" : "Import a layout")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(export ? "Choose the place" : "Choose the file", (dialog, which) -> {
                Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(export ? "text/plain" : "*/*");
                if (export) {
                    pendingLayoutExport = configuration;
                    intent.putExtra(Intent.EXTRA_TITLE, "halo-touch-layout.halolayout");
                }
                try {
                    startActivityForResult(intent, export ? EXPORT_LAYOUT : IMPORT_LAYOUT);
                } catch (android.content.ActivityNotFoundException e) {
                    pendingLayoutExport = null;
                    Toast.makeText(this, "This device has no file picker.", Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending-layout-export", pendingLayoutExport);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != EXPORT_LAYOUT && request != IMPORT_LAYOUT) {
            super.onActivityResult(request, result, data);
            return;
        }
        String exported = pendingLayoutExport;
        pendingLayoutExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri document = data.getData();
        // (the file is read and written away from the UI thread)
        new Thread(() -> {
            try {
                if (request == EXPORT_LAYOUT)
                    writeLayoutFile(document, exported);
                else
                    readLayoutFile(document);
            } catch (Exception e) {
                runOnUiThread(() -> layoutFileError(e));
            }
        }, "halo-touch-layout-file").start();
    }

    private void writeLayoutFile(Uri document, String configuration) throws IOException {
        if (configuration == null)
            throw new IOException("the layout to export is gone");
        try (OutputStream output = getContentResolver().openOutputStream(document, "wt")) {
            if (output == null)
                throw new IOException("the file cannot be opened");
            output.write(configuration.getBytes(StandardCharsets.UTF_8));
        }
        runOnUiThread(() -> Toast.makeText(this, "The layout is exported.", Toast.LENGTH_SHORT).show());
    }

    private void readLayoutFile(Uri document) throws IOException {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        try (InputStream input = getContentResolver().openInputStream(document)) {
            if (input == null)
                throw new IOException("the file cannot be opened");
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (contents.size() + size > 65536)
                    throw new IOException("the file is too large");
                contents.write(buffer, 0, size);
            }
        }
        String configuration = new String(contents.toByteArray(), StandardCharsets.UTF_8);
        // checked here, then applied to the overlay at once
        TouchLayout.importConfiguration(configuration);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || touchControls == null)
                return;
            try {
                touchControls.importLayout(configuration);
                Toast.makeText(this, "The layout is imported and saved.", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                layoutFileError(e);
            }
        });
    }

    private void layoutFileError(Exception error) {
        if (isFinishing() || isDestroyed())
            return;
        new AlertDialog.Builder(this)
            .setTitle("Touch layout file")
            .setMessage("The operation did not complete: " + error.getMessage())
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rewardActivityResumed = true;
        if (touchControls != null && getWindow().getDecorView().hasWindowFocus())
            touchControls.startDeviceInput();
    }

    @Override
    protected void onPause() {
        rewardActivityResumed = false;
        if (touchControls != null)
            touchControls.stopDeviceInput();
        if (rewardCallouts != null && rewardActiveStream != 0) {
            rewardCallouts.stop(rewardActiveStream);
            rewardActiveStream = 0;
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (touchControls != null) {
            if (hasFocus)
                touchControls.startDeviceInput();
            else
                touchControls.stopDeviceInput();
        }
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        if (lanUpdateServer != null) {
            lanUpdateServer.stop();
            lanUpdateServer = null;
        }
        rewardActivityResumed = false;
        rewardAudioClosed = true;
        rewardEnhancementsLoaded = rewardReconLoaded = false;
        rewardOrbitalAuthorizedLoaded = rewardOrbitalIncomingLoaded = false;
        if (rewardCallouts != null) {
            rewardCallouts.release();
            rewardCallouts = null;
        }
        rewardActiveStream = 0;
        if (touchControls != null)
            touchControls.stopDeviceInput();
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        multicastLock = null;
        super.onDestroy();
    }

    /**
     * keeps gestureInsets current; Android sends the insets again when the
     * activity rotates (after the game's native code has started on a phone
     * launched from portrait), so a single read at startup would keep the
     * portrait values; the listener hands the insets on so SDL's own
     * handling still sees them
     */
    private void trackGestureInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                gestureInsets = readGestureInsets(insets);
                return view.onApplyWindowInsets(insets);
            }
        });
    }

    @SuppressWarnings("deprecation") // getSystemGestureInsets is the only call on Android 10
    private static int[] readGestureInsets(WindowInsets insets) {
        Insets gesture;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            gesture = insets.getInsets(WindowInsets.Type.systemGestures());
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            gesture = insets.getSystemGestureInsets();
        else
            return new int[] { 0, 0, 0, 0 };
        return new int[] { gesture.left, gesture.top, gesture.right, gesture.bottom };
    }

    /**
     * the edges of the screen where Android keeps its gestures; in sticky
     * full screen the first swipe from an edge only shows the system bars,
     * and Android hands that swipe to the game as an ordinary finger, so
     * the game (port/linux/src/touch_input.c) must ignore touches that
     * begin there; returns a copy of {left, top, right, bottom} in pixels:
     * all 0 before Android 10 (which has no insets) and until the first
     * insets arrive. The game's native code calls this through JNI by name
     * and signature (host_main.c GetMethodID(...,
     * "getSystemGestureInsetsPixels", "()[I")), at every finger down, so
     * it must not be renamed, retyped or removed as unused
     */
    public int[] getSystemGestureInsetsPixels() {
        return gestureInsets.clone();
    }

    /**
     * Many phones drop the Wi-Fi's broadcast and multicast datagrams to
     * save power unless an app holds this: without it they would not see
     * system link games on the local network, nor be seen hosting one.
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null)
                return;
            multicastLock = wifi.createMulticastLock("halo-system-link");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException e) {
            // (no Wi-Fi, or not allowed: the local network may miss games)
            multicastLock = null;
        }
    }

    /**
     * The game draws a frame at every display refresh, between its 30 Hz
     * ticks (port/linux/game/render_interpolation.c); Android otherwise
     * often keeps an app at 60 Hz on a faster display.
     */
    private void preferHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;

        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                mode.getPhysicalHeight() == current.getPhysicalHeight() &&
                mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(attributes);
    }
}
