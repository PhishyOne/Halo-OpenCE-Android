package com.halo.decomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
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
    // Halo-style HUD colours. Native Android widgets remain accessible,
    // including the system file picker and LAN diagnostics.
    private static final int HUD_TEXT = Color.rgb(226, 237, 231);
    private static final int HUD_MUTED = Color.rgb(164, 185, 185);
    private static final int HUD_ACCENT = Color.rgb(172, 205, 157);
    private static final int HUD_BORDER = Color.rgb(74, 104, 119);
    private static final int HUD_WARNING = Color.rgb(255, 186, 128);


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
        startActivity(intent);
        finish();
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
        panel.setBackground(hudSurface(Color.rgb(23, 36, 47),
            Color.rgb(10, 19, 29), HUD_BORDER));
        return panel;
    }

    private void hudButton(Button button, boolean primary) {
        button.setTextColor(primary ? Color.rgb(17, 30, 32) : HUD_TEXT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 19 : 13);
        button.setAllCaps(true);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(primary ? 48 : 38));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(primary
            ? hudSurface(Color.rgb(190, 214, 180), Color.rgb(118, 159, 135),
                Color.rgb(218, 235, 210))
            : hudSurface(Color.rgb(43, 63, 77), Color.rgb(22, 39, 51),
                HUD_BORDER));
    }

    private void hudCheck(CheckBox check) {
        check.setTextColor(HUD_TEXT);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        check.setButtonTintList(ColorStateList.valueOf(HUD_ACCENT));
        check.setGravity(Gravity.CENTER_VERTICAL);
        check.setPadding(dp(6), dp(3), dp(6), dp(3));
    }

    private void hudInput(EditText input) {
        input.setTextColor(HUD_TEXT);
        input.setHintTextColor(HUD_MUTED);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setPadding(dp(10), dp(7), dp(10), dp(7));
        input.setBackground(hudSurface(Color.rgb(13, 24, 32),
            Color.rgb(14, 25, 33), HUD_BORDER));
    }

    private TextView lanText(String message, int size) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(HUD_TEXT);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setPadding(0, dp(2), 0, dp(5));
        return text;
    }

    private TextView hudSection(String message) {
        TextView label = lanText(message, 13);
        label.setTextColor(HUD_ACCENT);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLetterSpacing(0.16f);
        return label;
    }

    private Spinner botChoice(LinearLayout layout, String label, String[] choices, int selection) {
        TextView title = lanText(label, 13);
        title.setTextColor(HUD_MUTED);
        layout.addView(title);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, choices) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(HUD_TEXT);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                view.setPadding(dp(10), dp(6), dp(10), dp(6));
                return view;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(HUD_TEXT);
                view.setBackgroundColor(Color.rgb(25, 41, 53));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selection, choices.length - 1)));
        // Preserve Android's native drop-down indicator instead of covering
        // it with a custom background; the list still uses the HUD palette.
        spinner.setBackgroundTintList(ColorStateList.valueOf(HUD_BORDER));
        layout.addView(spinner);
        return spinner;
    }

    /** Game-styled launcher: one Play action and two mutually exclusive mode
     * checkboxes. Both off opens Halo normally. The selected mode alone
     * reveals its options; LAN and offline bot settings remain independent. */
    private void buildLanInterface() {
        LanConfig.Settings settings = new LanConfig.Settings();
        String warning = "";
        try {
            settings = LanConfig.load(new File(dataRoot, "config.toml"));
        } catch (java.io.IOException e) {
            warning = "Couldn't read the current settings: " + e.getMessage();
        }

        final boolean invitePending = hasInternetInvite();
        java.util.List<String> ips = lanIPv4Addresses();
        String suggestedAddress = ips.size() == 1 ? ips.get(0) : "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { Color.rgb(10, 18, 27), Color.rgb(20, 34, 45),
                Color.rgb(7, 13, 20) }));

        LinearLayout masthead = new LinearLayout(this);
        masthead.setOrientation(LinearLayout.HORIZONTAL);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        masthead.setPadding(dp(18), dp(5), dp(18), dp(3));
        TextView halo = lanText("HALO", 28);
        halo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        halo.setLetterSpacing(0.18f);
        halo.setTextColor(HUD_TEXT);
        masthead.addView(halo);
        TextView subtitle = lanText("COMBAT EVOLVED  /  GAME SETUP", 11);
        subtitle.setTextColor(HUD_MUTED);
        subtitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams subtitleLayout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLayout.leftMargin = dp(14);
        masthead.addView(subtitle, subtitleLayout);
        root.addView(masthead);
        View accent = new View(this);
        accent.setBackgroundColor(HUD_ACCENT);
        root.addView(accent, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // System Link panel. Host, guest and advanced controls are retained.
        LinearLayout lanPanel = hudPanel();
        content.addView(lanPanel);
        lanPanel.addView(hudSection("SYSTEM LINK"));
        lanPanel.addView(lanText("LOCAL IP  " + (ips.isEmpty()
            ? "Not detected"
            : android.text.TextUtils.join("   /   ", ips)), 12));

        RadioGroup roles = new RadioGroup(this);
        roles.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton hostRole = new RadioButton(this);
        hostRole.setId(View.generateViewId());
        hostRole.setText("HOST");
        hostRole.setTextColor(HUD_TEXT);
        hostRole.setButtonTintList(ColorStateList.valueOf(HUD_ACCENT));
        RadioButton joinRole = new RadioButton(this);
        joinRole.setId(View.generateViewId());
        joinRole.setText("JOIN");
        joinRole.setTextColor(HUD_TEXT);
        joinRole.setButtonTintList(ColorStateList.valueOf(HUD_ACCENT));
        roles.addView(hostRole);
        roles.addView(joinRole);
        roles.check(settings.peer.isEmpty() ? hostRole.getId() : joinRole.getId());
        lanPanel.addView(roles);

        TextView feedback = lanText(inviteError == null
            ? warning : warning + "  " + inviteError, 12);
        feedback.setTextColor(HUD_WARNING);

        LinearLayout hostPanel = new LinearLayout(this);
        hostPanel.setOrientation(LinearLayout.VERTICAL);
        hostPanel.addView(lanText("Give your address to players joining this match.", 12));
        hostPanel.addView(lanText("HOST ADDRESS", 12));
        EditText shareIp = new EditText(this);
        shareIp.setSingleLine(true);
        shareIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        shareIp.setHint("Your Wi-Fi / hotspot IP");
        shareIp.setText(suggestedAddress);
        shareIp.setSelectAllOnFocus(true);
        hudInput(shareIp);
        hostPanel.addView(shareIp);
        if (ips.size() > 1)
            hostPanel.addView(lanText("Confirm the active Wi-Fi address before sharing.", 12));

        LinearLayout shareActions = new LinearLayout(this);
        shareActions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("COPY IP");
        hudButton(copy, false);
        copy.setOnClickListener(view -> {
            String value = shareIp.getText().toString().trim();
            if (!LanConfig.validAddress(value) || value.isEmpty()) {
                feedback.setText("Enter a valid host address to copy.");
                return;
            }
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                feedback.setText("Android clipboard is unavailable.");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Halo host IP", value));
            feedback.setText("Copied " + value);
        });
        shareActions.addView(copy, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        Button share = new Button(this);
        share.setText("SHARE INVITE");
        hudButton(share, false);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        shareParams.leftMargin = dp(8);
        shareActions.addView(share, shareParams);
        share.setOnClickListener(view -> {
            String value = shareIp.getText().toString().trim();
            if (!LanConfig.validAddress(value) || value.isEmpty()) {
                feedback.setText("Enter a valid host address to share.");
                return;
            }
            Intent message = new Intent(Intent.ACTION_SEND);
            message.setType("text/plain");
            message.putExtra(Intent.EXTRA_TEXT, "Join my Halo LAN game. Connect to the "
                + "same Wi-Fi/hotspot, choose LAN > Join, and enter: " + value);
            startActivity(Intent.createChooser(message, "Share Halo host IP"));
        });
        hostPanel.addView(shareActions);

        String[] lanBotChoices = new String[LanConfig.MAX_LAN_BOTS + 1];
        lanBotChoices[0] = "None";
        for (int index = 1; index < lanBotChoices.length; index++)
            lanBotChoices[index] = Integer.toString(index);
        Spinner lanBots = botChoice(hostPanel, "BOT COUNT",
            lanBotChoices, settings.systemLinkBotCount);
        String[] lanSkillChoices = { "Recruit", "Easy", "Normal", "Heroic", "Veteran", "Legendary" };
        int lanSkillSelection = 2;
        for (int index = 0; index < LanConfig.BOT_SKILLS.length; index++) {
            if (LanConfig.BOT_SKILLS[index].equals(settings.botSkill)) {
                lanSkillSelection = index;
                break;
            }
        }
        Spinner lanSkill = botChoice(hostPanel, "DIFFICULTY",
            lanSkillChoices, lanSkillSelection);
        CheckBox vehicleSeatsLan = new CheckBox(this);
        vehicleSeatsLan.setText("VEHICLE CREWS");
        vehicleSeatsLan.setChecked(settings.vehicleSeats);
        hudCheck(vehicleSeatsLan);
        hostPanel.addView(vehicleSeatsLan);
        CheckBox objectiveBotsLan = new CheckBox(this);
        objectiveBotsLan.setText("CTF / KING OBJECTIVES");
        objectiveBotsLan.setChecked(settings.objectiveModes);
        hudCheck(objectiveBotsLan);
        hostPanel.addView(objectiveBotsLan);
        lanPanel.addView(hostPanel);

        LinearLayout joinPanel = new LinearLayout(this);
        joinPanel.setOrientation(LinearLayout.VERTICAL);
        joinPanel.addView(lanText("Join the host on the same Wi-Fi or hotspot.", 12));
        joinPanel.addView(lanText("HOST IP", 12));
        EditText hostIp = new EditText(this);
        hostIp.setSingleLine(true);
        hostIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hostIp.setHint("Example: 10.28.111.42");
        hostIp.setText(settings.peer);
        hostIp.setSelectAllOnFocus(true);
        hudInput(hostIp);
        joinPanel.addView(hostIp);
        lanPanel.addView(joinPanel);

        Runnable updateRole = () -> {
            hostPanel.setVisibility(hostRole.isChecked() ? View.VISIBLE : View.GONE);
            joinPanel.setVisibility(hostRole.isChecked() ? View.GONE : View.VISIBLE);
        };
        roles.setOnCheckedChangeListener((group, checkedId) -> updateRole.run());
        updateRole.run();

        Button advancedToggle = new Button(this);
        advancedToggle.setText("NETWORK OPTIONS");
        hudButton(advancedToggle, false);
        lanPanel.addView(advancedToggle);
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(lanText("LOCAL ADDRESS OVERRIDE", 12));
        EditText local = new EditText(this);
        local.setSingleLine(true);
        local.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        local.setHint("Automatic");
        local.setText(settings.local);
        hudInput(local);
        advanced.addView(local);
        CheckBox lanOnly = new CheckBox(this);
        lanOnly.setText("LAN ONLY");
        lanOnly.setChecked(invitePending ? false : settings.lanOnly);
        hudCheck(lanOnly);
        advanced.addView(lanOnly);
        advanced.addView(lanText("Host port 5150. Joining client 5151.", 11));
        advanced.setVisibility(invitePending || !settings.lanOnly
            ? View.VISIBLE : View.GONE);
        advancedToggle.setOnClickListener(view -> advanced.setVisibility(
            advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        lanPanel.addView(advanced);

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
        content.addView(offlinePanel);
        offlinePanel.addView(hudSection("OFFLINE BOT MATCH"));
        String[] offlineCounts = new String[127];
        for (int i = 0; i < offlineCounts.length; i++)
            offlineCounts[i] = Integer.toString(i + 1);
        Spinner offlineCount = botChoice(offlinePanel, "BOT COUNT",
            offlineCounts, offlinePreferences.getInt("count", 3) - 1);
        String[] offlineSkills = { "Recruit", "Easy", "Normal", "Heroic", "Veteran", "Legendary" };
        Spinner offlineSkill = botChoice(offlinePanel, "DIFFICULTY",
            offlineSkills, offlinePreferences.getInt("skill", 2));
        String[] offlineTeams = { "Balanced teams", "Bots against players" };
        Spinner offlineTeam = botChoice(offlinePanel, "TEAM ASSIGNMENT",
            offlineTeams, offlinePreferences.getInt("team", 0));
        CheckBox vehicleSeatsOffline = new CheckBox(this);
        vehicleSeatsOffline.setText("VEHICLE CREWS");
        vehicleSeatsOffline.setChecked(offlinePreferences.getBoolean("vehicle_seats", false));
        hudCheck(vehicleSeatsOffline);
        offlinePanel.addView(vehicleSeatsOffline);

        // Fixed footer stays on screen even when the host options are long.
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(14), dp(5), dp(14), dp(10));
        footer.setBackground(hudSurface(Color.rgb(20, 35, 45),
            Color.rgb(9, 18, 27), HUD_BORDER));

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox offlineMode = new CheckBox(this);
        offlineMode.setText("OFFLINE BOTS");
        hudCheck(offlineMode);
        offlineMode.setBackground(hudSurface(Color.rgb(24, 42, 52),
            Color.rgb(14, 27, 36), HUD_BORDER));
        choices.addView(offlineMode, new LinearLayout.LayoutParams(
            0, dp(39), 1.0f));

        CheckBox lanMode = new CheckBox(this);
        lanMode.setText("LAN / SYSTEM LINK");
        hudCheck(lanMode);
        lanMode.setBackground(hudSurface(Color.rgb(24, 42, 52),
            Color.rgb(14, 27, 36), HUD_BORDER));
        LinearLayout.LayoutParams lanModeParams = new LinearLayout.LayoutParams(
            0, dp(39), 1.0f);
        lanModeParams.leftMargin = dp(8);
        choices.addView(lanMode, lanModeParams);
        footer.addView(choices);

        SharedPreferences launcherPreferences = getSharedPreferences("launcher_ui", MODE_PRIVATE);
        String savedMode = invitePending ? "lan" :
            launcherPreferences.getString("play_mode", "lan");
        offlineMode.setChecked("offline".equals(savedMode));
        lanMode.setChecked("lan".equals(savedMode));
        Runnable updateMode = () -> {
            offlinePanel.setVisibility(offlineMode.isChecked() ? View.VISIBLE : View.GONE);
            lanPanel.setVisibility(lanMode.isChecked() ? View.VISIBLE : View.GONE);
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
        updateMode.run();

        footer.addView(feedback);
        Button appSettings = new Button(this);
        appSettings.setText("OPEN ANDROID APP SETTINGS");
        hudButton(appSettings, false);
        appSettings.setVisibility(View.GONE);
        appSettings.setOnClickListener(next -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        footer.addView(appSettings);

        Button play = new Button(this);
        play.setText("PLAY");
        hudButton(play, true);
        play.setOnClickListener(view -> {
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
            if (offlineMode.isChecked()) {
                int count = offlineCount.getSelectedItemPosition() + 1;
                int skill = offlineSkill.getSelectedItemPosition();
                int team = offlineTeam.getSelectedItemPosition();
                boolean crews = vehicleSeatsOffline.isChecked();
                offlinePreferences.edit().putInt("count", count).putInt("skill", skill)
                    .putInt("team", team).putBoolean("vehicle_seats", crews).apply();
                launcherPreferences.edit().putString("play_mode", "offline").apply();
                Intent game = new Intent(this, HaloActivity.class);
                game.putExtra(HaloActivity.OFFLINE_BOT_ARGUMENTS, new String[] {
                    "--offline-bots", Integer.toString(count),
                    offlineSkills[skill].toLowerCase(java.util.Locale.ROOT),
                    team == 0 ? "even" : "against",
                    Boolean.toString(crews)
                });
                startGame(game);
                return;
            }
            if (lanMode.isChecked()) {
                try {
                    String peer = LanConfig.peerForRole(hostRole.isChecked(),
                        hostIp.getText().toString(), lanOnly.isChecked());
                    boolean hosting = hostRole.isChecked() && lanOnly.isChecked()
                        && !invitePending;
                    int count = hosting ? lanBots.getSelectedItemPosition() : 0;
                    String skill = hosting
                        ? LanConfig.BOT_SKILLS[lanSkill.getSelectedItemPosition()] : null;
                    LanConfig.save(new File(dataRoot, "config.toml"),
                        local.getText().toString(), peer, lanOnly.isChecked(),
                        count, skill, hosting && vehicleSeatsLan.isChecked(),
                        hosting && objectiveBotsLan.isChecked());
                    if (invitePending)
                        publishPendingInvite();
                } catch (java.io.IOException e) {
                    feedback.setText("Couldn't apply game settings: " + e.getMessage());
                    return;
                }
                launcherPreferences.edit().putString("play_mode", "lan").apply();
            } else {
                // No box checked: launch ordinary Halo with existing settings.
                launcherPreferences.edit().putString("play_mode", "standard").apply();
            }
            startGame();
        });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(49));
        playParams.topMargin = dp(6);
        footer.addView(play, playParams);
        root.addView(footer);
        setContentView(root);
    }

    private void buildInterface() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(36), dp(20), dp(36), dp(20));
        layout.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { Color.rgb(10, 18, 27), Color.rgb(20, 34, 45),
                Color.rgb(7, 13, 20) }));

        TextView title = new TextView(this);
        title.setText("HALO  /  GAME DATA");
        title.setTextColor(HUD_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
