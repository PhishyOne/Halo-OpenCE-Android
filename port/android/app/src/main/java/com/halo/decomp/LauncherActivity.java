package com.halo.decomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.database.Cursor;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.TypedValue;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private static final int PICK_CUSTOM_MAPS = 4;
    private static final int PICK_CUSTOM_FOLDER = 5;
    private static final int PICK_CUSTOM_ZIP = 6;
    private static final int SAVE_DIAGNOSTICS = 7;
    private static final int PICK_CAMPAIGN_BACKUP = 8;
    private static final int PICK_CAMPAIGN_RESTORE = 9;
    private static final int PICK_STOCK_MAP_IMPORT = 10;
    private static final int PICK_STOCK_MAP_EXPORT = 11;
    private static final int PICK_ADMIN_CAMPAIGN_ARCHIVE = 12;
    private static final int PICK_REEL_CONSENT = 13;
    private static final int PICK_REEL_EXPORT = 14;
    private static final String STATE_STOCK_MAP_TRANSFER = "stock-map-transfer";
    // Halo-style HUD colours. Native Android widgets remain accessible,
    // including the system file picker and LAN diagnostics.
    private static final int HUD_TEXT = HaloTheme.TEXT;
    private static final int HUD_MUTED = HaloTheme.MUTED;
    private static final int HUD_ACCENT = HaloTheme.FOCUS_TEXT;
    private static final int HUD_BORDER = HaloTheme.BORDER;
    private static final int HUD_WARNING = HaloTheme.WARNING;


    private File dataRoot;
    private LauncherBackdrop titleBackdrop;
    private boolean titleBackdropAvailable;
    private TextView titleStatusView;
    private String lastTitleNotice = "";
    private TextView status;
    private ProgressBar progress;
    private Button pick;
    private TextView repairStatus;
    private ProgressBar mapAuditProgress;
    private Button shareMapsButton, receiveMapsButton, repairHostButton;
    private LinearLayout stockMapResults;
    private LinearLayout dashboardPanel;
    private LanBoardUi socialUi;
    private LauncherLobbyUi lobbyUi;
    private boolean lobbyGameStarted;
    private Runnable openSocial;
    private LanMapRepair lanMapRepair;
    private LanBoardServer lanBoardServer;
    private LanBoardPresenceMonitor livePeerMonitor;
    private boolean launcherResumed;
    private LanReelPlayer remoteReelPlayer;
    private ChoiceButton stockCampaignMapChoice;
    private Button stockCampaignAuditButton;
    private Button stockCampaignImportButton;
    private Button stockCampaignExportButton;
    private String pendingStockMapTransfer;
    private Intent pendingVideoLaunch;
    private String pendingVideoExport;
    private Button customMapImportButton;
    private Button customMapFolderButton;
    private Button customMapZipButton;
    private TextView customMapStatus;
    private TextView diagnosticStatus;
    private TextView campaignSaveStatus;
    private Button campaignBackupButton;
    private Button campaignRestoreButton;
    private Button playButton;
    private volatile boolean repairBusy;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null)
            pendingStockMapTransfer =
                savedInstanceState.getString(STATE_STOCK_MAP_TRANSFER);
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

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString(STATE_STOCK_MAP_TRANSFER, pendingStockMapTransfer);
        super.onSaveInstanceState(state);
    }

    /**
     * Stage an internet invite for the launcher, not for native P2P yet.
     * A running :game process must not consume an invite before the player
     * can force-stop it and change network.online.
     */
    private void passOnInvite(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) ||
            intent.getData() == null || dataRoot == null)
            return;
        String link = intent.getData().toString();
        if (!validInvite(link))
            return;
        try {
            atomicWrite(new File(dataRoot, "pending_invite.txt"),
                link.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // Do not touch an older valid pending invite if staging fails.
            inviteError = "Unable to save the new invitation. Retry opening its link.";
        }
    }

    private String inviteError;

    private static boolean validInvite(String link) {
        return link != null && link.matches("halo://join/[a-fA-F0-9]{64}");
    }

    /** Atomic replacement without overwriting existing state on write failure. */
    private static void atomicWrite(File destination, byte[] data) throws java.io.IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(temporary)) {
                out.write(data);
            }
            try {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary.toPath());
        }
    }

    /** File deliberately unreadable by Halo's native join_link.txt polling. */
    private String pendingInvite() {
        if (dataRoot == null)
            return null;
        File pending = new File(dataRoot, "pending_invite.txt");
        if (!pending.isFile() || pending.length() > 256)
            return null;
        try {
            String link = new String(java.nio.file.Files.readAllBytes(pending.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            return validInvite(link) ? link : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Only publish the pending invite after saving online mode and verifying
     * that the previous game process is no longer running. */
    private void publishPendingInvite() throws java.io.IOException {
        File pending = new File(dataRoot, "pending_invite.txt");
        if (pendingInvite() == null)
            throw new java.io.IOException("The pending invitation is no longer valid.");
        File ready = new File(dataRoot, "join_link.txt");
        try {
            java.nio.file.Files.move(pending.toPath(), ready.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(pending.toPath(), ready.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
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
        startGame(new Intent(this, HaloActivity.class));
    }

    private void startGame(Intent intent) {
        if (lobbyUi != null && lobbyUi.hasSession() && !intent.getBooleanExtra(HaloActivity.LOBBY_MATCH, false)) {
            HaloNotice.makeText(this, "Leave the launcher lobby before starting Classic Halo or Offline Match.", HaloNotice.LENGTH_LONG).show();
            return;
        }
        if (repairBusy) {
            HaloNotice.makeText(this, "Stop map sharing or finish the repair before playing.", HaloNotice.LENGTH_LONG).show();
            return;
        }
        boolean lanSession = intent.getBooleanExtra(HaloActivity.LOBBY_MATCH, false) || "lan".equals(getSharedPreferences("launcher_ui",
            MODE_PRIVATE).getString("play_mode", "standard"));
        intent.putExtra(HaloActivity.NETWORK_SESSION, lanSession);
        // Any opted-in phone may answer local leaderboard presence while
        // its game is open, including campaign and offline bot matches.
        // This does not enable network gameplay or public sharing.
        intent.putExtra(HaloActivity.LAN_BOARD_HOST,
            LanShareSettings.career(this));
        if (getSharedPreferences("reel_settings", MODE_PRIVATE)
                .getBoolean("record_opt_in", false)) {
            android.media.projection.MediaProjectionManager projection =
                (android.media.projection.MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projection != null) {
                try {
                    // A fresh system consent for EVERY session, required by
                    // Android 14+. The recording is opt-in and local-only.
                    pendingVideoLaunch = intent;
                    Intent request = android.os.Build.VERSION.SDK_INT >= 34
                        ? projection.createScreenCaptureIntent(
                            android.media.projection.MediaProjectionConfig
                                .createConfigForDefaultDisplay())
                        : projection.createScreenCaptureIntent();
                    startActivityForResult(request, PICK_REEL_CONSENT);
                    return;
                } catch (RuntimeException refused) {
                    pendingVideoLaunch = null;
                    HaloNotice.makeText(this,
                        "Screen recording unavailable. Starting Halo normally.",
                        HaloNotice.LENGTH_LONG).show();
                }
            }
        }
        launchWithoutConsent(intent);
    }

    private void launchWithoutConsent(Intent intent) {
        // Persist the one player identity before handing off to :game.
        // A first launch must not race two random-ID initializations.
        try {
            GroupRatingLedger.recoverAbandonedHost(getFilesDir(), LanBoardIdentity.id(this),
                gameProcessStatus() == 0);
            LanBoardIdentity.prepareForGame(this);
        } catch (java.io.IOException | RuntimeException unavailable) {
            android.util.Log.w("HaloLanBoard", "Group rating preparation unavailable", unavailable);
        }
        // Transfer the peer listener to :game; do not hold both copies
        // open on one device or rely on a cloud service.
        stopBoardSharing();
        startActivity(intent);
        if (intent.getBooleanExtra(HaloActivity.LOBBY_MATCH, false))
            lobbyGameStarted = true;
        else
            finish();
    }

    /** Explicit SAF export works for both the finished reel and full
     * original video, keeping the private source intact. No broad storage
     * permission, no local server, no public FileProvider or upload. */
    void exportReelVideo(File chosen) {
        File directory = new File(dataRoot, ReelSessionIndex.REEL_DIRECTORY);
        if (chosen == null || !directory.equals(chosen.getParentFile()) ||
                !chosen.isFile() ||
                java.nio.file.Files.isSymbolicLink(chosen.toPath()) ||
                chosen.length() < 2048 ||
                chosen.length() > ReelSessionIndex.SOURCE_LIMIT ||
                !HaloVideoFiles.validName(chosen.getName()))
            return;
        pendingVideoExport = chosen.getName();
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("video/mp4");
        save.putExtra(Intent.EXTRA_TITLE, "Halo-" + chosen.getName());
        startActivityForResult(save, PICK_REEL_EXPORT);
    }

    private void exportReelToDocument(Uri destination, String name) {
        new Thread(() -> {
            boolean saved = false;
            File source = new File(new File(dataRoot,
                ReelSessionIndex.REEL_DIRECTORY), name);
            if (source.isFile() &&
                !java.nio.file.Files.isSymbolicLink(source.toPath()) &&
                HaloVideoFiles.validName(name) &&
                source.length() >= 2048 &&
                source.length() <= ReelSessionIndex.SOURCE_LIMIT) {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(
                         destination, "w")) {
                    if (output != null) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) >= 0)
                            if (read > 0) output.write(buffer, 0, read);
                        output.flush();
                        saved = true;
                    }
                } catch (Exception problem) {
                    android.util.Log.w("HaloVideo", "Could not export local reel", problem);
                }
            }
            boolean done = saved;
            runOnUiThread(() -> HaloNotice.makeText(this,
                done ? "Halo reel saved to your chosen folder." :
                    "Could not copy reel. Private original kept.",
                HaloNotice.LENGTH_LONG).show());
        }, "Halo-reel-export").start();
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics());
    }

    /** LAN IP hints only. Never silently bind the game to one of these:
     * hotspot, Wi-Fi and VPN interfaces vary across Android devices. */
    private java.util.List<String> lanIPv4Addresses() {
        java.util.LinkedHashSet<String> addresses = new java.util.LinkedHashSet<>();
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
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() &&
                        !ip.isLinkLocalAddress())
                        addresses.add(ip.getHostAddress());
                }
            }
        } catch (java.net.SocketException e) {
            // Some Android builds hide their hotspot interface. Manual entry is available.
        }
        return new java.util.ArrayList<>(addresses);
    }

    /** The file remains after an Android Force stop/reopen with a MAIN intent. */
    private boolean hasInternetInvite() {
        return pendingInvite() != null;
    }

    /** 0: not running; 1: own game process running; -1: unknown, fail closed. */
    private int gameProcessStatus() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null)
            return -1;
        java.util.List<ActivityManager.RunningAppProcessInfo> processes;
        try {
            processes = manager.getRunningAppProcesses();
        } catch (RuntimeException e) {
            return -1;
        }
        if (processes == null)
            return -1;
        String gameProcess = getPackageName() + ":game";
        int ownUid = android.os.Process.myUid();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process != null && process.uid == ownUid && gameProcess.equals(process.processName))
                return 1;
        }
        return 0;
    }

    private boolean canStartGame(TextView feedback, Button appSettings) {
        int processStatus = gameProcessStatus();
        if (processStatus == 0)
            return true;
        feedback.setText(processStatus == 1
            ? "Halo is still running. Force-stop it in Android App Settings, "
              + "then reopen this launcher to apply new settings."
            : "Android couldn't confirm the old game is stopped. Force-stop Halo "
              + "in Android App Settings, then reopen.");
        appSettings.setVisibility(View.VISIBLE);
        return false;
    }

    private GradientDrawable hudSurface(int top, int bottom, int edge) {
        GradientDrawable shape = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        shape.setCornerRadius(dp(3));
        shape.setStroke(dp(1), edge);
        return shape;
    }

    private LinearLayout hudPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackground(hudSurface(
            titleBackdropAvailable ? (HaloTheme.PANEL_TOP & 0x00ffffff) | 0x92000000 : HaloTheme.PANEL_TOP,
            titleBackdropAvailable ? (HaloTheme.PANEL_BOTTOM & 0x00ffffff) | 0x92000000 : HaloTheme.PANEL_BOTTOM,
            HUD_BORDER));
        return panel;
    }

    /** This phone serves a tiny leaderboard card while its launcher is
     * open and sharing is enabled. The game process takes over after Play. */
    void refreshBoardSharing() {
        boolean enabled = LanShareSettings.career(this);
        if (enabled && haveData() && lanBoardServer == null)
            lanBoardServer = LanBoardServer.start(this);
        else if (!enabled)
            stopBoardSharing();
    }

    void boardSharingChanged() {
        refreshBoardSharing();
        if (livePeerMonitor != null) livePeerMonitor.sharingChanged();
    }

    private void stopBoardSharing() {
        if (lanBoardServer != null) {
            lanBoardServer.stop();
            lanBoardServer = null;
        }
    }

    private void hudButton(Button button, boolean large) {
        HaloTheme.menuButtonColors(button);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 19 : 13);
        button.setAllCaps(true);
        HaloTheme.font(button, true);
        button.setMinHeight(dp(large ? 48 : 38));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
    }

    private void hudCheck(CheckBox check) {
        check.setTextColor(HUD_TEXT);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        HaloTheme.font(check, false);
        check.setButtonTintList(HaloTheme.CHECK_TINT);
        check.setGravity(Gravity.CENTER_VERTICAL);
        check.setPadding(dp(6), dp(3), dp(6), dp(3));
    }

    private void hudInput(EditText input) {
        input.setTextColor(HUD_TEXT);
        input.setHintTextColor(HUD_MUTED);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setPadding(dp(10), dp(7), dp(10), dp(7));
        input.setBackground(hudSurface(HaloTheme.PANEL_BOTTOM,
            HaloTheme.PANEL_BOTTOM, HUD_BORDER));
    }

    private TextView lanText(String message, int size) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(HUD_TEXT);
        HaloTheme.font(text, false);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setPadding(0, dp(2), 0, dp(5));
        return text;
    }

    private TextView hudSection(String message) {
        TextView label = lanText(message, 13);
        label.setTextColor(HUD_ACCENT);
        HaloTheme.font(label, true);
        label.setLetterSpacing(0.16f);
        return label;
    }

    /** Compact two-column row. Selection opens only on a deliberate tap;
     * vertical swipes on the page no longer open an Android Spinner menu. */
    private final class ChoiceButton extends Button {
        private String[] choices;
        private final String prompt;
        private int selection;
        private Runnable selectionChanged;

        ChoiceButton(String prompt, String[] choices, int selected) {
            super(LauncherActivity.this);
            this.prompt = prompt;
            this.choices = choices.clone();
            this.selection = Math.max(0, Math.min(selected, choices.length - 1));
            setAllCaps(false);
            HaloTheme.menuButtonColors(this);
            HaloTheme.font(this, true);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            setSingleLine(true);
            setEllipsize(android.text.TextUtils.TruncateAt.END);
            setPadding(dp(9), dp(4), dp(9), dp(4));
            refresh();
            setOnClickListener(view -> new HaloDialog.Builder(
                    LauncherActivity.this)
                .setTitle(prompt)
                .setSingleChoiceItems(this.choices, selection,
                    (dialog, which) -> {
                        selection = which;
                        refresh();
                        dialog.dismiss();
                        if (selectionChanged != null) selectionChanged.run();
                    })
                .setNegativeButton("CANCEL", null)
                .show());
        }

        private void refresh() {
            setText(choices[selection] + "  ▾");
            setContentDescription(prompt + ": " + choices[selection] +
                ". Tap to change.");
        }

        int getSelectedItemPosition() { return selection; }
        void replaceChoices(String[] values,int selected){
            if(values.length==0)throw new IllegalArgumentException("Empty selection");
            choices=values.clone();selection=Math.max(0,Math.min(selected,choices.length-1));refresh();
        }

        void onSelectionChanged(Runnable listener) {
            selectionChanged = listener;
            if (listener != null) listener.run();
        }
    }

    private ChoiceButton botChoice(LinearLayout layout, String label,
            String[] choices, int selection) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(2));
        TextView title = lanText(label, 11);
        title.setTextColor(HUD_MUTED);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 0.8f));
        ChoiceButton choice = new ChoiceButton(label, choices, selection);
        choice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        choice.setMinWidth(0);
        choice.setMinimumWidth(0);
        choice.setMinHeight(0);
        choice.setMinimumHeight(0);
        row.addView(choice, new LinearLayout.LayoutParams(0, dp(setupRowHeight()), 1.2f));
        layout.addView(row);
        return choice;
    }

    /** Equal-weight columns use the real available landscape width. */
    private LinearLayout setupColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout setupGrid(LinearLayout parent, LinearLayout left,
            LinearLayout right) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setBaselineAligned(false);
        grid.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1f);
        first.rightMargin = dp(7);
        grid.addView(left, first);
        grid.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return grid;
    }

    /** Visual grouping only: foreground has no layout padding or extra rows. */
    private void setupGroupBorder(View group) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(android.graphics.Color.TRANSPARENT);
        border.setCornerRadius(dp(3));
        border.setStroke(Math.max(1, dp(1)), 0x657bb8ed);
        group.setForeground(border);
    }

    private void toolsPair(LinearLayout parent, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(44), 1f);
        left.rightMargin = dp(7);
        row.addView(first, left);
        row.addView(second, new LinearLayout.LayoutParams(0, dp(44), 1f));
        parent.addView(row);
    }

    private void revealStatus(TextView view) {
        view.setVisibility(view.length() == 0 ? View.GONE : View.VISIBLE);
        view.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                view.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            }
            public void afterTextChanged(android.text.Editable text) { }
        });
    }

    /** A shared panel is reparented, never cloned: values survive tab changes. */
    private void attachSetupPanel(LinearLayout parent, View view) {
        if (view.getParent() == parent) return;
        if (view.getParent() instanceof ViewGroup)
            ((ViewGroup)view.getParent()).removeView(view);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private int setupRowHeight() {
        return getResources().getConfiguration().screenHeightDp <= 340 ? 34 : 42;
    }

    private void setupCheck(CheckBox check) {
        hudCheck(check);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        check.setMinimumHeight(0);
        check.setMinHeight(dp(setupRowHeight()));
        check.setPadding(dp(2), 0, dp(2), 0);
    }

    private LinearLayout setupChecks(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void addSetupCheck(LinearLayout row, CheckBox check) {
        setupCheck(check);
        row.addView(check, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private void setupInput(LinearLayout parent, String label, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = lanText(label, 11);
        name.setTextColor(HUD_MUTED);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 0.75f));
        input.setPadding(dp(6), dp(2), dp(6), dp(2));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(input, new LinearLayout.LayoutParams(0, dp(setupRowHeight()), 1.25f));
        parent.addView(row);
    }

    /** Native OpenCE-style tabs for Leaderboard, Offline Match, Host,
     * Join and Tools. Campaign is a choice within Offline Match. */
    private void hudTab(Button button) {
        button.setAllCaps(false);
        HaloTheme.font(button, true);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        HaloTheme.menuButtonColors(button);
        button.setMinHeight(dp(39));
        button.setPadding(dp(2), dp(3), dp(2), dp(3));
    }

    /** Prepare once automatically after the first usable foreground launch.
     * Remember the attempt before starting the separate native process: a
     * canceled, crashed, or failed capture must not create a startup loop. */
    private void maybePrepareOriginalTitle() {
        if (!launcherResumed || isFinishing() || isDestroyed() ||
                dataRoot == null || dashboardPanel == null ||
                titleBackdropAvailable || !haveData() ||
                (lobbyUi != null && lobbyUi.active()) ||
                repairBusy || gameProcessStatus() != 0 ||
                pendingVideoLaunch != null ||
                hasInternetInvite() || new File(dataRoot, "join_link.txt").exists())
            return;
        SharedPreferences prefs = getSharedPreferences("launcher_title", MODE_PRIVATE);
        long version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0)
                .getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return;
        }
        if (!TitleBackdropFiles.shouldPrepare(titleBackdropAvailable,
                prefs.getLong("attempted_version", -1), version))
            return;
        // Synchronously persist before launching: survives process death.
        if (!prefs.edit().putLong("attempted_version", version).commit()) return;
        try {
            TitleBackdropFiles.prepare(getFilesDir());
            TitleBackdropFiles.writeStatus(getFilesDir(), "Preparing: launching title");
            startActivity(new Intent(this, HaloActivity.class)
                .putExtra(HaloActivity.TITLE_CAPTURE, true));
        } catch (Exception unavailable) {
            TitleBackdropFiles.writeStatus(getFilesDir(), "Title preparation unavailable (" +
                unavailable.getClass().getSimpleName() + ")");
            updateTitleStatus();
            HaloNotice.makeText(this, "Original title unavailable; Halo still works. " +
                (unavailable.getMessage() == null ? "" : unavailable.getMessage()),
                HaloNotice.LENGTH_LONG).show();
        }
    }

    private void buildLanInterface() {
        if (lobbyUi != null) { lobbyUi.close(); lobbyUi = null; }
        if (titleBackdrop != null) titleBackdrop.pauseTitle();
        titleBackdrop = null;
        titleBackdropAvailable = TitleBackdropFiles.ready(getFilesDir());
        titleStatusView = null;
        LanConfig.Settings settings = new LanConfig.Settings();
        String warning = "";
        try {
            settings = LanConfig.load(new File(dataRoot, "config.toml"));
        } catch (java.io.IOException e) {
            warning = "Couldn't read the current settings: " + e.getMessage();
        }

        final boolean invitePending = hasInternetInvite();
        java.util.List<String> ips = lanIPv4Addresses();


        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { HaloTheme.BACKGROUND_TOP, HaloTheme.BACKGROUND_CENTER,
                HaloTheme.BACKGROUND_BOTTOM }));
        if (titleBackdropAvailable) {
            titleBackdrop = new LauncherBackdrop(this, getFilesDir(), message -> updateTitleStatus());
            stage.addView(titleBackdrop, new android.widget.FrameLayout.LayoutParams(-1, -1));
            root.setBackgroundColor(0x30091320);
        }
        stage.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout masthead = new LinearLayout(this);
        masthead.setOrientation(LinearLayout.HORIZONTAL);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        masthead.setPadding(dp(10), dp(1), dp(10), dp(1));
        TextView halo = lanText("HALO", 23);
        halo.setPadding(0, 0, 0, 0);
        HaloTheme.font(halo, true);
        halo.setLetterSpacing(0.18f);
        halo.setTextColor(HaloTheme.FOCUS_TEXT);
        masthead.addView(halo);
        TextView subtitle = lanText("COMBAT EVOLVED", 10);
        subtitle.setPadding(0, 0, 0, 0);
        subtitle.setTextColor(HUD_MUTED);
        subtitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams subtitleLayout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLayout.leftMargin = dp(14);
        // The status strip stays visible on every launcher tab, including
        // Profile. Keep it one line so landscape game setup doesn't grow.
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleLayout.width = 0;
        subtitleLayout.weight = 0.65f;
        masthead.addView(subtitle, subtitleLayout);
        Button livePeers = new Button(this);
        hudButton(livePeers, false);
        livePeers.setAllCaps(false);
        livePeers.setSingleLine(true);
        livePeers.setEllipsize(android.text.TextUtils.TruncateAt.END);
        livePeers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        livePeers.setMinWidth(0);
        livePeers.setMinimumWidth(0);
        LinearLayout.LayoutParams liveParams = new LinearLayout.LayoutParams(0, dp(setupRowHeight()), 1f);
        liveParams.leftMargin = dp(8);
        masthead.addView(livePeers, liveParams);
        if (socialUi != null) socialUi.close();
        if (livePeerMonitor != null) livePeerMonitor.close();
        livePeerMonitor = new LanBoardPresenceMonitor(this, livePeers);
        if (launcherResumed) livePeerMonitor.resume();
        root.addView(masthead);
        View accent = new View(this);
        accent.setBackgroundColor(HUD_ACCENT);
        root.addView(accent, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // Host and Join are roles inside a single Lobby tab.
        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setPadding(dp(6), dp(2), dp(6), dp(2));
        String[] modeNames = {
            "Leaderboard", "Lobby", "Offline Match", "Tools"
        };
        Button[] modeTabs = new Button[modeNames.length];
        for (int i = 0; i < modeNames.length; i++) {
            Button tab = new Button(this);
            tab.setText(modeNames[i]);
            hudTab(tab);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(setupRowHeight()), 1.0f);
            if (i > 0) params.leftMargin = dp(5);
            modeBar.addView(tab, params);
            modeTabs[i] = tab;
        }
        root.addView(modeBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(7), dp(3), dp(7), dp(3));
        scroll.addView(content);
        LauncherActionDock actionDock = new LauncherActionDock(this, scroll, content);
        root.addView(actionDock, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout toolsPage = new LinearLayout(this);
        toolsPage.setOrientation(LinearLayout.VERTICAL);
        toolsPage.setVisibility(View.GONE);
        content.addView(toolsPage);

        dashboardPanel = hudPanel();
        dashboardPanel.setVisibility(View.GONE);
        content.addView(dashboardPanel);
        socialUi = new LanBoardUi(this, dataRoot, dashboardPanel, scroll);
        LinearLayout lobbyPanel = setupColumn();
        lobbyPanel.setVisibility(View.GONE);
        content.addView(lobbyPanel);
        // Original native setup remains reachable through Classic Halo.
        LinearLayout lanPanel = hudPanel();
        lanPanel.setPadding(dp(7), dp(3), dp(7), dp(3));
        content.addView(lanPanel);
        LinearLayout roleColumn = setupColumn();
        LinearLayout networkColumn = setupColumn();
        setupGrid(lanPanel, roleColumn, networkColumn);
        RadioGroup roles = new RadioGroup(this);
        roles.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton hostRole = new RadioButton(this);
        hostRole.setId(View.generateViewId());
        hostRole.setText("HOST");
        hostRole.setTextColor(HUD_TEXT);
        hostRole.setButtonTintList(HaloTheme.CHECK_TINT);
        RadioButton joinRole = new RadioButton(this);
        joinRole.setId(View.generateViewId());
        joinRole.setText("JOIN");
        joinRole.setTextColor(HUD_TEXT);
        joinRole.setButtonTintList(HaloTheme.CHECK_TINT);
        roles.addView(hostRole);
        roles.addView(joinRole);
        String rememberedRole = getSharedPreferences("launcher_ui", MODE_PRIVATE)
            .getString("lan_role", settings.peer.isEmpty() ? "host" : "join");
        roles.check("join".equals(rememberedRole) ?
            joinRole.getId() : hostRole.getId());
        // Visible only within the original Classic Halo setup.
        roles.setVisibility(View.VISIBLE);
        Button backToLobby = new Button(this);
        backToLobby.setText("BACK TO LOBBIES");
        hudButton(backToLobby, false);
        roleColumn.addView(backToLobby, new LinearLayout.LayoutParams(-1, dp(setupRowHeight())));
        roleColumn.addView(roles);

        TextView feedback = lanText(inviteError == null
            ? warning : warning + "  " + inviteError, 12);
        feedback.setTextColor(HUD_WARNING);

        LinearLayout hostPanel = new LinearLayout(this);
        hostPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout hostConnection = setupColumn();
        LinearLayout hostBotOptions = setupColumn();
        setupGroupBorder(hostConnection);
        setupGroupBorder(hostBotOptions);
        hostPanel.addView(hostConnection);
        hostPanel.addView(hostBotOptions);
        TextView hostAddress = lanText(ips.isEmpty()
            ? "YOUR IP: NOT DETECTED"
            : "YOUR IP: " + android.text.TextUtils.join(" / ", ips), 14);
        hostAddress.setTextColor(HUD_ACCENT);
        HaloTheme.font(hostAddress, true);
        hostAddress.setPadding(dp(5), dp(3), dp(5), dp(3));
        hostAddress.setBackground(hudSurface(
            HaloTheme.PANEL_TOP, HaloTheme.PANEL_BOTTOM, HUD_BORDER));
        hostConnection.addView(hostAddress);
        if (ips.size() > 1)
            hostAddress.setContentDescription(hostAddress.getText() +
                ". Use the address belonging to the active Wi-Fi hotspot.");
        // Serving is explicitly scoped to a LAN host's game process. This
        // preference never enables a socket for standard, offline or joins.
        CheckBox shareUpdates = new CheckBox(this);
        shareUpdates.setText("SHARE APK");
        shareUpdates.setContentDescription("Share my Halo version with LAN players");
        shareUpdates.setChecked(getSharedPreferences("launcher_ui", MODE_PRIVATE)
            .getBoolean("lan_update_share", true));
        LinearLayout hostChecks = setupChecks(hostConnection);
        addSetupCheck(hostChecks, shareUpdates);
        // Live-player and sharing controls remain in the masthead/Leaderboard.

        final int[] lanBotCounts = LanConfig.choicesIncluding(
            settings.systemLinkBotCount);
        String[] lanBotChoices = new String[lanBotCounts.length];
        for (int index = 0; index < lanBotCounts.length; index++)
            lanBotChoices[index] = lanBotCounts[index] == 0 ?
                "None" : Integer.toString(lanBotCounts[index]);
        ChoiceButton lanBots = botChoice(hostBotOptions, "BOT COUNT",
            lanBotChoices, LanConfig.choiceIndex(lanBotCounts,
                settings.systemLinkBotCount));
        String[] lanSkillChoices = { "Recruit", "Easy", "Normal", "Heroic", "Veteran", "Legendary" };
        int lanSkillSelection = 2;
        for (int index = 0; index < LanConfig.BOT_SKILLS.length; index++) {
            if (LanConfig.BOT_SKILLS[index].equals(settings.botSkill)) {
                lanSkillSelection = index;
                break;
            }
        }
        ChoiceButton lanSkill = botChoice(hostBotOptions, "DIFFICULTY",
            lanSkillChoices, lanSkillSelection);
        // Driving is permanently enabled when this launcher hosts LAN bots.
        roleColumn.addView(hostPanel);

        LinearLayout joinPanel = new LinearLayout(this);
        joinPanel.setOrientation(LinearLayout.VERTICAL);
        setupGroupBorder(joinPanel);
        TextView discoveryStatus = lanText("Find a LAN host or enter its IP.", 11);
        discoveryStatus.setTextColor(HUD_ACCENT);
        joinPanel.addView(discoveryStatus);
        LinearLayout joinActions = new LinearLayout(this);
        joinActions.setOrientation(LinearLayout.HORIZONTAL);
        joinActions.setPadding(0, dp(2), 0, dp(2));
        joinPanel.addView(joinActions);
        Button findHosts = new Button(this);
        findHosts.setText("FIND HOSTS");
        hudButton(findHosts, false);
        joinActions.addView(findHosts,
            new LinearLayout.LayoutParams(0, dp(setupRowHeight()), 1f));
        LinearLayout manualHostPanel = new LinearLayout(this);
        manualHostPanel.setOrientation(LinearLayout.VERTICAL);
        /* Auto-discovery remains primary; the direct-IP field is a single
         * visible fallback, without an extra disclosure button. */
        // The host IP stays directly editable on this page.
        EditText hostIp = new EditText(this);
        hostIp.setSingleLine(true);
        hostIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hostIp.setHint("Copy from host's screen");
        hostIp.setContentDescription("Host's IP address. Copy the address displayed on the phone hosting Halo.");
        String previousHost = getSharedPreferences("launcher_ui", MODE_PRIVATE)
            .getString("last_host", "");
        hostIp.setText(settings.peer.isEmpty() ? previousHost : settings.peer);
        hostIp.setSelectAllOnFocus(true);
        hudInput(hostIp);
        setupInput(manualHostPanel, "HOST'S IP ADDRESS", hostIp);
        joinPanel.addView(manualHostPanel);
        LanHostPicker hostPicker = new LanHostPicker(
            this, hostIp, discoveryStatus, findHosts, manualHostPanel);
        findHosts.setOnClickListener(view -> hostPicker.search(false, null));
        Button checkHostUpdate = new Button(this);
        checkHostUpdate.setText("CHECK UPDATE");
        hudButton(checkHostUpdate, false);
        checkHostUpdate.setOnClickListener(view -> {
            if (gameProcessStatus() != 0) {
                feedback.setText("Close Halo before checking for an update.");
                return;
            }
            if (hostPicker.isSearching() ||
                LanUpdateProtocol.privateAddress(hostIp.getText().toString().trim()) == null) {
                hostPicker.search(true, () -> checkHostUpdate.performClick());
                return;
            }
            getSharedPreferences("launcher_ui", MODE_PRIVATE).edit()
                .putString("play_mode", "lan").putString("lan_role", "join")
                .putString("last_host", hostIp.getText().toString().trim())
                .apply();
            checkHostUpdate.setEnabled(false);
            feedback.setText("Checking the host update service on TCP 5162...");
            LanUpdateClient.check(this, hostIp.getText().toString().trim(),
                true, null, () -> checkHostUpdate.setEnabled(true));
        });
        LinearLayout.LayoutParams updateButtonParams =
            new LinearLayout.LayoutParams(0, dp(setupRowHeight()), 1f);
        updateButtonParams.leftMargin = dp(8);
        joinActions.addView(checkHostUpdate, updateButtonParams);
        // These are global recorder preferences, not a join-only recording mode.
        // Store on change so Host and offline multiplayer use the same choices.
        LinearLayout recordingPanel = setupColumn();
        setupGroupBorder(recordingPanel);
        recordingPanel.addView(hudSection("MATCH RECORDING"));
        LinearLayout recordingChecks = setupChecks(recordingPanel);
        CheckBox recordHighlights = new CheckBox(this);
        recordHighlights.setText("RECORD MATCHES");
        recordHighlights.setContentDescription("Record multiplayer matches without audio");
        recordHighlights.setChecked(getSharedPreferences("reel_settings",
            MODE_PRIVATE).getBoolean("record_opt_in", false));
        addSetupCheck(recordingChecks, recordHighlights);

        CheckBox keepFullVideo = new CheckBox(this);
        keepFullVideo.setText("KEEP FULL RECORDING");
        keepFullVideo.setContentDescription("Keep full match recordings");
        keepFullVideo.setChecked(getSharedPreferences("reel_settings", MODE_PRIVATE)
            .getBoolean("keep_full_match", false));
        keepFullVideo.setOnCheckedChangeListener((view, checked) ->
            getSharedPreferences("reel_settings", MODE_PRIVATE).edit()
                .putBoolean("keep_full_match", checked).apply());
        addSetupCheck(recordingChecks, keepFullVideo);
        // Give the longer label more of the existing row; allow wrapping
        // at large font scales rather than shrinking or clipping the text.
        LinearLayout.LayoutParams fullRecordingParams =
            (LinearLayout.LayoutParams) keepFullVideo.getLayoutParams();
        fullRecordingParams.weight = 1.25f;
        keepFullVideo.setLayoutParams(fullRecordingParams);
        keepFullVideo.setSingleLine(false);
        recordHighlights.setSingleLine(false);
        keepFullVideo.setEnabled(recordHighlights.isChecked());
        recordHighlights.setOnCheckedChangeListener((view, checked) -> {
            getSharedPreferences("reel_settings", MODE_PRIVATE).edit()
                .putBoolean("record_opt_in", checked).apply();
            keepFullVideo.setEnabled(checked);
        });

        recordingPanel.addView(lanText(
            "Silent, all multiplayer modes. No campaign/co-op. Keep full or highlights only.", 10));
        LinearLayout joinChecks = setupChecks(joinPanel);
        roleColumn.addView(joinPanel);

        LinearLayout advanced = setupColumn();
        setupGroupBorder(advanced);
        advanced.addView(hudSection("NETWORK OPTIONS"));
        EditText local = new EditText(this);
        local.setSingleLine(true);
        local.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        local.setHint("Leave blank for auto");
        local.setContentDescription("Your IP address, optional. Leave blank unless automatic network detection fails.");
        local.setText(settings.local);
        hudInput(local);
        setupInput(advanced, "YOUR IP (OPTIONAL)", local);
        CheckBox lanOnly = new CheckBox(this);
        lanOnly.setText("LAN ONLY");
        lanOnly.setChecked(invitePending ? false : settings.lanOnly);
        setupCheck(lanOnly);
        lanOnly.setContentDescription("LAN only. Host port 5150. Joining client 5151.");
        networkColumn.addView(advanced);
        networkColumn.addView(recordingPanel);
        Runnable updateRole = () -> {
            boolean hosting = hostRole.isChecked();
            hostPanel.setVisibility(hosting ? View.VISIBLE : View.GONE);
            joinPanel.setVisibility(hosting ? View.GONE : View.VISIBLE);
            recordingPanel.setVisibility(hosting ? View.GONE : View.VISIBLE);
            LinearLayout checks = hosting ? hostChecks : joinChecks;
            if (lanOnly.getParent() != checks) {
                if (lanOnly.getParent() instanceof ViewGroup)
                    ((ViewGroup)lanOnly.getParent()).removeView(lanOnly);
                checks.addView(lanOnly, new LinearLayout.LayoutParams(0, -2, 1f));
            }
        };
        updateRole.run();

        if (invitePending) {
            lanPanel.addView(lanText("An internet invitation is pending. Join using online mode "
                + "or discard the invitation to play offline.", 12));
            Button discard = new Button(this);
            discard.setText("DISCARD INVITE");
            hudButton(discard, false);
            discard.setOnClickListener(view -> {
                try {
                    java.nio.file.Files.deleteIfExists(new File(dataRoot,
                        "pending_invite.txt").toPath());
                    buildLanInterface();
                } catch (java.io.IOException e) {
                    feedback.setText("Couldn't discard the invitation. Check app storage.");
                }
            });
            lanPanel.addView(discard);
        }

        // Offline bot options are part of the page rather than a second dialog.
        SharedPreferences offlinePreferences = getSharedPreferences("offline_bots", MODE_PRIVATE);
        LinearLayout offlinePanel = hudPanel();
        offlinePanel.setPadding(dp(7), dp(3), dp(7), dp(3));
        content.addView(offlinePanel);
        LinearLayout offlineLeft = setupColumn();
        LinearLayout offlineRight = setupColumn();
        setupGrid(offlinePanel, offlineLeft, offlineRight);
        SharedPreferences launcherPreferences = getSharedPreferences("launcher_ui", MODE_PRIVATE);
        String savedMode = invitePending ? "lan" :
            launcherPreferences.getString("play_mode", "lan");
        RadioGroup offlineType = new RadioGroup(this);
        offlineType.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton botMatch = new RadioButton(this);
        botMatch.setId(View.generateViewId());
        botMatch.setText("BOT MATCH");
        botMatch.setTextColor(HUD_TEXT);
        botMatch.setButtonTintList(HaloTheme.CHECK_TINT);
        HaloTheme.font(botMatch, true);
        RadioButton campaign = new RadioButton(this);
        campaign.setId(View.generateViewId());
        campaign.setText("CAMPAIGN");
        campaign.setTextColor(HUD_TEXT);
        campaign.setButtonTintList(HaloTheme.CHECK_TINT);
        HaloTheme.font(campaign, true);
        botMatch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        campaign.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        offlineType.addView(botMatch, new RadioGroup.LayoutParams(0, dp(setupRowHeight()), 1f));
        offlineType.addView(campaign, new RadioGroup.LayoutParams(0, dp(setupRowHeight()), 1f));
        String offlineChoice = launcherPreferences.getString("offline_game_type",
            "standard".equals(savedMode) ? "campaign" : "bots");
        offlineType.check("campaign".equals(offlineChoice) ? campaign.getId() : botMatch.getId());
        setupGroupBorder(offlineType);
        offlineLeft.addView(offlineType);
        TextView campaignInfo = lanText("Launch the original Halo campaign. "
            + "Your existing profiles and checkpoints are kept.", 12);
        offlineRight.addView(campaignInfo);
        LinearLayout offlineBotOptions = new LinearLayout(this);
        offlineBotOptions.setOrientation(LinearLayout.VERTICAL);
        setupGroupBorder(offlineBotOptions);
        offlineLeft.addView(offlineBotOptions);
        String[] offlineCounts = new String[127];
        for (int i = 0; i < offlineCounts.length; i++)
            offlineCounts[i] = Integer.toString(i + 1);
        ChoiceButton offlineCount = botChoice(offlineBotOptions, "BOT COUNT",
            offlineCounts, offlinePreferences.getInt("count", 3) - 1);
        String[] offlineSkills = { "Recruit", "Easy", "Normal", "Heroic", "Veteran", "Legendary" };
        ChoiceButton offlineSkill = botChoice(offlineBotOptions, "DIFFICULTY",
            offlineSkills, offlinePreferences.getInt("skill", 2));
        // A small direct-launch proof before replacing the existing setup.
        // Classic remains available, and only installed stock maps are offered.
        final LauncherMatch.MapChoice[] directMaps = LauncherMatch.installedMaps(dataRoot);
        String[] directMapLabels = new String[directMaps.length];
        int directMapSelection = 0;
        String rememberedMap = offlinePreferences.getString("direct_map", "");
        for (int i = 0; i < directMaps.length; i++) {
            directMapLabels[i] = directMaps[i].label;
            if (directMaps[i].id.equals(rememberedMap)) directMapSelection = i;
        }
        LinearLayout directMatchPanel = setupColumn();
        setupGroupBorder(directMatchPanel);
        offlineLeft.addView(directMatchPanel);
        ChoiceButton directMap = botChoice(directMatchPanel, "MAP",
            directMapLabels, directMapSelection);
        java.util.ArrayList<MatchWorkshop.Preset> directPresets=new java.util.ArrayList<>();
        for(String base:MatchWorkshop.BASE_IDS)directPresets.add(MatchWorkshop.Preset.standard(base));
        ChoiceButton directMode = botChoice(directMatchPanel, "GAME MODE",
            MatchWorkshop.BASE_LABELS, Math.max(0,MatchWorkshop.baseIndex(
                offlinePreferences.getString("direct_mode", "team_slayer"))));
        Runnable refreshDirectPresets=()->{
            String selected=offlinePreferences.getString("direct_mode","team_slayer");
            if(directMode.getSelectedItemPosition()<directPresets.size()){
                MatchWorkshop.Preset old=directPresets.get(directMode.getSelectedItemPosition());
                if(!old.id.isEmpty())selected=old.id;
            }
            directPresets.clear();
            for(String base:MatchWorkshop.BASE_IDS)directPresets.add(MatchWorkshop.Preset.standard(base));
            try{directPresets.addAll(MatchWorkshop.read(getFilesDir()).presets);}
            catch(java.io.IOException invalid){feedback.setText("Saved Workshop unavailable; using standard modes.");}
            String[] names=new String[directPresets.size()];int selectedIndex=1;
            for(int i=0;i<names.length;i++){
                MatchWorkshop.Preset preset=directPresets.get(i);
                String key=preset.id.isEmpty()?preset.base:preset.id;
                names[i]=preset.name+(preset.id.isEmpty()?"":" • Custom");
                if(key.equals(selected))selectedIndex=i;
            }
            directMode.replaceChoices(names,selectedIndex);
        };
        refreshDirectPresets.run();
        directMode.onSelectionChanged(()->{
            MatchWorkshop.Preset selected=directPresets.get(directMode.getSelectedItemPosition());
            offlinePreferences.edit().putString("direct_mode",selected.id.isEmpty()?selected.base:selected.id).apply();
        });
        directMap.onSelectionChanged(() -> ((View) directMode.getParent()).setVisibility(
            directMap.getSelectedItemPosition() == 0 ? View.GONE : View.VISIBLE));

        String[] offlineTeams = { "Balanced", "Bots vs players" };
        LinearLayout offlineTeamOptions = setupColumn();
        setupGroupBorder(offlineTeamOptions);
        offlineRight.addView(offlineTeamOptions);
        ChoiceButton offlineTeam = botChoice(offlineTeamOptions, "TEAMS",
            offlineTeams, offlinePreferences.getInt("team", 0));

        /* The inactive 2142 commander experiment is retired. Keep only
         * individual host-authorized killstreak rewards in this launcher. */
        LinearLayout killstreakPanel = setupColumn();
        setupGroupBorder(killstreakPanel);
        killstreakPanel.setVisibility(View.GONE);
        offlineRight.addView(killstreakPanel);
        SharedPreferences killstreakPrefs =
            getSharedPreferences("killstreak_controls", MODE_PRIVATE);
        KillstreakConfig.Options killstreakChoices = new KillstreakConfig.Options();
        for (String key : KillstreakConfig.KEYS)
            killstreakChoices.set(key, killstreakPrefs.getBoolean(key,
                killstreakChoices.get(key)));
        killstreakPanel.addView(hudSection("KILLSTREAK REWARDS"));
        LinearLayout rewardChecks = setupChecks(killstreakPanel);
        String[] killstreakLabels = {
            "ENABLE",
            "RETIRED ORBITAL (DISABLED)",
            "RADIO"
        };
        CheckBox[] killstreakChecks = new CheckBox[KillstreakConfig.KEYS.length];
        for (int i = 0; i < KillstreakConfig.KEYS.length; i++) {
            CheckBox check = new CheckBox(this);
            check.setText(killstreakLabels[i]);
            check.setChecked(i == 1 ? false :
                killstreakChoices.get(KillstreakConfig.KEYS[i]));
            check.setContentDescription(i == 0 ? "Enable killstreak rewards" :
                i == 2 ? "Killstreak radio announcements" : "Retired orbital");
            /* Preserve the old OFF key without exposing it. */
            if (i != 1) addSetupCheck(rewardChecks, check);
            killstreakChecks[i] = check;
        }
        Runnable updateKillstreakChecks = () -> {
            boolean enabled = killstreakChecks[0].isChecked();
            for (int i = 1; i < killstreakChecks.length; i++)
                killstreakChecks[i].setEnabled(enabled);
        };
        for (CheckBox check : killstreakChecks)
            check.setOnCheckedChangeListener((button, checked) ->
                updateKillstreakChecks.run());
        updateKillstreakChecks.run();
        TextView rewardSummary = lanText("3: camo + overshield  •  5: recon  •  10: God Mode (60s)", 10);
        rewardSummary.setContentDescription("3 kills: camo and overshield. 5 kills: refresh and recon. "
            + "10 kills: invisible and invulnerable for 60 seconds. Death or match end cancels protection.");
        killstreakPanel.addView(rewardSummary);

        // Seven visible user actions. Preserve validation/rollback in the
        // underlying import and repair helpers, not in more menu buttons.
        LinearLayout repairPanel = hudPanel();
        toolsPage.addView(repairPanel);
        repairPanel.addView(hudSection("MAP MANAGEMENT"));
        stockCampaignAuditButton = new Button(this);
        stockCampaignAuditButton.setText("CHECK MY MAPS");
        hudButton(stockCampaignAuditButton, false);
        stockCampaignAuditButton.setOnClickListener(view -> auditStockCampaignMaps());
        repairHostButton = new Button(this);
        repairHostButton.setText("REPAIR FROM HOST");
        hudButton(repairHostButton, false);
        toolsPair(repairPanel, stockCampaignAuditButton, repairHostButton);

        mapAuditProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mapAuditProgress.setVisibility(View.GONE);
        mapAuditProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(HUD_ACCENT));
        repairPanel.addView(mapAuditProgress, new LinearLayout.LayoutParams(-1, dp(5)));
        repairStatus = lanText("", 12);
        repairStatus.setTextColor(HUD_TEXT);
        revealStatus(repairStatus);
        repairPanel.addView(repairStatus);
        stockMapResults = setupColumn();
        repairPanel.addView(stockMapResults);

        shareMapsButton = new Button(this);
        shareMapsButton.setText("SHARE MAPS");
        hudButton(shareMapsButton, false);
        receiveMapsButton = new Button(this);
        receiveMapsButton.setText("RECEIVE MAPS");
        hudButton(receiveMapsButton, false);
        toolsPair(repairPanel, shareMapsButton, receiveMapsButton);

        TextView transferStatus = lanText("", 12);
        revealStatus(transferStatus);
        repairPanel.addView(transferStatus);
        Button pauseMaps = new Button(this);
        pauseMaps.setText("STOP / PAUSE");
        hudButton(pauseMaps, false);
        repairPanel.addView(pauseMaps, new LinearLayout.LayoutParams(-1, dp(40)));
        lanMapRepair = new LanMapRepair(this, dataRoot, shareMapsButton, repairHostButton,
            receiveMapsButton, pauseMaps, transferStatus,
            () -> !repairBusy && gameProcessStatus() == 0,
            this::setMapToolsBusy, this::auditStockCampaignMaps);

        customMapImportButton = new Button(this);
        customMapImportButton.setText("IMPORT MAPS");
        hudButton(customMapImportButton, false);
        customMapImportButton.setOnClickListener(view -> {
            if (repairBusy) return;
            if (gameProcessStatus() != 0) {
                repairStatus.setText("Close Halo before importing maps.");
                return;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(picker, PICK_CUSTOM_MAPS);
        });
        repairPanel.addView(customMapImportButton, new LinearLayout.LayoutParams(-1, dp(44)));
        repairPanel.addView(lanText("Map files, BMP previews or ZIP archives from this device.", 10));
        customMapStatus = repairStatus;
        diagnosticStatus = repairStatus;

        // Campaign progress is in save/u and save/z under app-owned storage.
        // An update preserves these, but Android uninstall/clear-data does
        // not. Offer a portable backup in a player-selected external location.
        LinearLayout campaignSavesPanel = hudPanel();
        toolsPage.addView(campaignSavesPanel);
        campaignSavesPanel.addView(hudSection("CAMPAIGN SAVES"));
        campaignSavesPanel.addView(lanText(
            "Never uninstall Halo to update. Back up outside Android/data.", 11));
        campaignBackupButton = new Button(this);
        campaignBackupButton.setText("BACK UP CAMPAIGN PROGRESS");
        hudButton(campaignBackupButton, false);
        campaignBackupButton.setOnClickListener(view -> {
            if (repairBusy)
                return;
            if (gameProcessStatus() != 0) {
                campaignSaveStatus.setText(
                    "Close or force-stop Halo before backing up saves.");
                return;
            }
            if (!CampaignSaveArchive.hasSaves(dataRoot)) {
                campaignSaveStatus.setText(
                    "No saved campaign profiles found yet.");
                return;
            }
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("application/zip");
            picker.putExtra(Intent.EXTRA_TITLE,
                "Halo-Campaign-Backup-" +
                new java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                    java.util.Locale.US).format(new java.util.Date()) + ".zip");
            startActivityForResult(picker, PICK_CAMPAIGN_BACKUP);
        });

        campaignRestoreButton = new Button(this);
        campaignRestoreButton.setText("RESTORE CAMPAIGN BACKUP");
        hudButton(campaignRestoreButton, false);
        campaignRestoreButton.setOnClickListener(view -> {
            if (repairBusy)
                return;
            if (gameProcessStatus() != 0) {
                campaignSaveStatus.setText(
                    "Close or force-stop Halo before restoring saves.");
                return;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("application/zip");
            startActivityForResult(picker, PICK_CAMPAIGN_RESTORE);
        });
        toolsPair(campaignSavesPanel, campaignBackupButton, campaignRestoreButton);

        campaignSaveStatus = lanText(
            "",
            12);
        campaignSaveStatus.setTextColor(HUD_WARNING);
        revealStatus(campaignSaveStatus);
        campaignSavesPanel.addView(campaignSaveStatus);
        titleStatusView = lanText("", 11);
        titleStatusView.setTextColor(HUD_WARNING);
        titleStatusView.setVisibility(View.GONE);
        toolsPage.addView(titleStatusView);
        updateTitleStatus();

        // Compact action over the empty lower-right part of the active page.
        // Status/error text occupies a separate scrollable region if needed.
        LinearLayout footer = setupColumn();
        LinearLayout statusColumn = setupColumn();
        ScrollView statusScroll = new ScrollView(this);
        statusScroll.setFillViewport(false);
        statusScroll.addView(statusColumn);

        // Internal mode flags preserve the existing native launch branches.
        // The four tabs and offline radio group are the visible controls.
        CheckBox offlineMode = new CheckBox(this);
        CheckBox lanMode = new CheckBox(this);
        lanMode.setChecked("lan".equals(savedMode));
        offlineMode.setChecked(!lanMode.isChecked() && botMatch.isChecked());
        final boolean[] toolsVisible = { false };
        final boolean[] homeVisible = { !invitePending };
        final boolean[] classicLan = { invitePending };
        if (invitePending) {
            roles.check(joinRole.getId());
            lanMode.setChecked(true);
            offlineMode.setChecked(false);
            updateRole.run();
        }
        Runnable updateRewardVisibility = () -> {
            boolean playing = !toolsVisible[0] && !homeVisible[0];
            boolean available = playing && (offlineMode.isChecked() ||
                (lanMode.isChecked() && classicLan[0] && hostRole.isChecked() &&
                    lanOnly.isChecked() && !invitePending));
            if (available) attachSetupPanel(offlineMode.isChecked() ? offlineRight : networkColumn,
                killstreakPanel);
            killstreakPanel.setVisibility(available ? View.VISIBLE : View.GONE);
        };
        roles.setOnCheckedChangeListener((group, id) -> {
            updateRole.run();
            updateRewardVisibility.run();
        });
        lanOnly.setOnCheckedChangeListener((button, checked) ->
            updateRewardVisibility.run());
        Runnable updateMode = () -> {
            boolean playing = !toolsVisible[0] && !homeVisible[0];
            offlinePanel.setVisibility(playing && !lanMode.isChecked() ?
                View.VISIBLE : View.GONE);
            offlineBotOptions.setVisibility(offlineMode.isChecked() ? View.VISIBLE : View.GONE);
            directMatchPanel.setVisibility(offlineMode.isChecked() ? View.VISIBLE : View.GONE);
            offlineTeamOptions.setVisibility(offlineMode.isChecked() ? View.VISIBLE : View.GONE);
            campaignInfo.setVisibility(offlineMode.isChecked() ? View.GONE : View.VISIBLE);
            boolean lobbyVisible = playing && lanMode.isChecked() && !classicLan[0];
            boolean launchVisible = playing && !lobbyVisible;
            lanPanel.setVisibility(playing && lanMode.isChecked() && classicLan[0] ?
                View.VISIBLE : View.GONE);
            lobbyPanel.setVisibility(lobbyVisible ? View.VISIBLE : View.GONE);
            if (lobbyUi != null) lobbyUi.setVisible(lobbyVisible);
            toolsPage.setVisibility(toolsVisible[0] ? View.VISIBLE : View.GONE);
            dashboardPanel.setVisibility(homeVisible[0] ?
                View.VISIBLE : View.GONE);
            if (socialUi != null) socialUi.setVisible(homeVisible[0]);
            footer.setVisibility(launchVisible ? View.VISIBLE : View.GONE);
            statusScroll.setVisibility(launchVisible ? View.VISIBLE : View.GONE);
            updateRewardVisibility.run();
            actionDock.setSetupPage(launchVisible ?
                (lanMode.isChecked() ? lanPanel : offlinePanel) : null,
                launchVisible ? (lanMode.isChecked() ? networkColumn : offlineRight) : null);
            if (playButton != null)
                playButton.setVisibility(launchVisible ? View.VISIBLE : View.GONE);
            int chosen = homeVisible[0] ? 0 : toolsVisible[0] ? 3 :
                lanMode.isChecked() ? 1 : 2;
            for (int i = 0; i < modeTabs.length; i++)
                modeTabs[i].setSelected(i == chosen);
            scroll.scrollTo(0, 0);
        };
        offlineMode.setOnCheckedChangeListener((button, selected) -> {
            if (selected)
                lanMode.setChecked(false);
            updateMode.run();
        });
        lanMode.setOnCheckedChangeListener((button, selected) -> {
            if (selected)
                offlineMode.setChecked(false);
            updateMode.run();
        });
        offlineType.setOnCheckedChangeListener((group, selected) -> {
            boolean bots = selected == botMatch.getId();
            launcherPreferences.edit().putString("offline_game_type", bots ? "bots" : "campaign").apply();
            if (!lanMode.isChecked()) offlineMode.setChecked(bots);
            updateMode.run();
        });
        lobbyUi = new LauncherLobbyUi(this, dataRoot, lobbyPanel, () -> {
            classicLan[0] = true;
            updateMode.run();
            if (joinRole.isChecked() && lanOnly.isChecked() && !invitePending)
                hostPicker.search(false, null);
        }, () -> !repairBusy && gameProcessStatus() == 0 && !hasInternetInvite(), (arguments, hosting) -> {
            Intent game = new Intent(this, HaloActivity.class);
            game.putExtra(HaloActivity.LOBBY_MATCH, true);
            game.putExtra(HaloActivity.OFFLINE_BOT_ARGUMENTS, arguments);
            game.putExtra(HaloActivity.LAN_DISCOVERY_HOST, hosting);
            game.putExtra(HaloActivity.LAN_UPDATE_HOST, hosting && shareUpdates.isChecked());
            startGame(game);
        });
        backToLobby.setOnClickListener(view -> {
            classicLan[0] = false;
            updateMode.run();
        });
        updateMode.run();
        // Keep the existing mode/configuration machinery, but use one
        // compact navigation row for all normal player decisions.
        openSocial = () -> {
            homeVisible[0] = true;
            toolsVisible[0] = false;
            if (socialUi != null) socialUi.home();
            updateMode.run();
        };
        modeTabs[0].setOnClickListener(view -> openLeaderboard());
        modeTabs[1].setOnClickListener(view -> {
            homeVisible[0] = toolsVisible[0] = false;
            classicLan[0] = false;
            lanMode.setChecked(true);
            updateMode.run();
        });
        modeTabs[2].setOnClickListener(view -> {
            homeVisible[0] = toolsVisible[0] = false;
            refreshDirectPresets.run();
            lanMode.setChecked(false);
            offlineMode.setChecked(botMatch.isChecked());
            updateMode.run();
        });
        modeTabs[3].setOnClickListener(view -> {
            homeVisible[0] = false;
            toolsVisible[0] = true;
            updateMode.run();
        });

        statusColumn.addView(feedback);
        feedback.setVisibility(feedback.length() == 0 ? View.GONE : View.VISIBLE);
        feedback.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                feedback.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        Button appSettings = new Button(this);
        appSettings.setText("OPEN ANDROID APP SETTINGS");
        hudButton(appSettings, false);
        appSettings.setVisibility(View.GONE);
        appSettings.setOnClickListener(next -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        statusColumn.addView(appSettings);

        Button play = new Button(this);
        playButton = play;
        play.setText("PLAY");
        hudButton(play, true);
        play.setOnClickListener(view -> {
            if (lobbyUi != null && lobbyUi.hasSession()) {
                feedback.setText("Leave the launcher lobby before using Classic Halo or Offline Match.");
                return;
            }
            if (repairBusy) {
                feedback.setText("Finish the file or save operation before starting Halo.");
                return;
            }
            if (invitePending && !lanMode.isChecked()) {
                feedback.setText("Discard the pending invitation before playing offline.");
                return;
            }
            if (lanMode.isChecked() && invitePending && lanOnly.isChecked()) {
                feedback.setText("Discard the internet invitation before enabling LAN-only mode.");
                return;
            }
            if (!canStartGame(feedback, appSettings))
                return;
            if (lanMode.isChecked() && joinRole.isChecked() &&
                lanOnly.isChecked() && !invitePending &&
                (hostPicker.isSearching() ||
                 LanUpdateProtocol.privateAddress(
                     hostIp.getText().toString().trim()) == null)) {
                // Discovery is asynchronous: do not save an empty host or
                // launch gameplay until the user chooses a real LAN host.
                hostPicker.search(true, () -> play.performClick());
                return;
            }
            if (offlineMode.isChecked()) {
                int count = offlineCount.getSelectedItemPosition() + 1;
                int skill = offlineSkill.getSelectedItemPosition();
                int team = offlineTeam.getSelectedItemPosition();
                // Both features now default on. Objective AI is constrained
                // to CTF/King by the native bot planner, so Slayer is unaffected.
                boolean crews = true;
                boolean objectives = true;
                LauncherMatch.MapChoice map = directMaps[directMap.getSelectedItemPosition()];
                MatchWorkshop.Preset preset=directPresets.get(directMode.getSelectedItemPosition());
                String mode=preset.id.isEmpty()?preset.base:preset.id;
                LauncherMatch match;
                try {
                    match = LauncherMatch.offlinePreset(dataRoot, map.id, preset,
                        count, offlineSkills[skill].toLowerCase(java.util.Locale.ROOT),
                        team == 0 ? "even" : "against", crews);
                } catch (IllegalArgumentException unavailable) {
                    feedback.setText(unavailable.getMessage());
                    return;
                }
                try {
                    // Android's native game reads config.toml, not arbitrary
                    // --offline-bots CLI flags. Apply before its :game process
                    // starts, leaving [network] and LAN count untouched.
                    OfflineBotConfig.enable(new File(dataRoot, "config.toml"), count,
                        offlineSkills[skill].toLowerCase(java.util.Locale.ROOT),
                        team == 0 ? "even" : "against", crews, objectives);
                    CommanderConfig.disable(new File(dataRoot, "config.toml"));
                    saveKillstreakChoices(new File(dataRoot, "config.toml"),
                        killstreakChecks, killstreakPrefs, true,match.radio);
                } catch (java.io.IOException error) {
                    feedback.setText("Couldn't configure offline bots: " + error.getMessage());
                    return;
                }
                offlinePreferences.edit().putInt("count", count).putInt("skill", skill)
                    .putInt("team", team).putBoolean("vehicle_seats", crews)
                    .putBoolean("objective_modes", objectives).apply();
                launcherPreferences.edit().putString("play_mode", "offline").apply();
                Intent game = new Intent(this, HaloActivity.class);
                game.putExtra(HaloActivity.OFFLINE_BOT_ARGUMENTS, match.arguments());
                offlinePreferences.edit().putString("direct_map", map.id)
                    .putString("direct_mode", mode).apply();
                startGame(game);
                return;
            }
            if (lanMode.isChecked()) {
                try {
                    // Keep offline-only count/team/objective preferences
                    // from leaking into a LAN session.
                    OfflineBotConfig.disable(new File(dataRoot, "config.toml"));
                    String peer = LanConfig.peerForRole(hostRole.isChecked(),
                        hostIp.getText().toString(), lanOnly.isChecked());
                    boolean hosting = hostRole.isChecked() && lanOnly.isChecked()
                        && !invitePending;
                    int count = hosting ?
                        lanBotCounts[lanBots.getSelectedItemPosition()] : 0;
                    String skill = hosting
                        ? LanConfig.BOT_SKILLS[lanSkill.getSelectedItemPosition()] : null;
                    LanConfig.save(new File(dataRoot, "config.toml"),
                        local.getText().toString(), peer, lanOnly.isChecked(),
                        count, skill, true, true, hosting);
                    CommanderConfig.disable(new File(dataRoot, "config.toml"));
                    saveKillstreakChoices(new File(dataRoot, "config.toml"),
                        killstreakChecks, killstreakPrefs, hosting);
                    if (invitePending)
                        publishPendingInvite();
                } catch (java.io.IOException e) {
                    feedback.setText("Couldn't apply game settings: " + e.getMessage());
                    return;
                }
                SharedPreferences.Editor roleState = launcherPreferences.edit()
                    .putString("play_mode", "lan")
                    .putString("lan_role", hostRole.isChecked() ? "host" : "join");
                if (!hostRole.isChecked())
                    roleState.putString("last_host", hostIp.getText().toString().trim());
                roleState.apply();
            } else {
                // Campaign selected: launch ordinary Halo with no synthetic
                // offline bots, while preserving network setup and saves.
                try {
                    OfflineBotConfig.disable(new File(dataRoot, "config.toml"));
                    CommanderConfig.disable(new File(dataRoot, "config.toml"));
                    KillstreakConfig.disable(new File(dataRoot, "config.toml"));
                } catch (java.io.IOException error) {
                    feedback.setText("Couldn't configure normal Halo: " + error.getMessage());
                    return;
                }
                launcherPreferences.edit().putString("play_mode", "standard").apply();
            }
            if (lanMode.isChecked() && lanOnly.isChecked() &&
                    !hostRole.isChecked() && !invitePending) {
                // Before starting gameplay, avoid Halo's network-version
                // mismatch by checking the *already entered* host IP.
                play.setEnabled(false);
                feedback.setText("Checking the Halo host for a LAN update...");
                LanUpdateClient.check(this, hostIp.getText().toString().trim(),
                    false, () -> startGame(), () -> play.setEnabled(true));
                return;
            }
            if (lanMode.isChecked() && hostRole.isChecked() &&
                    lanOnly.isChecked() && !invitePending) {
                launcherPreferences.edit().putBoolean("lan_update_share",
                    shareUpdates.isChecked()).apply();
                Intent game = new Intent(this, HaloActivity.class);
                game.putExtra(HaloActivity.LAN_DISCOVERY_HOST, true);
                if (shareUpdates.isChecked())
                    game.putExtra(HaloActivity.LAN_UPDATE_HOST, true);
                startGame(game);
                return;
            }
            startGame();
        });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(Math.max(44, setupRowHeight() + 4)));
        footer.addView(play, playParams);
        actionDock.setActions(footer, statusScroll, statusColumn);
        setContentView(stage);
        if (launcherResumed && titleBackdrop != null) titleBackdrop.resumeTitle();
        updateMode.run(); // hide PLAY on the default leaderboard screen
        if (launcherResumed && !titleBackdropAvailable)
            handler.post(this::maybePrepareOriginalTitle);
        // Reentering Join after an in-place APK update should find a host
        // still playing without asking the host to leave the match.
        if (classicLan[0] && lanMode.isChecked() && joinRole.isChecked() &&
            lanOnly.isChecked() && !invitePending)
            hostPicker.search(false, null);
    }

    /** Individual host-authoritative rewards; retired orbital stays OFF. */
    private void saveKillstreakChoices(File file, CheckBox[] checks,
            SharedPreferences preferences, boolean hosting)
            throws java.io.IOException {
        saveKillstreakChoices(file,checks,preferences,hosting,null);
    }
    private void saveKillstreakChoices(File file, CheckBox[] checks,
            SharedPreferences preferences, boolean hosting,Boolean radioOverride)
            throws java.io.IOException {
        if (!hosting) {
            KillstreakConfig.disable(file);
            return;
        }
        KillstreakConfig.Options selected = new KillstreakConfig.Options();
        SharedPreferences.Editor saved = preferences.edit();
        for (int i = 0; i < KillstreakConfig.KEYS.length; i++) {
            String key = KillstreakConfig.KEYS[i];
            boolean checked = !key.equals("orbital") && checks[i].isChecked();
            selected.set(key, checked);
            saved.putBoolean(key, checked);
        }
        if(radioOverride!=null)selected.set("announcements",radioOverride);
        KillstreakConfig.save(file, selected);
        saved.apply();
    }

    /** Saves are accessed only after :game is stopped. This does not touch
     * the original maps, mods or configuration and uses no storage grants
     * except the system document picker chosen by the player. */
    private interface CampaignSaveOperation {
        String apply() throws Exception;
    }

    private void runCampaignSaveOperation(String initial, CampaignSaveOperation operation) {
        if (repairBusy)
            return;
        if (gameProcessStatus() != 0) {
            campaignSaveStatus.setText(
                "Close or force-stop the Halo game before working with saves.");
            return;
        }
        setMapToolsBusy(true);
        campaignSaveStatus.setText(initial);
        new Thread(() -> {
            String result;
            try {
                if (gameProcessStatus() != 0)
                    throw new java.io.IOException(
                        "The Halo game started. No save files were changed.");
                result = operation.apply();
            } catch (Exception error) {
                result = "Campaign backup/restore failed: " +
                    (error.getMessage() == null ?
                     error.getClass().getSimpleName() : error.getMessage());
            }
            final String message = result;
            handler.post(() -> {
                setMapToolsBusy(false);
                campaignSaveStatus.setText(message);
            });
        }, "Halo-Campaign-Backup").start();
    }

    private void exportCampaignSave(Uri target) {
        runCampaignSaveOperation("Exporting campaign profiles and checkpoints...", () -> {
            try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                if (output == null)
                    throw new java.io.IOException("Android couldn't create the backup ZIP.");
                CampaignSaveArchive.Result result =
                    CampaignSaveArchive.exportTo(dataRoot, output);
                return "Campaign backup saved (" + result.files +
                    " files, " + result.bytes / 1024 + " KiB). Keep this ZIP outside " +
                    "Halo's Android/data folder. Updating in place preserves progress.";
            }
        });
    }

    private void restoreCampaignSave(Uri source) {
        runCampaignSaveOperation("Validating and restoring campaign progress...", () -> {
            try (InputStream input = getContentResolver().openInputStream(source)) {
                if (input == null)
                    throw new java.io.IOException("Couldn't read the campaign backup ZIP.");
                CampaignSaveArchive.Result result =
                    CampaignSaveArchive.restoreFrom(dataRoot, input);
                return "Restored " + result.files + " campaign save files. " +
                    (result.previousSave == null ? "There were no previous saves." :
                    "Your previous save folder was preserved for rollback. ") +
                    "Reopen Halo to continue the campaign.";
            }
        });
    }

    private interface RepairOperation {
        String apply() throws Exception;
    }

    private void setMapToolsBusy(boolean busy) {
        repairBusy = busy;
        Button[] actions = { playButton, stockCampaignAuditButton, repairHostButton,
            shareMapsButton, receiveMapsButton, customMapImportButton,
            campaignBackupButton, campaignRestoreButton, stockCampaignImportButton,
            stockCampaignExportButton, customMapFolderButton, customMapZipButton };
        for (Button action : actions) if (action != null) action.setEnabled(!busy);
    }

    private void runMapRepair(String initialStatus, RepairOperation operation) {
        if (repairBusy)
            return;
        if (gameProcessStatus() != 0) {
            if (repairStatus != null)
                repairStatus.setText("Close or force-stop Halo before repairing maps.");
            if (customMapStatus != null)
                customMapStatus.setText("Close or force-stop Halo before importing maps.");
            return;
        }
        setMapToolsBusy(true);
        if (repairStatus != null) repairStatus.setText(initialStatus);
        if (customMapStatus != null && customMapStatus != repairStatus) customMapStatus.setText(initialStatus);

        new Thread(() -> {
            String result;
            try {
                if (gameProcessStatus() != 0)
                    throw new java.io.IOException("Halo started while the repair was being prepared.");
                result = operation.apply();
            } catch (Exception error) {
                result = "✕ FAIL  " + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            }
            final String message = result;
            handler.post(() -> {
                setMapToolsBusy(false);
                if (mapAuditProgress != null) mapAuditProgress.setVisibility(View.GONE);
                if (repairStatus != null) repairStatus.setText(readableMapStatus(message));
                if (customMapStatus != null && customMapStatus != repairStatus) customMapStatus.setText(message);
            });
        }, "Halo-Xbox-Map-Transfer").start();
    }

    private String selectedStockCampaignMap() {
        int selected = stockCampaignMapChoice == null ? 3 :
            stockCampaignMapChoice.getSelectedItemPosition();
        String[] names = SidewinderRepair.CAMPAIGN_MAP_FILES;
        return selected == names.length ?
            SidewinderRepair.SIDEWINDER_MAP :
            names[Math.max(0, Math.min(selected, names.length - 1))];
    }

    private void pickStockMapRepairs() {
        if (repairBusy) return;
        if (gameProcessStatus() != 0) {
            repairStatus.setText("Close Halo before repairing maps.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(picker, PICK_STOCK_MAP_IMPORT);
    }

    private String refreshStockMapChecks() {
        java.util.List<CampaignMapTransfer.Check> checks = CampaignMapTransfer.checks(dataRoot,
            (name, index, total) -> handler.post(() -> {
                if (repairStatus != null) repairStatus.setText("Checking " + SidewinderRepair.titleFor(name) +
                    " (" + index + "/" + total + ")...");
                if (mapAuditProgress != null) {
                    mapAuditProgress.setMax(total);
                    mapAuditProgress.setProgress(index - 1);
                    mapAuditProgress.setVisibility(View.VISIBLE);
                }
            }));
        int passed = 0;
        for (CampaignMapTransfer.Check check : checks) if (check.passed) passed++;
        handler.post(() -> showStockMapChecks(checks));
        return (passed == checks.size() ? "✓ PASS  " : "✕ FAIL  ") + passed + "/" + checks.size()
            + " maps passed. " + (passed == checks.size() ? "All map files passed." :
            (checks.size() - passed) + " need repair. Use Repair From Host or Import Maps.");
    }

    private void showStockMapChecks(java.util.List<CampaignMapTransfer.Check> checks) {
        if (stockMapResults == null) return;
        stockMapResults.removeAllViews();
        for (CampaignMapTransfer.Check check : checks) {
            if (check.passed) continue;
            TextView failed = lanText("✕ " + check.title + " (" + check.filename + ") — " + check.detail, 12);
            failed.setTextColor(HUD_WARNING);
            stockMapResults.addView(failed);
        }
    }

    private static CharSequence readableMapStatus(String message) {
        android.text.SpannableString styled = new android.text.SpannableString(message);
        int start = 0;
        for (String line : message.split("\n", -1)) {
            if (line.startsWith("✓ PASS") || line.startsWith("✕ FAIL"))
                styled.setSpan(new android.text.style.ForegroundColorSpan(line.startsWith("✓")
                    ? Color.rgb(87, 230, 155) : Color.rgb(255, 107, 107)), start, start + line.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start += line.length() + 1;
        }
        return styled;
    }

    private void auditStockCampaignMaps() {
        if (repairBusy) return;
        if (stockMapResults != null) stockMapResults.removeAllViews();
        runMapRepair("Checking original maps...", this::refreshStockMapChecks);
    }

    /** A user-selected SAF destination receives verified, streaming map
     * copies without duplicating gigabytes inside the app's runtime disk. */
    private void exportAdminCampaignLibrary(Uri destination) {
        runMapRepair("Preparing your verified campaign-map archive...", () -> {
            try (OutputStream output = getContentResolver().openOutputStream(
                    destination, "w")) {
                if (output == null)
                    throw new java.io.IOException(
                        "Android couldn't create the selected archive.");
                CampaignAdminArchive.Report report = CampaignAdminArchive.export(
                    dataRoot, output, (name, index, count) ->
                        handler.post(() -> {
                            if (repairStatus != null)
                                repairStatus.setText("Campaign archive " + index +
                                    "/" + count + ": " + name);
                        }));
                return "Admin archive written: " + report.valid +
                    "/10 verified maps, " + report.invalid + " invalid, " +
                    report.missing + " missing; " +
                    (report.bytes / 1048576) + " MiB copied. Review " +
                    "CAMPAIGN-MAP-INVENTORY.txt inside the ZIP. " +
                    "A full library requires 10/10 valid Xbox maps. " +
                    "Share this ZIP privately. The other phone chooses " +
                    "IMPORT MAPS OR REPAIR ZIP; no extraction or level selection is needed.";
            }
        });
    }

    private void importStockMapRepairs(Intent result) {
        ArrayList<Uri> documents = new ArrayList<>();
        LinkedHashSet<Uri> seen = new LinkedHashSet<>();
        ClipData clips = result.getClipData();
        if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) {
            Uri uri = clips.getItemAt(i).getUri();
            if (uri != null && seen.add(uri)) documents.add(uri);
        }
        if (result.getData() != null && seen.add(result.getData())) documents.add(result.getData());
        if (documents.isEmpty() || documents.size() > 11) {
            repairStatus.setText("Choose 1–11 map files, or one repair ZIP.");
            return;
        }
        runMapRepair("Detecting and checking your replacement maps...", () -> {
            StringBuilder report = new StringBuilder();
            for (Uri document : documents) {
                try (InputStream input = getContentResolver().openInputStream(document)) {
                    report.append(CampaignRepairImport.install(input, dataRoot,
                        message -> handler.post(() -> repairStatus.setText(message)))).append('\n');
                } catch (java.io.IOException failure) {
                    report.append("✕ FAIL  ").append(failure.getMessage()).append('\n');
                }
            }
            report.append('\n').append(refreshStockMapChecks());
            return report.toString();
        });
    }

    private void exportSelectedStockCampaignMap(Uri document, String filename) {
        runMapRepair("Exporting verified " + filename + " to your chosen document...", () -> {
            try (OutputStream out = getContentResolver().openOutputStream(document, "w")) {
                if (out == null)
                    throw new java.io.IOException("Android could not create the map export.");
                CampaignMapTransfer.Export result =
                    CampaignMapTransfer.export(dataRoot, filename, out);
                return "Exported " + result.filename + " (" +
                    result.bytes / 1048576 + " MiB, SHA-256 " +
                    result.sha256.substring(0, 12) + "...). Send this file " +
                    "privately to the other phone and choose " +
                    "IMPORT MAPS OR REPAIR ZIP. Halo detects the level automatically.";
            }
        });
    }

    /** Check only app-owned files; scoped-storage Android keeps them
     * inaccessible to Termux. Beach Assault's Halo CE cache actually
     * references all three standard external resource maps. The user must
     * import their own legitimate CE resources if any are absent. */
    private String beachAssaultResourceStatus() {
        return CeResourceAudit.beachReport(dataRoot);
    }

    /** The display name is only used after it passes CustomMapImporter's
     * strict filename grammar. Do not use a document URI's path as a name. */
    private String customMapDisplayName(Uri document) throws java.io.IOException {
        try (Cursor cursor = getContentResolver().query(document,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0))
                throw new java.io.IOException("Android did not provide a map filename.");
            return cursor.getString(0);
        } catch (RuntimeException exception) {
            throw new java.io.IOException("Could not read the selected map name.", exception);
        }
    }

    private void importCustomMaps(Intent result) {
        ArrayList<Uri> documents = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ClipData clips = result.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri document = clips.getItemAt(i).getUri();
                if (document != null && seen.add(document.toString()))
                    documents.add(document);
            }
        }
        Uri single = result.getData();
        if (single != null && seen.add(single.toString()))
            documents.add(single);
        if (documents.isEmpty() || documents.size() > 32) {
            if (customMapStatus != null)
                customMapStatus.setText("Choose 1 to 32 map files, BMP previews or ZIP archives.");
            return;
        }

        runMapRepair("Importing " + documents.size() + " selected files...", () -> {
            StringBuilder report = new StringBuilder();
            for (Uri document : documents) {
                String name = customMapDisplayName(document);
                handler.post(() -> repairStatus.setText("Checking " + name + "..."));
                try (InputStream input = getContentResolver().openInputStream(document)) {
                    report.append(UnifiedMapImport.install(input, dataRoot, name,
                        progress -> handler.post(() -> repairStatus.setText(progress)))).append('\n');
                } catch (java.io.IOException invalid) {
                    report.append("✕ FAIL  ").append(name).append(": ").append(invalid.getMessage()).append('\n');
                }
            }
            return report.toString().trim();
        });
    }

    private static final class MapDocument {
        final Uri uri;
        final String filename;

        MapDocument(Uri uri, String filename) {
            this.uri = uri;
            this.filename = filename;
        }
    }

    private void importCustomFolder(Uri tree) {
        runMapRepair("Checking selected Custom Edition folder...", () -> {
            String root = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root);
            ArrayList<MapDocument> selected = new ArrayList<>();
            HashSet<String> names = new HashSet<>();
            String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = getContentResolver().query(
                    children, columns, null, null, null)) {
                if (cursor == null)
                    throw new java.io.IOException("Android could not list the chosen folder.");
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (name == null || documentId == null ||
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ||
                        (!name.toLowerCase(Locale.ROOT).endsWith(".map") &&
                         !name.toLowerCase(Locale.ROOT).endsWith(".bmp")))
                        continue;
                    if (!CustomMapImporter.isImportName(name))
                        throw new java.io.IOException("Unsafe map name in the folder: " + name);
                    if (selected.size() >= 32)
                        throw new java.io.IOException("Select a folder with at most 32 map/preview files.");
                    if (!names.add(name.toLowerCase(Locale.ROOT)))
                        throw new java.io.IOException("The folder contains duplicate map names.");
                    selected.add(new MapDocument(
                        DocumentsContract.buildDocumentUriUsingTree(tree, documentId), name));
                }
            } catch (RuntimeException error) {
                throw new java.io.IOException(
                    "Android could not read this folder. Try a ZIP map pack instead.", error);
            }
            if (selected.isEmpty())
                throw new java.io.IOException("No .map or .bmp files were exposed by Android. "
                    + "Try importing a ZIP map pack instead.");

            // Sorting ensures the three shared resource maps install first,
            // followed by individual maps. Sorting doesn't change filenames.
            selected.sort((a, b) -> {
                boolean resourceA = a.filename.equals("bitmaps.map") ||
                    a.filename.equals("sounds.map") || a.filename.equals("loc.map");
                boolean resourceB = b.filename.equals("bitmaps.map") ||
                    b.filename.equals("sounds.map") || b.filename.equals("loc.map");
                if (resourceA != resourceB)
                    return resourceA ? -1 : 1;
                return a.filename.compareToIgnoreCase(b.filename);
            });

            int count = 0;
            long bytes = 0;
            for (MapDocument document : selected) {
                final int number = count + 1;
                handler.post(() -> {
                    if (customMapStatus != null)
                        customMapStatus.setText("Importing " + document.filename + " ("
                            + number + "/" + selected.size() + ")...");
                });
                try (InputStream input = getContentResolver().openInputStream(document.uri)) {
                    if (input == null)
                        throw new java.io.IOException(
                            "Android could not open " + document.filename);
                    CustomMapImporter.Result result = CustomMapImporter.install(
                        input, dataRoot, document.filename);
                    bytes += result.size;
                    count++;
                } catch (java.io.IOException error) {
                    throw new java.io.IOException(count + " map(s) installed, then "
                        + document.filename + " failed: " + error.getMessage(), error);
                }
            }
            return count + " Custom Edition maps/resources/previews imported ("
                + (bytes / 1048576) + " MiB). Open CUSTOM MULTIPLAYER.";
        });
    }

    private void importCustomZip(Uri document) {
        runMapRepair("Checking selected ZIP map pack...", () -> {
            try (InputStream input = getContentResolver().openInputStream(document)) {
                if (input == null)
                    throw new java.io.IOException("Android could not open the ZIP.");
                CustomMapPack.Result result = CustomMapPack.install(
                    input, dataRoot, (name, index) -> handler.post(() -> {
                        if (customMapStatus != null)
                            customMapStatus.setText("Installing ZIP map " + index
                                + ": " + name);
                    }));
                return result.files + " ZIP map(s)/resources imported ("
                    + (result.bytes / 1048576) + " MiB). Open CUSTOM MULTIPLAYER.";
            }
        });
    }

    private void saveDiagnostics(Uri destination) {
        new Thread(() -> {
            String result;
            try (OutputStream output =
                    getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null)
                    throw new java.io.IOException("Android could not write the report.");
                HaloDiagnostics.write(dataRoot, output);
                result = "Diagnostic report saved. Review it before sharing.";
            } catch (Exception error) {
                result = "Couldn't save report: " + error.getMessage();
            }
            String message = result;
            handler.post(() -> {
                if (diagnosticStatus != null)
                    diagnosticStatus.setText(message);
            });
        }, "Halo-Diagnostic-Export").start();
    }

    private void buildInterface() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(36), dp(20), dp(36), dp(20));
        layout.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { HaloTheme.BACKGROUND_TOP, HaloTheme.BACKGROUND_CENTER,
                HaloTheme.BACKGROUND_BOTTOM }));

        TextView title = new TextView(this);
        title.setText("HALO  /  GAME DATA");
        title.setTextColor(HUD_TEXT);
        HaloTheme.font(title, true);
        title.setLetterSpacing(0.12f);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView message = new TextView(this);
        message.setText("Select your Halo: Combat Evolved Xbox disc image "
            + "to install the maps. The disc image will not be modified.\n"
            + "Approximately 1.8 GB of free space is needed.");
        message.setTextColor(HUD_MUTED);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(16), 0, dp(16));
        layout.addView(message);

        pick = new Button(this);
        pick.setText("IMPORT DISC DATA");
        hudButton(pick, true);
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
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        progressLayout.topMargin = dp(16);
        layout.addView(progress, progressLayout);

        status = new TextView(this);
        status.setTextColor(HUD_ACCENT);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        layout.addView(status);

        setContentView(layout);
        pick.requestFocus();
    }

    void openLeaderboard() {
        if (openSocial != null) openSocial.run();
    }

    void openMyVideos() {
        openLeaderboard();
        if (socialUi != null) socialUi.myVideos();
    }

    @Override
    public void onBackPressed() {
        if (socialUi != null && socialUi.back()) return;
        super.onBackPressed();
    }

    java.util.List<String> liveBoardTargets() {
        return livePeerMonitor == null ? java.util.Collections.emptyList() :
            livePeerMonitor.liveTargets();
    }

    LanBoardPresence.Snapshot liveBoardSnapshot() {
        return livePeerMonitor == null ? LanBoardPresence.Snapshot.empty() : livePeerMonitor.snapshot();
    }

    void observeLiveBoard(Runnable listener) {
        if (livePeerMonitor != null) livePeerMonitor.observe(listener);
    }

    void removeLiveBoardObserver(Runnable listener) {
        if (livePeerMonitor != null) livePeerMonitor.removeObserver(listener);
    }

    boolean launcherVisible() { return launcherResumed && !isFinishing() && !isDestroyed(); }
    void setRemoteReelPlayer(LanReelPlayer player) {
        if (remoteReelPlayer != null && remoteReelPlayer != player) remoteReelPlayer.close();
        remoteReelPlayer = player;
    }
    void remoteReelClosed(LanReelPlayer player) {
        if (remoteReelPlayer == player) remoteReelPlayer = null;
    }

    @Override
    protected void onPause() {
        launcherResumed = false;
        if (titleBackdrop != null) titleBackdrop.pauseTitle();
        if (remoteReelPlayer != null) remoteReelPlayer.close();
        if (livePeerMonitor != null) livePeerMonitor.pause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (lobbyUi != null) lobbyUi.suspend();
        stopBoardSharing();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (lobbyUi != null) { lobbyUi.close(); lobbyUi = null; }
        if (titleBackdrop != null) titleBackdrop.pauseTitle();
        if (remoteReelPlayer != null) remoteReelPlayer.close();
        if (socialUi != null) socialUi.close();
        if (livePeerMonitor != null) livePeerMonitor.close();
        stopBoardSharing();
        if (lanMapRepair != null) lanMapRepair.destroy();
        super.onDestroy();
    }

    private void resumeLobbyAfterGame() {
        if (!launcherResumed || !lobbyGameStarted || lobbyUi == null) return;
        if (gameProcessStatus() == 0) {
            lobbyGameStarted = false;
            lobbyUi.returned();
        } else {
            getWindow().getDecorView().postDelayed(this::resumeLobbyAfterGame, 750);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        launcherResumed = true;
        if (lobbyGameStarted && lobbyUi != null) {
            // Recording consent may briefly resume this Activity before :game has focus.
            // A game that is still running keeps ownership until it exits.
            resumeLobbyAfterGame();
        }
        // A stopped host's durable pending record becomes a forfeit,
        // not a vanished match. Never recover while the game is running.
        try {
            if (gameProcessStatus() == 0 &&
                    new File(getFilesDir(), GroupRatingLedger.PENDING).isFile())
                GroupRatingLedger.recoverAbandonedHost(getFilesDir(), LanBoardIdentity.id(this), true);
        } catch (java.io.IOException | RuntimeException unavailable) {
            android.util.Log.w("HaloLanBoard", "Pending ranked match retained for recovery", unavailable);
        }
        if (livePeerMonitor != null) livePeerMonitor.resume();
        refreshBoardSharing();
        // The native game may finish a match after the recorder reached its
        // time cap. Reconcile locally saved raw MP4 with durable HLM1 results
        // on return, without blocking the launcher or writing to a server.
        if (dataRoot != null)
            new Thread(() -> ReelLibrary.assemble(dataRoot),
                "Halo-local-reel-recovery").start();
        // data pushed with adb while this screen was open
        if (pick != null && pick.isEnabled() && haveData()) {
            pick = null;
            buildLanInterface();
        } else if (dashboardPanel != null &&
                titleBackdropAvailable != TitleBackdropFiles.ready(getFilesDir())) {
            buildLanInterface();
        } else if (dashboardPanel != null && dashboardPanel.getVisibility() == View.VISIBLE) {
            if (socialUi != null) socialUi.reload();
        }
        if (titleBackdrop != null) titleBackdrop.resumeTitle();
        showTitleResult(getIntent());
        updateTitleStatus();
        maybePrepareOriginalTitle();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra(HaloActivity.TITLE_RESULT)) {
            setIntent(intent);
            if (dashboardPanel != null &&
                    titleBackdropAvailable != TitleBackdropFiles.ready(getFilesDir()))
                buildLanInterface();
            showTitleResult(intent);
        } else {
            setIntent(intent);
            passOnInvite(intent);
        }
    }

    private void updateTitleStatus() {
        String status = TitleBackdropFiles.readStatus(getFilesDir());
        boolean failure = !status.isEmpty() && !status.equals("Ready") &&
            (!status.startsWith("Preparing:") ||
                (!titleBackdropAvailable && gameProcessStatus() == 0));
        String display = failure && status.startsWith("Preparing:") ?
            "Title preparation did not finish: " + status.substring("Preparing:".length()).trim() : status;
        if (titleStatusView != null) {
            titleStatusView.setText(failure ? display : "");
            titleStatusView.setVisibility(failure ? View.VISIBLE : View.GONE);
        }
        if (failure && launcherResumed && !display.equals(lastTitleNotice)) {
            lastTitleNotice = display;
            HaloNotice.makeText(this, display, HaloNotice.LENGTH_LONG).show();
        }
    }

    private void showTitleResult(Intent intent) {
        if (intent == null) return;
        String message = intent.getStringExtra(HaloActivity.TITLE_RESULT);
        if (message == null) return;
        intent.removeExtra(HaloActivity.TITLE_RESULT);
        updateTitleStatus();
        if (TitleBackdropFiles.ready(getFilesDir()))
            HaloNotice.makeText(this, message, HaloNotice.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_REEL_CONSENT) {
            Intent game = pendingVideoLaunch;
            pendingVideoLaunch = null;
            if (game == null) return;
            if (resultCode == RESULT_OK && data != null) {
                String videoToken = java.util.UUID.randomUUID().toString().replace("-", "");
                game.putExtra(MatchVideoState.TOKEN, videoToken);
                Intent recording = new Intent(this, HaloVideoCaptureService.class)
                    .putExtra(MatchVideoState.TOKEN, videoToken)
                    .setAction(HaloVideoCaptureService.ACTION_START)
                    .putExtra(HaloVideoCaptureService.EXTRA_CONSENT_CODE, resultCode)
                    .putExtra(HaloVideoCaptureService.EXTRA_CONSENT_DATA, data);
                try {
                    startForegroundService(recording);
                } catch (RuntimeException unavailable) {
                    game.removeExtra(MatchVideoState.TOKEN);
                    HaloNotice.makeText(this,
                        "Recording unavailable. Halo will still launch.",
                        HaloNotice.LENGTH_LONG).show();
                }
            } else {
                HaloNotice.makeText(this,
                    "Recording skipped; Halo starts normally.",
                    HaloNotice.LENGTH_SHORT).show();
            }
            launchWithoutConsent(game);
            return;
        }
        if (requestCode == PICK_REEL_EXPORT) {
            String name = pendingVideoExport;
            pendingVideoExport = null;
            if (resultCode == RESULT_OK && name != null && data != null &&
                data.getData() != null)
                exportReelToDocument(data.getData(), name);
            return;
        }
        if (requestCode == PICK_STOCK_MAP_IMPORT) {
            if (resultCode == RESULT_OK && data != null) importStockMapRepairs(data);
            return;
        }
        if (requestCode == PICK_STOCK_MAP_EXPORT) {
            String filename = pendingStockMapTransfer;
            pendingStockMapTransfer = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && filename != null)
                exportSelectedStockCampaignMap(data.getData(), filename);
            return;
        }
        if (requestCode == PICK_ADMIN_CAMPAIGN_ARCHIVE) {
            if (resultCode == RESULT_OK && data != null &&
                data.getData() != null)
                exportAdminCampaignLibrary(data.getData());
            return;
        }
        if (requestCode == PICK_CUSTOM_MAPS) {
            if (resultCode == RESULT_OK && data != null)
                importCustomMaps(data);
            return;
        }
        if (requestCode == PICK_CUSTOM_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                importCustomFolder(data.getData());
            return;
        }
        if (requestCode == PICK_CUSTOM_ZIP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                importCustomZip(data.getData());
            return;
        }
        if (requestCode == SAVE_DIAGNOSTICS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                saveDiagnostics(data.getData());
            return;
        }
        if (requestCode == PICK_CAMPAIGN_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                exportCampaignSave(data.getData());
            return;
        }
        if (requestCode == PICK_CAMPAIGN_RESTORE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri document = data.getData();
                new HaloDialog.Builder(this)
                    .setTitle("Restore campaign progress?")
                    .setMessage("This backup will replace the current Halo "
                        + "campaign profiles and checkpoints. The existing "
                        + "save directory will be preserved separately on "
                        + "this phone in case you need to roll back. "
                        + "Halo must be closed before restoring.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("RESTORE", (dialog, which) ->
                        restoreCampaignSave(document))
                    .show();
            }
            return;
        }
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
