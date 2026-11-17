package com.halo.decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Local Spartan service record, available from launcher while game is closed.
 * No login, network fetch, tracking SDK or permissions. */
final class CareerProfile {
    private static final int TEXT = HaloTheme.TEXT;
    private static final int MUTED = HaloTheme.MUTED;
    private static final int GOLD = HaloTheme.FOCUS_TEXT;
    private static final int PANEL = HaloTheme.PANEL_BOTTOM;
    private static final String PREFS = "career_profile_v1";

    private CareerProfile() { }

    private static int rarityColor(String rarity) {
        switch (rarity) {
            case "UNCOMMON": return Color.rgb(144, 214, 151);
            case "RARE": return Color.rgb(125, 191, 244);
            case "LEGENDARY": return Color.rgb(244, 183, 94);
            default: return MUTED;
        }
    }

    /** First launcher screen: compact local stats, not an intrusive modal.
     * Loading is asynchronous and never blocks the main/UI thread. */
    static void dashboard(Activity activity, File appDirectory, LinearLayout target) {
        target.removeAllViews();
        target.addView(text(activity, "LOADING SPARTAN SERVICE RECORD...", 14, MUTED, true));
        new Thread(() -> {
            CareerRecords.Career career;
            String error = null;
            try {
                career = CareerRecords.load(appDirectory);
            } catch (IOException failure) {
                career = new CareerRecords.Career();
                error = failure.getMessage();
            }
            final CareerRecords.Career result = career;
            final String warning = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                target.removeAllViews();
                SharedPreferences pref = activity.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
                String name = pref.getString("name", "Spartan");
                section(activity, target, CareerRecords.SERVICE_RANK_TITLE);
                target.addView(identity(activity, name, result.rankIndex(),
                    result.rank(), 22));
                target.addView(text(activity,
                    "Rank " + result.rankNumber() + "    " +
                    String.format(Locale.US, "%,d", result.xp) + " XP", 14, TEXT, true));
                if (result.rankNext() > result.rankCurrent()) {
                    int pct = (int)Math.min(100,
                        (result.xp - result.rankCurrent()) * 100 /
                        (result.rankNext() - result.rankCurrent()));
                    ProgressBar progress = new ProgressBar(activity, null,
                        android.R.attr.progressBarStyleHorizontal);
                    progress.setMax(100);
                    progress.setProgress(pct);
                    progress.setProgressTintList(ColorStateList.valueOf(GOLD));
                    target.addView(progress, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 12)));
                    target.addView(text(activity, "Next promotion in " +
                        String.format(Locale.US, "%,d", result.rankNext() - result.xp) +
                        " XP", 12, MUTED, false));
                } else {
                    target.addView(text(activity, "MAXIMUM RANK EARNED",
                        12, GOLD, true));
                }
                section(activity, target, "CAREER SNAPSHOT");
                target.addView(text(activity, "Matches  " + result.games() +
                    "     Wins  " + result.wins +
                    "     K/D  " + result.kd(), 17, TEXT, true));
                target.addView(text(activity, "Kills  " + result.kills +
                    "     Deaths  " + result.deaths +
                    "     Assists  " + result.assists +
                    "     Best streak  " + result.bestStreak, 13, TEXT, false));
                long earned = 0;
                for (CareerRecords.Achievement medal : result.achievements())
                    if (medal.earned) earned++;
                target.addView(text(activity, "Achievements unlocked: " + earned +
                    " / " + result.achievements().size(), 13, GOLD, true));
                section(activity, target, "SPARTAN RATING");
                target.addView(text(activity, "UNRATED", 17, GOLD, true));
                target.addView(text(activity,
                    "Verified human matches are required for a skill rating.",
                    12, MUTED, false));
                if (warning != null)
                    target.addView(text(activity, "Career data: " + warning,
                        12, Color.rgb(255, 184, 136), false));
                LinearLayout actions = new LinearLayout(activity);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setGravity(Gravity.CENTER_VERTICAL);
                Button full = new Button(activity);
                full.setAllCaps(false);
                full.setText("FULL SERVICE RECORD");
                full.setTextColor(TEXT);
                full.setBackgroundTintList(ColorStateList.valueOf(
                    HaloTheme.CONTROL_TOP));
                HaloTheme.font(full, true);
                full.setOnClickListener(view -> show(activity, appDirectory));
                actions.addView(full, new LinearLayout.LayoutParams(0,
                    dp(activity, 44), 1f));
                Button ranks = new Button(activity);
                ranks.setAllCaps(false);
                ranks.setText("VIEW ALL RANKS");
                ranks.setTextColor(TEXT);
                ranks.setBackgroundTintList(ColorStateList.valueOf(
                    HaloTheme.CONTROL_TOP));
                HaloTheme.font(ranks, true);
                ranks.setOnClickListener(view ->
                    RankGallery.show(activity, result.rankIndex()));
                LinearLayout.LayoutParams rankButton = new LinearLayout.LayoutParams(
                    0, dp(activity, 44), 1f);
                rankButton.leftMargin = dp(activity, 8);
                actions.addView(ranks, rankButton);
                target.addView(actions);
            });
        }, "Halo-career-dashboard-read").start();
    }

    static void show(Activity activity, File appDirectory) {
        new Thread(() -> {
            CareerRecords.Career career;
            String problem = null;
            try {
                career = CareerRecords.load(appDirectory);
            } catch (IOException failed) {
                career = new CareerRecords.Career();
                problem = failed.getMessage();
            }
            CareerRecords.Career loaded = career;
            String error = problem;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed())
                    display(activity, loaded, error);
            });
        }, "Halo-career-profile-read").start();
    }

    private static int dp(Activity activity, int amount) {
        return (int)(amount * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static TextView text(Activity activity, String content, int size,
                                 int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(0, dp(activity, 3), 0, dp(activity, 3));
        HaloTheme.font(view, bold);
        return view;
    }

    private static LinearLayout identity(Activity activity, String name,
            int rankIndex, String rank, int nameSize) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        RankInsignia emblem = new RankInsignia(activity, rankIndex);
        row.addView(emblem, new LinearLayout.LayoutParams(
            dp(activity, 70), dp(activity, 70)));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(activity, 12), 0, 0, 0);
        titles.addView(text(activity, name, nameSize, TEXT, true));
        titles.addView(text(activity, rank, 15, GOLD, true));
        row.addView(titles, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static void section(Activity activity, LinearLayout layout, String title) {
        TextView label = text(activity, title, 13, GOLD, true);
        label.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        layout.addView(label);
    }

    private static void display(Activity activity, CareerRecords.Career career,
                                String error) {
        SharedPreferences pref = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = pref.getString("name", "Spartan");
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 14), dp(activity, 10),
            dp(activity, 14), dp(activity, 12));
        panel.setBackground(HaloTheme.surface(HaloTheme.PANEL_TOP,
            PANEL, HaloTheme.BORDER, 6,
            activity.getResources().getDisplayMetrics().density));

        section(activity, panel, CareerRecords.SERVICE_RANK_TITLE);
        panel.addView(identity(activity, name, career.rankIndex(),
            career.rank(), 20));
        panel.addView(text(activity,
            "RANK " + career.rankNumber() + "     " +
            String.format(Locale.US, "%,d", career.xp) + " XP",
            12, GOLD, true));
        if (career.rankNext() > career.rankCurrent()) {
            int percent = (int)(100 * (career.xp - career.rankCurrent()) /
                (career.rankNext() - career.rankCurrent()));
            panel.addView(text(activity,
                "Next rank: " + (career.rankNext() - career.xp) +
                " XP    (" + percent + "% complete)", 12, MUTED, false));
        } else {
            panel.addView(text(activity, "Maximum career rank reached",
                12, GOLD, true));
        }

        section(activity, panel, "SPARTAN RATING");
        panel.addView(text(activity, "UNRATED", 16, GOLD, true));
        panel.addView(text(activity,
            "Competitive rating requires verified human matches. "
            + "Bot kills and career XP do not affect skill rating.",
            12, MUTED, false));

        section(activity, panel, "CAREER STATISTICS");
        panel.addView(text(activity,
            "Matches  " + career.games() + "     Wins  " + career.wins +
            "     K/D  " + career.kd(), 14, TEXT, true));
        panel.addView(text(activity,
            "Kills  " + career.kills + "     Deaths  " + career.deaths +
            "     Assists  " + career.assists, 13, TEXT, false));
        panel.addView(text(activity,
            "Human kills  " + career.humanKills +
            "     Bot kills  " + career.botKills, 13, TEXT, false));
        if (career.unknownOpponentKills > 0)
            panel.addView(text(activity, career.unknownOpponentKills +
                " kills not classified by opponent type", 12, MUTED, false));
        panel.addView(text(activity,
            "Best streak  " + career.bestStreak +
            "     Flag captures  " + career.captures +
            "     Flag returns  " + career.returns, 13, TEXT, false));
        panel.addView(text(activity,
            "Time played  " + (career.seconds / 3600) + "h " +
            (career.seconds / 60 % 60) + "m", 12, MUTED, false));

        long unlocked = 0;
        for (CareerRecords.Achievement medal : career.achievements())
            if (medal.earned) unlocked++;
        section(activity, panel, "ACHIEVEMENTS  " + unlocked + "/" +
            career.achievements().size());
        panel.addView(text(activity,
            "COMMON  •  UNCOMMON  •  RARE  •  LEGENDARY", 11, MUTED, true));
        for (CareerRecords.Achievement medal : career.achievements()) {
            panel.addView(text(activity,
                (medal.earned ? "★  " : "○  ") + "[" + medal.rarity +
                "] " + medal.title + "  —  " + medal.description, 12,
                medal.earned ? rarityColor(medal.rarity) : MUTED, medal.earned));
        }

        section(activity, panel, "RECENT MATCHES");
        if (career.matches.isEmpty()) {
            panel.addView(text(activity,
                "No completed multiplayer matches recorded yet. Finish a match " +
                "then reopen Service Record.", 12, MUTED, false));
        } else {
            DateFormat dates = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT);
            for (CareerRecords.Match match : career.recent()) {
                String date = match.epoch > 0 ?
                    dates.format(new Date(match.epoch * 1000L)) : "Date unavailable";
                panel.addView(text(activity, date + "  " + match.gameType() +
                    "  " + match.map + "  " + (match.wins == 1 ? "WIN" : "MATCH") +
                    "  " + match.kills + "K/" + match.deaths + "D", 11, MUTED, false));
            }
        }
        if (career.invalidLines > 0 || career.duplicates > 0)
            panel.addView(text(activity, "Skipped " + career.invalidLines +
                " invalid and " + career.duplicates + " duplicate records.",
                11, MUTED, false));
        if (error != null)
            panel.addView(text(activity, "Storage: " + error,
                12, Color.rgb(255, 184, 136), false));
        Button gallery = new Button(activity);
        gallery.setText("VIEW ALL 12 UNSC RANK INSIGNIAS");
        gallery.setAllCaps(false);
        HaloTheme.font(gallery, true);
        gallery.setTextColor(TEXT);
        gallery.setBackgroundTintList(ColorStateList.valueOf(HaloTheme.CONTROL_TOP));
        gallery.setOnClickListener(view -> RankGallery.show(activity,
            career.rankIndex()));
        panel.addView(gallery);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("SPARTAN SERVICE RECORD")
            .setView(scroll)
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("RENAME", null)
            .create();
        dialog.setOnShowListener(unused -> dialog.getButton(
            AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                rename(activity, dialog)));
        dialog.show();
    }

    private static void rename(Activity activity, AlertDialog parent) {
        SharedPreferences pref = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(16) });
        input.setText(pref.getString("name", "Spartan"));
        input.setSelectAllOnFocus(true);
        AlertDialog rename = new AlertDialog.Builder(activity)
            .setTitle("Choose your Spartan name")
            .setMessage("2–16 characters: letters, digits, spaces, hyphens or underscores.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE", null)
            .create();
        rename.setOnShowListener(unused -> rename.getButton(
            AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (!name.matches("[A-Za-z0-9 _-]{2,16}")) {
                input.setError("Use 2–16 safe characters.");
                return;
            }
            pref.edit().putString("name", name).apply();
            rename.dismiss();
            parent.dismiss();
            show(activity, activity.getExternalFilesDir(null));
        }));
        rename.show();
    }
}
