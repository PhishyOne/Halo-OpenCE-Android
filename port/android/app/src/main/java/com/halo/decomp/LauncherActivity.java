package com.halo.decomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.TypedValue;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;

/**
 * Starts the game once its data is in place.
 *
 * The game reads the Xbox game data (the folder holding maps/) from the
 * app's external files directory, /sdcard/Android/data/com.halo.decomp/files.
 * If it is missing, this screen lets the player pick an Xbox disc image of
 * the game (.xiso or .iso, any version) with the system file picker, and
 * copies its maps folder there (XisoExtractor), as the desktop games do; or
 * they can push the maps folder with adb.
 */
public class LauncherActivity extends Activity {
    private static final int PICK_IMAGE = 1;

    private File dataRoot;
    private TextView status;
    private ProgressBar progress;
    private Button pick;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dataRoot = getExternalFilesDir(null);
        // created by the app, so that files pushed into it with adb stay
        // readable (a directory adb creates there belongs to the shell user)
        if (dataRoot != null)
            new File(dataRoot, "maps").mkdirs();
        passOnHardwareId();
        passOnInvite(getIntent());
        if (haveData()) {
            buildLanInterface();
            return;
        }
        buildInterface();
    }

    /**
     * An internet play invite link the app was opened with: the game
     * (port/linux/src/p2p.c) picks it up from join_link.txt, whether it is
     * starting now or already running.
     */
    private void passOnInvite(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null
            || dataRoot == null)
            return;
        // written whole under another name, then renamed: the game never
        // reads it half written
        File partial = new File(dataRoot, "join_link.txt.tmp");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(intent.getData().toString().getBytes("UTF-8"));
        } catch (java.io.IOException e) {
            // the link is lost; the player can copy it instead
            partial.delete();
            return;
        }
        if (!partial.renameTo(new File(dataRoot, "join_link.txt")))
            partial.delete();
    }

    /**
     * This device's ANDROID_ID (the app's own: one per app signing key and
     * user, until a factory reset), which native code cannot read: the game
     * (port/linux/src/p2p.c) hashes it from hardware_id.txt into the
     * hardware id a host it joins is told.
     */
    private void passOnHardwareId() {
        String id;

        if (dataRoot == null)
            return;
        try {
            id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (RuntimeException e) {
            return;
        }
        if (id == null || id.isEmpty())
            return;
        File partial = new File(dataRoot, "hardware_id.txt.tmp");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(id.getBytes("UTF-8"));
        } catch (java.io.IOException e) {
            partial.delete();
            return;
        }
        if (!partial.renameTo(new File(dataRoot, "hardware_id.txt")))
            partial.delete();
    }

    private boolean haveData() {
        return dataRoot != null && new File(dataRoot, "maps/ui.map").isFile();
    }

    private void startGame() {
        startActivity(new Intent(this, HaloActivity.class));
        finish();
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics());
    }

    /** Hints only: mobile data or a VPN can also have a private address. */
    private String possibleLanAddresses() {
        StringBuilder addresses = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                String name = network.getName();
                if (network.isLoopback() || !network.isUp() ||
                    !(name.startsWith("wlan") || name.startsWith("swlan") ||
                      name.startsWith("ap") || name.startsWith("eth")))
                    continue;
                Enumeration<InetAddress> ips = network.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) {
                        if (addresses.length() != 0)
                            addresses.append("  ");
                        addresses.append(name).append(": ").append(ip.getHostAddress());
                    }
                }
            }
        } catch (java.net.SocketException e) {
            // Android does not expose every hotspot interface on every phone.
        }
        return addresses.length() == 0
            ? "Check Wi-Fi or hotspot settings to find each phone's IP address."
            : addresses.toString();
    }

    /** A halo://join invite needs online play; keep it pending through import. */
    private boolean hasInternetInvite() {
        Intent intent = getIntent();
        return intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) &&
            intent.getData() != null && "halo".equals(intent.getData().getScheme()) &&
            "join".equals(intent.getData().getHost());
    }

    /** Checks only processes belonging to this application, not other apps. */
    private boolean gameProcessRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null)
            return false;
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null)
            return false;
        String gameProcess = getPackageName() + ":game";
        int ownUid = android.os.Process.myUid();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid == ownUid && gameProcess.equals(process.processName))
                return true;
        }
        return false;
    }

    private TextView lanText(String message, int size) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.rgb(225, 230, 235));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setPadding(0, 0, 0, dp(7));
        return text;
    }

    /** A phone-friendly System Link screen; no external text editor required. */
    private void buildLanInterface() {
        LanConfig.Settings settings = new LanConfig.Settings();
        String warning = "";
        try {
            settings = LanConfig.load(new File(dataRoot, "config.toml"));
        } catch (java.io.IOException e) {
            warning = "Could not read the current settings: " + e.getMessage();
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(16), dp(22), dp(16));
        layout.setBackgroundColor(Color.rgb(12, 16, 20));
        scroll.addView(layout);

        layout.addView(lanText("Halo • System Link", 23));
        layout.addView(lanText("Configure local multiplayer, then start Halo. "
            + "Both phones need the same build. Leave an IP blank to use automatic detection.", 14));
        layout.addView(lanText("Possible local IP: " + possibleLanAddresses(), 13));

        layout.addView(lanText("This phone's LAN IP (optional)", 15));
        EditText local = new EditText(this);
        local.setSingleLine(true);
        local.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        local.setHint("Automatic");
        local.setText(settings.local);
        layout.addView(local);

        layout.addView(lanText("Other phone's LAN IP (optional)", 15));
        EditText peer = new EditText(this);
        peer.setSingleLine(true);
        peer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        peer.setHint("Example: 10.18.179.225");
        peer.setText(settings.peer);
        layout.addView(peer);

        CheckBox offline = new CheckBox(this);
        offline.setText("LAN only (disable internet matchmaking)");
        offline.setTextColor(Color.WHITE);
        final boolean invitePending = hasInternetInvite();
        offline.setChecked(invitePending ? false : settings.lanOnly);
        layout.addView(offline);

        if (invitePending)
            layout.addView(lanText("Internet invite received. LAN-only has been turned off for this launch. "
                + "Press Save & Play to join the invitation.", 14));

        TextView status = lanText(warning, 13);
        status.setTextColor(Color.rgb(255, 170, 120));
        layout.addView(status);
        layout.addView(lanText("Ports: host UDP/TCP 5150; client UDP 5151. "
            + "Use each phone's current hotspot IP. This screen saves the values "
            + "before Halo initializes its sockets.", 13));

        Button appSettings = new Button(this);
        appSettings.setText("Open Android App Settings");
        appSettings.setVisibility(View.GONE);
        appSettings.setOnClickListener(next -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));

        Button play = new Button(this);
        play.setText("Save LAN settings & Play");
        play.setOnClickListener(view -> {
            if (invitePending && offline.isChecked()) {
                status.setText("This invite needs internet play. Uncheck LAN only and save again.");
                return;
            }
            if (gameProcessRunning()) {
                status.setText("Halo is still running with its previous network settings. "
                    + "Force-stop Halo in Android App Settings, then reopen to save and apply LAN settings.");
                appSettings.setVisibility(View.VISIBLE);
                return;
            }
            try {
                LanConfig.save(new File(dataRoot, "config.toml"),
                    local.getText().toString(), peer.getText().toString(), offline.isChecked());
            } catch (java.io.IOException e) {
                status.setText("Could not save: " + e.getMessage());
                return;
            }
            startGame();
        });
        layout.addView(play);
        layout.addView(appSettings);
        setContentView(scroll);
    }

    private void buildInterface() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(48), dp(24), dp(48), dp(24));
        layout.setBackgroundColor(Color.rgb(12, 16, 20));

        TextView title = new TextView(this);
        title.setText("Halo needs its game data");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView message = new TextView(this);
        message.setText("Choose an Xbox disc image of Halo: Combat Evolved (an .iso or .xiso file, any "
            + "version) on this device. Its maps folder is copied into the app's storage (about 1.8 GB), "
            + "and you can delete the image afterwards.\n\n"
            + "You can also copy a maps folder from a computer:\n"
            + "adb push <folder with maps>/. " + (dataRoot != null ? dataRoot.getAbsolutePath() : "") + "/");
        message.setTextColor(Color.rgb(200, 205, 210));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(16), 0, dp(16));
        layout.addView(message);

        pick = new Button(this);
        pick.setText("Choose disc image");
        pick.setOnClickListener(v -> {
            // (disc images have no MIME type of their own: any file, checked
            // when it is read)
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_IMAGE);
        });
        layout.addView(pick, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(dp(480),
            LinearLayout.LayoutParams.WRAP_CONTENT);
        progressLayout.topMargin = dp(16);
        layout.addView(progress, progressLayout);

        status = new TextView(this);
        status.setTextColor(Color.rgb(160, 200, 160));
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        layout.addView(status);

        setContentView(layout);
        pick.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // data pushed with adb while this screen was open
        if (pick != null && pick.isEnabled() && haveData()) {
            pick = null;
            buildLanInterface();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri image = data.getData();
        pick.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Reading the disc image...");
        new Thread(() -> importImage(image)).start();
    }

    private void report(String text, int permille) {
        handler.post(() -> {
            status.setText(text);
            if (permille >= 0)
                progress.setProgress(permille);
        });
    }

    private void fail(String text) {
        handler.post(() -> {
            status.setText(text);
            progress.setVisibility(View.GONE);
            pick.setEnabled(true);
            pick.requestFocus();
        });
    }

    private void importImage(Uri image) {
        try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(image, "r")) {
            if (descriptor == null)
                throw new java.io.IOException("the file could not be opened");
            try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
                FileChannel channel = in.getChannel();

                XisoExtractor.extractMaps(channel, dataRoot, (file, done, total) ->
                    report("Extracting maps/" + file + " (" + (done >> 20) + " of " + (total >> 20) + " MB)",
                        total > 0 ? (int) (done * 1000 / total) : 0));
            }
            handler.post(() -> {
                if (haveData()) {
                    pick = null;
                    buildLanInterface();
                } else {
                    fail("The extraction finished but maps/ui.map is missing.");
                }
            });
        } catch (XisoExtractor.ExtractException exception) {
            fail(exception.getMessage());
        } catch (Exception exception) {
            fail("Extracting failed: " + exception.getMessage());
        }
    }
}
