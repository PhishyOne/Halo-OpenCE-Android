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
        Object request = new Object();
        target.setTag(request);
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
            java.util.List<MatchHighlights.Moment> markers;
            try {
                markers = MatchHighlights.load(appDirectory);
            } catch (IOException unreadable) {
                markers = java.util.Collections.emptyList();
                error = error == null ? unreadable.getMessage() :
                    error + "; " + unreadable.getMessage();
            }
            final java.util.List<MatchHighlights.Moment> moments = markers;
            final CareerRecords.Career result = career;
            final String warning = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || target.getTag() != request) return;
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
                target.addView(text(activity, coopSummary(result), 14, TEXT, false));
                section(activity, target, "TOP ACHIEVEMENTS");
                target.addView(threeMedals(activity, result));
                section(activity, target, "MATCH HIGHLIGHTS");
                java.util.List<MatchHighlights.Moment> latest =
                    MatchHighlights.mostRecent(result, moments);
                if (latest.isEmpty()) {
                    target.addView(text(activity,
                        "No highlight moments yet. Finish a multiplayer match.",
                        12, MUTED, false));
                } else {
                    for (MatchHighlights.Moment highlight : latest) {
                        target.addView(text(activity,
                            highlight.timing().substring(0, 5) + "  •  " +
                            highlight.label(), 12, TEXT, true));
                    }
                    Button timeline = new Button(activity);
                    timeline.setAllCaps(false);
                    timeline.setText("VIEW MOMENT TIMELINE");
                    HaloTheme.font(timeline, true);
                    HaloTheme.menuButtonColors(timeline);
                    timeline.setOnClickListener(view ->
                        showMomentTimeline(activity, result, moments));
                    target.addView(timeline, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 40)));
                }
                section(activity, target, "VIDEO REELS");
                Button videoReels = new Button(activity);
                videoReels.setText("WATCH / SAVE LOCAL VIDEO HIGHLIGHTS");
                videoReels.setAllCaps(false);
                HaloTheme.font(videoReels, true);
                HaloTheme.menuButtonColors(videoReels);
                videoReels.setOnClickListener(view -> {
                    if (activity instanceof LauncherActivity)
                        HaloReelGallery.show((LauncherActivity)activity, appDirectory);
                });
                target.addView(videoReels, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 43)));
                target.addView(text(activity,
                    "Real MP4 capture is optional. Older event bookmarks "
                    + "cannot generate missing video frames.", 11, MUTED, false));
                section(activity, target, "SPARTAN RATING");
                target.addView(text(activity, "UNRATED", 17, GOLD, true));
                target.addView(text(activity,
                    "Verified human matches are required for a skill rating.",
                    12, MUTED, false));
                if (warning != null)
                    target.addView(text(activity, "Career data: " + warning,
                        12, Color.rgb(255, 184, 136), false));
                Button full = new Button(activity);
                full.setAllCaps(false);
                full.setText("FULL SERVICE RECORD");
                HaloTheme.menuButtonColors(full);
                HaloTheme.font(full, true);
                full.setOnClickListener(view -> show(activity, appDirectory,
                    () -> dashboard(activity, appDirectory, target)));
                target.addView(full, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 44)));
            });
        }, "Halo-career-dashboard-read").start();
    }

    static void show(Activity activity, File appDirectory) {
        show(activity, appDirectory, null);
    }

    static void show(Activity activity, File appDirectory,
                     Runnable onProfileChanged) {
        new Thread(() -> {
            CareerRecords.Career career;
            String problem = null;
            try {
                career = CareerRecords.load(appDirectory);
            } catch (IOException failed) {
                career = new CareerRecords.Career();
                problem = failed.getMessage();
            }
            java.util.List<MatchHighlights.Moment> markers;
            try {
                markers = MatchHighlights.load(appDirectory);
            } catch (IOException unreadable) {
                markers = java.util.Collections.emptyList();
                problem = problem == null ? unreadable.getMessage() :
                    problem + "; " + unreadable.getMessage();
            }
            CareerRecords.Career loaded = career;
            java.util.List<MatchHighlights.Moment> highlights = markers;
            String error = problem;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed())
                    display(activity, loaded, error, onProfileChanged, highlights);
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

    /** The detail popup reads the actual achievement definition; no guessed
     * progress and no locked/secret achievements are revealed early. */
    private static void showAchievementDetails(Activity activity,
            CareerRecords.Achievement medal, int slot) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (medal == null || !medal.earned) {
            new AlertDialog.Builder(activity)
                .setTitle("ACHIEVEMENT SLOT " + slot)
                .setMessage("Not earned yet. Complete more matches and "
                    + "objectives to unlock an achievement for this slot.")
                .setPositiveButton("CLOSE", null)
                .show();
            return;
        }
        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_HORIZONTAL);
        details.setPadding(dp(activity, 18), dp(activity, 13),
            dp(activity, 18), dp(activity, 15));
        details.setBackground(HaloTheme.surface(HaloTheme.PANEL_TOP,
            HaloTheme.PANEL_BOTTOM, HaloTheme.BORDER, 6,
            activity.getResources().getDisplayMetrics().density));
        AchievementMedal enlarged = new AchievementMedal(activity, medal.rarity);
        details.addView(enlarged, new LinearLayout.LayoutParams(
            dp(activity, 64), dp(activity, 64)));

        TextView name = text(activity, medal.title, 19, TEXT, true);
        name.setGravity(Gravity.CENTER);
        details.addView(name);
        TextView tier = text(activity, medal.rarity + "  •  UNLOCKED",
            13, rarityColor(medal.rarity), true);
        tier.setGravity(Gravity.CENTER);
        details.addView(tier);

        TextView requirementLabel = text(activity,
            "UNLOCK REQUIREMENT", 11, GOLD, true);
        requirementLabel.setGravity(Gravity.CENTER);
        requirementLabel.setPadding(0, dp(activity, 12), 0, 0);
        details.addView(requirementLabel);
        TextView requirement = text(activity, medal.description,
            14, TEXT, false);
        requirement.setGravity(Gravity.CENTER);
        details.addView(requirement);

        // Landscape phone screens have little vertical space. Keep
        // descriptions readable without clipping the bottom of the dialog.
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(details);
        new AlertDialog.Builder(activity)
            .setTitle("SPARTAN ACHIEVEMENT")
            .setView(scroll)
            .setPositiveButton("CLOSE", null)
            .show();
    }

    /** The three best earned medals, never unreleased rank insignias.
     * Locked placeholders occupy empty slots without implying an award. */
    private static LinearLayout threeMedals(Activity activity,
                                            CareerRecords.Career career) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        java.util.List<CareerRecords.Achievement> earned = career.topAchievements();
        for (int index = 0; index < 3; index++) {
            CareerRecords.Achievement medal = index < earned.size() ?
                earned.get(index) : null;
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(activity, 4), dp(activity, 3),
                            dp(activity, 4), dp(activity, 3));
            tile.setBackground(HaloTheme.surface(HaloTheme.PANEL_TOP,
                HaloTheme.PANEL_BOTTOM, HaloTheme.BORDER, 5,
                activity.getResources().getDisplayMetrics().density));
            AchievementMedal badge = new AchievementMedal(activity,
                medal == null ? null : medal.rarity);
            tile.addView(badge, new LinearLayout.LayoutParams(
                dp(activity, 43), dp(activity, 43)));
            TextView title = text(activity,
                medal == null ? "LOCKED" : medal.title,
                12, medal == null ? MUTED : rarityColor(medal.rarity), true);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView rarity = text(activity,
                medal == null ? "NOT EARNED" : medal.rarity, 10, MUTED, false);
            rarity.setGravity(Gravity.CENTER);
            tile.addView(rarity);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(
                0, dp(activity, 110), 1f);
            if (index > 0) cell.leftMargin = dp(activity, 6);
            // The whole medal tile is tappable, not only its 43dp icon.
            final int slot = index + 1;
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(medal == null ?
                "Achievement slot " + slot + ", locked. Tap for details." :
                medal.title + ", " + medal.rarity +
                " achievement, unlocked. Tap for details.");
            tile.setForeground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x28FFFFFF), null,
                new android.graphics.drawable.ColorDrawable(Color.WHITE)));
            tile.setOnClickListener(view ->
                showAchievementDetails(activity, medal, slot));
            row.addView(tile, cell);
        }
        return row;
    }

    private static void section(Activity activity, LinearLayout layout, String title) {
        TextView label = text(activity, title, 13, GOLD, true);
        label.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        layout.addView(label);
    }

    private static String coopSummary(CareerRecords.Career career) {
        return "Co-op sessions  " + career.coopSessions + "     Missions completed  " + career.coopCompletions;
    }

    /** Read-only per-match details shared with themed history cards. */
    static String matchDetails(CareerRecords.Match match) {
        if (match.cooperative())
            return "Co-op  •  " + match.map + "\n" + match.outcome() + "\n" + match.seconds + " seconds";
        StringBuilder result = new StringBuilder();
        result.append("Game type: ").append(match.gameType())
            .append("\nMap: ").append(match.map)
            .append("\nDate: ").append(DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(match.epoch * 1000L)))
            .append("\nResult: ").append(match.outcome())
            .append("\nKills: ").append(match.kills)
            .append("\nDeaths: ").append(match.deaths)
            .append("\nAssists: ").append(match.assists)
            .append("\nBest streak: ").append(match.bestStreak)
            .append("\nDuration: ").append(match.seconds / 60).append(" min")
            .append("\nSuicides: ").append(match.suicides)
            .append("\nFriendly-fire kills: ").append(match.teamKills);
        if (match.humanKills < 0) {
            result.append("\nOpponent type: NOT CLASSIFIED");
        } else {
            result.append("\nHuman kills: ").append(match.humanKills)
                .append("\nBot kills: ").append(match.botKills);
        }
        if (!match.extended) {
            result.append("\n\nAdditional objective details were not "
                + "recorded in this older match.");
        } else {
            switch (match.mode) {
                case 1:
                    result.append("\nFlag grabs: ").append(match.flagGrabs)
                        .append("\nFlag captures: ").append(match.captures)
                        .append("\nFlag returns: ").append(match.returns);
                    break;
                case 3:
                    result.append("\nOddball held: ")
                        .append(match.oddballTicks / 30).append(" sec")
                        .append("\nBall-carrier kills: ")
                        .append(match.ballCarrierKills)
                        .append("\nKills while carrying: ")
                        .append(match.killsAsCarrier);
                    break;
                case 4:
                    result.append("\nHill time: ")
                        .append(match.hillTicks / 30).append(" sec");
                    break;
                case 5:
                    result.append("\nRace laps: ").append(match.raceLaps)
                        .append("\nBest lap: ")
                        .append(match.bestLapTicks > 0 ?
                            String.format(Locale.US, "%.1f sec",
                                match.bestLapTicks / 30f) : "No completed lap")
                        .append("\nLast lap: ")
                        .append(match.lastLapTicks > 0 ?
                            String.format(Locale.US, "%.1f sec",
                                match.lastLapTicks / 30f) : "No completed lap");
                    break;
                default:
                    break;
            }
        }
        result.append("\n\nAccuracy/headshots: Not yet instrumented");
        return result.toString();
    }

    private static void allMatches(Activity activity, CareerRecords.Career career) {
        MatchHistoryView.show(activity, career);
    }

    private static void showMomentTimeline(Activity activity,
            CareerRecords.Career career,
            java.util.List<MatchHighlights.Moment> moments) {
        java.util.List<CareerRecords.Match> games =
            new java.util.ArrayList<>(career.matches);
        java.util.Collections.reverse(games);
        java.util.List<CareerRecords.Match> available =
            new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (CareerRecords.Match match : games) {
            java.util.List<MatchHighlights.Moment> selected =
                MatchHighlights.forMatch(match, moments);
            if (selected.isEmpty()) continue;
            available.add(match);
            labels.add(match.gameType() + "  " + match.map +
                "    " + selected.size() + " moments");
        }
        if (available.isEmpty()) {
            new AlertDialog.Builder(activity)
                .setTitle("MATCH HIGHLIGHT MOMENTS")
                .setMessage("No recorded moments yet. Complete a multiplayer "
                    + "match in this version to capture event timestamps.")
                .setPositiveButton("CLOSE", null)
                .show();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("MATCH HIGHLIGHT MOMENTS")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                CareerRecords.Match match = available.get(which);
                java.util.List<MatchHighlights.Moment> selected =
                    MatchHighlights.forMatch(match, moments);
                StringBuilder summary = new StringBuilder();
                for (MatchHighlights.Moment moment : selected) {
                    summary.append(moment.timing()).append("  ")
                        .append(moment.label()).append('\n');
                }
                summary.append("\nEvent bookmarks only; no video or "
                    + "3D replay was recorded in this version.");
                new AlertDialog.Builder(activity)
                    .setTitle(match.map + "  •  " + match.gameType())
                    .setMessage(summary.toString())
                    .setPositiveButton("CLOSE", null)
                    .show();
            })
            .setNegativeButton("CLOSE", null)
            .show();
    }

    private static void display(Activity activity, CareerRecords.Career career,
                                String error, Runnable onProfileChanged,
                                java.util.List<MatchHighlights.Moment> highlights) {
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
            "Matches  " + career.games() + "     DNF  " + career.unfinished + "     Wins  " + career.wins +
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
        section(activity, panel, "OBJECTIVE AND MATCH RECORDS");
        panel.addView(text(activity,
            "CTF grabs  " + career.flagGrabs +
            "     Captures  " + career.captures +
            "     Returns  " + career.returns, 12, TEXT, false));
        panel.addView(text(activity,
            "Oddball held  " + (career.oddballTicks / 30) + " sec" +
            "     Carrier kills  " + career.ballCarrierKills +
            "     Kills carrying  " + career.killsAsCarrier, 12, TEXT, false));
        panel.addView(text(activity,
            "King hill time  " + (career.hillTicks / 30) + " sec" +
            "     Race laps  " + career.raceLaps, 12, TEXT, false));
        panel.addView(text(activity,
            "Fastest lap  " + (career.fastestLapTicks > 0 ?
                String.format(Locale.US, "%.1f sec",
                    career.fastestLapTicks / 30f) : "Not recorded"), 12,
            TEXT, false));
        panel.addView(text(activity,
            "Suicides  " + career.suicides +
            "     Friendly-fire kills  " + career.teamKills, 12, TEXT, false));
        panel.addView(text(activity, "Accuracy and headshots: Not tracked",
            12, MUTED, false));

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

        panel.addView(text(activity, coopSummary(career), 14, TEXT, false));
        section(activity, panel, "RECENT MATCHES");
        if (career.matches.isEmpty()) {
            panel.addView(text(activity,
                "Play a multiplayer match or co-op mission to start your service record.", 12, MUTED, false));
        } else {
            DateFormat dates = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT);
            for (CareerRecords.Match match : career.recent()) {
                String date = match.epoch > 0 ?
                    dates.format(new Date(match.epoch * 1000L)) : "Date unavailable";
                panel.addView(text(activity, date + "  " + match.gameType() +
                    "  " + match.map + "  " + match.outcome() +
                    (match.cooperative() ? "" : "  " + match.kills + "K/" + match.deaths + "D"), 11, MUTED, false));
            }
        }
        if (!MatchHighlights.mostRecent(career, highlights).isEmpty()) {
            Button momentTimeline = new Button(activity);
            momentTimeline.setText("VIEW MATCH HIGHLIGHTS");
            momentTimeline.setAllCaps(false);
            HaloTheme.menuButtonColors(momentTimeline);
            HaloTheme.font(momentTimeline, true);
            momentTimeline.setOnClickListener(view ->
                showMomentTimeline(activity, career, highlights));
            panel.addView(momentTimeline);
        }
        if (!career.matches.isEmpty()) {
            Button allHistory = new Button(activity);
            allHistory.setText("VIEW COMPLETE MATCH HISTORY");
            allHistory.setAllCaps(false);
            HaloTheme.menuButtonColors(allHistory);
            HaloTheme.font(allHistory, true);
            allHistory.setOnClickListener(view -> allMatches(activity, career));
            panel.addView(allHistory);
        }
        if (career.invalidLines > 0 || career.duplicates > 0)
            panel.addView(text(activity, "Skipped " + career.invalidLines +
                " invalid and " + career.duplicates + " duplicate records.",
                11, MUTED, false));
        if (error != null)
            panel.addView(text(activity, "Storage: " + error,
                12, Color.rgb(255, 184, 136), false));
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
                rename(activity, dialog, onProfileChanged)));
        dialog.show();
    }

    private static void rename(Activity activity, AlertDialog parent,
                               Runnable onProfileChanged) {
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
            // Refresh the live Profile tab now, not only on a tab switch.
            // The name is already persisted before the callback executes.
            if (onProfileChanged != null && !activity.isFinishing() &&
                    !activity.isDestroyed())
                onProfileChanged.run();
            show(activity, activity.getExternalFilesDir(null), onProfileChanged);
        }));
        rename.show();
    }
}
