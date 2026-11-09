package com.halo.decomp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

    private Spinner botChoice(LinearLayout layout, String label, String[] choices, int selection) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setPadding(0, dp(12), 0, 0);
        layout.addView(title);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selection, choices.length - 1)));
        layout.addView(spinner);
        return spinner;
    }

    private void showOfflineBotOptions(TextView feedback, Button appSettings) {
        SharedPreferences preferences = getSharedPreferences("offline_bots", MODE_PRIVATE);
        String[] counts = new String[127];
        for (int index = 0; index < counts.length; index++)
            counts[index] = Integer.toString(index + 1);
        String[] skills = { "Recruit", "Easy", "Normal", "Heroic", "Veteran", "Legendary" };
        String[] teams = { "Balanced teams", "All bots against human players" };

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView instructions = new TextView(this);
        instructions.setText("In Halo, choose Multiplayer → Split Screen, then Slayer or Team Slayer. "
            + "You can play alone or with controllers on this phone.");
        options.addView(instructions);
        Spinner count = botChoice(options, "Bots (1–127, stress test)", counts,
            preferences.getInt("count", 3) - 1);
        Button maxBots = new Button(this);
        maxBots.setText("MAX: 127 BOTS");
        maxBots.setOnClickListener(view -> count.setSelection(126));
        options.addView(maxBots);
        Spinner skill = botChoice(options, "Difficulty", skills, preferences.getInt("skill", 2));
        Spinner team = botChoice(options, "Team Slayer setup", teams, preferences.getInt("team", 0));
        CheckBox vehicleSeatsOffline = new CheckBox(this);
        vehicleSeatsOffline.setText("Experimental: bots board Warthog gunner/passenger seats");
        vehicleSeatsOffline.setChecked(preferences.getBoolean("vehicle_seats", false));
        options.addView(vehicleSeatsOffline);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(options);
        new AlertDialog.Builder(this)
            .setTitle("Offline Bot Match")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open Halo", (dialog, which) -> {
                if (!canStartGame(feedback, appSettings))
                    return;
                int botCount = count.getSelectedItemPosition() + 1;
                int skillIndex = skill.getSelectedItemPosition();
                int teamIndex = team.getSelectedItemPosition();
                boolean vehicleSeats = vehicleSeatsOffline.isChecked();
                preferences.edit().putInt("count", botCount).putInt("skill", skillIndex)
                    .putInt("team", teamIndex).putBoolean("vehicle_seats", vehicleSeats).apply();
                Intent game = new Intent(this, HaloActivity.class);
                game.putExtra(HaloActivity.OFFLINE_BOT_ARGUMENTS, new String[] {
                    "--offline-bots", Integer.toString(botCount),
                    skills[skillIndex].toLowerCase(java.util.Locale.ROOT),
                    teamIndex == 0 ? "even" : "against",
                    Boolean.toString(vehicleSeats)
                });
                startGame(game);
            })
            .show();
    }

    private TextView lanText(String message, int size) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.rgb(225, 230, 235));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setPadding(0, 0, 0, dp(7));
        return text;
    }

    /** Simple multiplayer setup. The *game* host may be any Wi-Fi client:
     * the hotspot owner does not have to host Halo. Each joining phone sends
     * searches to the same host IP; hosts learn searchers from incoming UDP
     * and do not need a list of guests. */
    private void buildLanInterface() {
        LanConfig.Settings settings = new LanConfig.Settings();
        String warning = "";
        try {
            settings = LanConfig.load(new File(dataRoot, "config.toml"));
        } catch (java.io.IOException e) {
            warning = "Could not read the current settings: " + e.getMessage();
        }

        final boolean invitePending = hasInternetInvite();
        java.util.List<String> ips = lanIPv4Addresses();
        String suggestedAddress = ips.size() == 1 ? ips.get(0) : "";

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(13), dp(20), dp(16));
        layout.setBackgroundColor(Color.rgb(12, 16, 20));
        scroll.addView(layout);

        layout.addView(lanText("Halo • Play Together", 23));
        layout.addView(lanText("All players connect to the same Wi-Fi or phone hotspot. "
            + "One phone hosts the HALO game; the hotspot owner can be someone else.", 14));
        layout.addView(lanText("This phone's LAN IP: "
            + (ips.isEmpty() ? "Not detected; check Wi-Fi settings."
               : android.text.TextUtils.join("  •  ", ips)), 15));
        layout.addView(lanText("Which role will this phone have in Halo?", 15));

        RadioGroup roles = new RadioGroup(this);
        roles.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton hostRole = new RadioButton(this);
        hostRole.setId(View.generateViewId());
        hostRole.setText("Host game");
        hostRole.setTextColor(Color.WHITE);
        RadioButton joinRole = new RadioButton(this);
        joinRole.setId(View.generateViewId());
        joinRole.setText("Join game");
        joinRole.setTextColor(Color.WHITE);
        roles.addView(hostRole);
        roles.addView(joinRole);
        roles.check(settings.peer.isEmpty() ? hostRole.getId() : joinRole.getId());
        layout.addView(roles);

        TextView feedback = lanText(inviteError == null ? warning : warning + " " + inviteError, 13);
        feedback.setTextColor(Color.rgb(255, 170, 120));

        LinearLayout hostPanel = new LinearLayout(this);
        hostPanel.setOrientation(LinearLayout.VERTICAL);
        hostPanel.addView(lanText("1. Start a co-op campaign or multiplayer System Link "
            + "lobby inside Halo.\n2. Give the IP below to EVERY joining player. "
            + "No guest IPs are needed on the host.", 14));
        hostPanel.addView(lanText("Host IP to share (edit if detection is wrong)", 15));
        EditText shareIp = new EditText(this);
        shareIp.setSingleLine(true);
        shareIp.setTextColor(Color.WHITE);
        shareIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        shareIp.setHint(ips.isEmpty() ? "Look in Wi-Fi / hotspot settings" : "Choose this phone's Wi-Fi IP");
        shareIp.setText(suggestedAddress);
        shareIp.setSelectAllOnFocus(true);
        hostPanel.addView(shareIp);
        if (ips.size() > 1)
            hostPanel.addView(lanText("Multiple addresses detected: confirm which one "
                + "belongs to this hotspot before sharing it.", 13));

        LinearLayout shareActions = new LinearLayout(this);
        shareActions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("Copy host IP");
        copy.setOnClickListener(view -> {
            String value = shareIp.getText().toString().trim();
            if (!LanConfig.validAddress(value) || value.isEmpty()) {
                feedback.setText("Enter this phone's valid LAN IP before copying it.");
                return;
            }
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                feedback.setText("Android clipboard is unavailable.");
                return;
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Halo host IP", value));
            feedback.setText("Copied " + value + ". Send it to everyone joining the game.");
        });
        shareActions.addView(copy, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button share = new Button(this);
        share.setText("Share invite");
        share.setOnClickListener(view -> {
            String value = shareIp.getText().toString().trim();
            if (!LanConfig.validAddress(value) || value.isEmpty()) {
                feedback.setText("Enter this phone's valid LAN IP before sharing it.");
                return;
            }
            Intent message = new Intent(Intent.ACTION_SEND);
            message.setType("text/plain");
            message.putExtra(Intent.EXTRA_TEXT, "Join my Halo LAN game. Connect to the "
                + "same Wi-Fi/hotspot, choose Join game in the Halo launcher, and enter "
                + "this Host IP: " + value);
            startActivity(Intent.createChooser(message, "Share Halo host IP"));
        });
        shareActions.addView(share, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        hostPanel.addView(shareActions);
        String[] lanBotChoices = new String[LanConfig.MAX_LAN_BOTS + 1];
        lanBotChoices[0] = "Off (0)";
        for (int index = 1; index < lanBotChoices.length; index++)
            lanBotChoices[index] = index + (index == 1 ? " bot" : " bots");
        Spinner lanBots = botChoice(hostPanel, "LAN Slayer bots (host only)",
            lanBotChoices, settings.systemLinkBotCount);
        String[] lanSkillChoices = { "Recruit", "Easy", "Normal", "Heroic", "Veteran", "Legendary" };
        int lanSkillSelection = 2;
        for (int index = 0; index < LanConfig.BOT_SKILLS.length; index++) {
            if (LanConfig.BOT_SKILLS[index].equals(settings.botSkill)) {
                lanSkillSelection = index;
                break;
            }
        }
        Spinner lanSkill = botChoice(hostPanel, "LAN bot difficulty",
            lanSkillChoices, lanSkillSelection);
        CheckBox vehicleSeatsLan = new CheckBox(this);
        vehicleSeatsLan.setText("Experimental: bots can board vehicle seats (no driving)");
        vehicleSeatsLan.setTextColor(Color.WHITE);
        vehicleSeatsLan.setChecked(settings.vehicleSeats);
        hostPanel.addView(vehicleSeatsLan);
        CheckBox objectiveBotsLan = new CheckBox(this);
        objectiveBotsLan.setText("Experimental: bots play CTF and King of the Hill");
        objectiveBotsLan.setTextColor(Color.WHITE);
        objectiveBotsLan.setChecked(settings.objectiveModes);
        hostPanel.addView(objectiveBotsLan);
        hostPanel.addView(lanText("4 bots have been tested on LAN. Try 8, then 16 "
            + "and watch synchronization and performance. Difficulty is set by the host. "
            + "CTF/King bots require the experimental checkbox. No campaign bots.", 13));
        layout.addView(hostPanel);

        LinearLayout joinPanel = new LinearLayout(this);
        joinPanel.setOrientation(LinearLayout.VERTICAL);
        joinPanel.addView(lanText("Ask the person hosting the HALO game for their IP. "
            + "Everyone joining enters that same address. You do NOT need the other "
            + "players' IPs or the hotspot gateway unless that gateway is the game host.", 14));
        joinPanel.addView(lanText("Halo game host's IP", 15));
        EditText hostIp = new EditText(this);
        hostIp.setSingleLine(true);
        hostIp.setTextColor(Color.WHITE);
        hostIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hostIp.setHint("Example: 10.28.111.42");
        hostIp.setText(settings.peer);
        hostIp.setSelectAllOnFocus(true);
        joinPanel.addView(hostIp);
        joinPanel.addView(lanText("After opening Halo, select System Link and join "
            + "the host's lobby. The game host starts the level once everyone is in.", 13));
        layout.addView(joinPanel);

        Runnable updateRole = () -> {
            boolean hosting = hostRole.isChecked();
            hostPanel.setVisibility(hosting ? View.VISIBLE : View.GONE);
            joinPanel.setVisibility(hosting ? View.GONE : View.VISIBLE);
        };
        roles.setOnCheckedChangeListener((group, checkedId) -> updateRole.run());
        updateRole.run();

        if (!settings.local.isEmpty())
            layout.addView(lanText("Note: a manual local IP override is active ("
                + settings.local + "). If you've switched hotspots, clear it under Advanced.", 13));

        Button advancedToggle = new Button(this);
        advancedToggle.setText("Advanced / troubleshooting");
        layout.addView(advancedToggle);
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(lanText("Local IP override (usually leave blank for Automatic)", 14));
        EditText local = new EditText(this);
        local.setSingleLine(true);
        local.setTextColor(Color.WHITE);
        local.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        local.setHint("Automatic");
        local.setText(settings.local);
        advanced.addView(local);

        CheckBox offline = new CheckBox(this);
        offline.setText("LAN only (disable internet matchmaking)");
        offline.setTextColor(Color.WHITE);
        offline.setChecked(invitePending ? false : settings.lanOnly);
        advanced.addView(offline);
        advanced.addView(lanText("LAN ports: game host UDP/TCP 5150; joining client UDP "
            + "5151. Close and reopen Halo after changing networks.", 12));
        advanced.setVisibility(invitePending || !settings.lanOnly ? View.VISIBLE : View.GONE);
        advancedToggle.setOnClickListener(view -> advanced.setVisibility(
            advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        layout.addView(advanced);

        TextView modeStatus = lanText(offline.isChecked()
            ? "LAN-only enabled. Internet matchmaking disabled."
            : "Internet mode is enabled; use Advanced to switch to LAN-only.", 13);
        offline.setOnCheckedChangeListener((button, enabled) ->
            modeStatus.setText(enabled
                ? "LAN-only enabled. Internet matchmaking disabled."
                : "Internet mode is enabled; use Advanced to switch to LAN-only."));
        layout.addView(modeStatus);

        if (invitePending) {
            layout.addView(lanText("An internet invitation is pending. LAN-only is "
                + "disabled for this launch. Save & Open Halo to accept it, or discard "
                + "the invitation to use local multiplayer.", 14));
            Button discard = new Button(this);
            discard.setText("Discard internet invitation (use LAN)");
            discard.setOnClickListener(view -> {
                try {
                    java.nio.file.Files.deleteIfExists(new File(dataRoot,
                        "pending_invite.txt").toPath());
                    buildLanInterface();
                } catch (java.io.IOException e) {
                    feedback.setText("Couldn't discard the invitation. Check app storage.");
                }
            });
            layout.addView(discard);
        }

        layout.addView(feedback);
        Button appSettings = new Button(this);
        appSettings.setText("Open Android App Settings / Force stop");
        appSettings.setVisibility(View.GONE);
        appSettings.setOnClickListener(next -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));

        Button play = new Button(this);
        play.setText("Save settings & Open Halo");
        play.setOnClickListener(view -> {
            if (invitePending && offline.isChecked()) {
                feedback.setText("Discard the pending internet invite before enabling LAN-only.");
                return;
            }
            if (!canStartGame(feedback, appSettings))
                return;
            try {
                String peer = LanConfig.peerForRole(hostRole.isChecked(),
                    hostIp.getText().toString(), offline.isChecked());
                boolean lanHosting = hostRole.isChecked() && offline.isChecked() && !invitePending;
                int count = lanHosting ? lanBots.getSelectedItemPosition() : 0;
                String skill = lanHosting
                    ? LanConfig.BOT_SKILLS[lanSkill.getSelectedItemPosition()] : null;
                LanConfig.save(new File(dataRoot, "config.toml"),
                    local.getText().toString(), peer, offline.isChecked(), count, skill,
                    lanHosting && vehicleSeatsLan.isChecked(),
                    lanHosting && objectiveBotsLan.isChecked());
                if (invitePending)
                    publishPendingInvite();
            } catch (java.io.IOException e) {
                feedback.setText("Could not apply network settings: " + e.getMessage());
                return;
            }
            startGame();
        });
        layout.addView(play);
        Button offlineBots = new Button(this);
        offlineBots.setText("Offline Bot Match");
        offlineBots.setOnClickListener(view -> showOfflineBotOptions(feedback, appSettings));
        layout.addView(offlineBots);
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
