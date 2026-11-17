package com.halo.decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Gallery of exactly the same original vector insignias actually drawn on
 * player profiles. Name and XP milestones derive from one career source. */
final class RankGallery {
    private RankGallery() { }

    private static int dp(Activity activity, float n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }

    private static TextView label(Activity a, String value, int size, int color) {
        TextView text = new TextView(a);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER);
        HaloTheme.font(text, size >= 13);
        text.setMaxLines(2);
        return text;
    }

    static void show(Activity activity, int currentRank) {
        int columns = activity.getResources().getConfiguration()
            .screenWidthDp >= 480 ? 3 : 2;
        int screenDp = activity.getResources().getConfiguration().screenWidthDp;
        int tileWidth = dp(activity, Math.max(116,
            Math.min(200, (screenDp - 65f) / columns)));
        LinearLayout content = new LinearLayout(activity);
        content.setPadding(dp(activity, 6), dp(activity, 10),
            dp(activity, 6), dp(activity, 10));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(HaloTheme.BACKGROUND_TOP);
        content.addView(label(activity, "UNSC SERVICE RANKS", 17, HaloTheme.FOCUS_TEXT));
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        for (int i = 0; i < CareerRecords.rankCount(); i++) {
            final int rank = i;
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(activity, 4), dp(activity, 5),
                dp(activity, 4), dp(activity, 5));
            boolean selected = rank == currentRank;
            tile.setBackground(HaloTheme.surface(
                selected ? HaloTheme.CONTROL_TOP : HaloTheme.PANEL_TOP,
                HaloTheme.PANEL_BOTTOM,
                selected ? HaloTheme.FOCUS : HaloTheme.BORDER,
                5, activity.getResources().getDisplayMetrics().density));
            RankInsignia emblem = new RankInsignia(activity, rank);
            tile.addView(emblem, new LinearLayout.LayoutParams(
                dp(activity, 66), dp(activity, 66)));
            tile.addView(label(activity, CareerRecords.rankName(rank), 13,
                selected ? HaloTheme.FOCUS_TEXT : HaloTheme.TEXT));
            tile.addView(label(activity,
                String.format(Locale.US, "%,d XP", CareerRecords.rankThreshold(rank)),
                11, HaloTheme.MUTED));
            if (selected)
                tile.addView(label(activity, "CURRENT RANK", 10,
                    HaloTheme.FOCUS_TEXT));
            GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
            cell.width = tileWidth;
            cell.height = dp(activity, selected ? 133 : 124);
            cell.leftMargin = dp(activity, 3);
            cell.rightMargin = dp(activity, 3);
            cell.topMargin = dp(activity, 3);
            cell.bottomMargin = dp(activity, 3);
            grid.addView(tile, cell);
        }
        content.addView(grid);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        new AlertDialog.Builder(activity)
            .setTitle("UNSC SERVICE RANK PROGRESSION")
            .setView(scroll)
            .setPositiveButton("CLOSE", null)
            .show();
    }
}
