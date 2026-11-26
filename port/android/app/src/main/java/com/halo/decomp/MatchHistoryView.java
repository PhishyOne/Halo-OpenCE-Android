package com.halo.decomp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native Halo-themed, recycled match cards. Read-only: no career mutation,
 * network calls, new game permissions or storage copies. */
final class MatchHistoryView {
    private static final String[] FILTER_LABELS =
        {"ALL", "WINS", "LOSSES", "DNF", "CO-OP"};
    private static final int WIN = Color.rgb(114, 223, 166);
    private static final int LOSS = Color.rgb(255, 142, 144);
    private static final int WARNING = Color.rgb(255, 188, 111);

    private final Activity activity;
    private final CareerRecords.Career career;
    private final Set<String> expandedIds = new HashSet<>();
    private final DateFormat dates = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT);
    private final List<CareerRecords.Match> visible = new ArrayList<>();
    private final Button[] filters = new Button[FILTER_LABELS.length];
    private Dialog dialog;
    private TextView countLabel;
    private ListView list;
    private BaseAdapter adapter;
    private int filter = MatchHistoryModel.ALL;

    private MatchHistoryView(Activity activity, CareerRecords.Career career) {
        this.activity = activity;
        this.career = career;
    }

    static void show(Activity activity, CareerRecords.Career career) {
        if (activity == null || activity.isFinishing() ||
            activity.isDestroyed() || career == null) return;
        new MatchHistoryView(activity, career).open();
    }

    private int dp(float value) {
        return Math.round(value *
            activity.getResources().getDisplayMetrics().density);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        HaloTheme.font(label, bold);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private void open() {
        dialog = new HaloWindow(activity);
        LinearLayout surface = new LinearLayout(activity);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(13), dp(9), dp(13), dp(10));
        surface.setBackground(HaloTheme.surface(
            HaloTheme.PANEL_TOP, HaloTheme.BACKGROUND_BOTTOM,
            HaloTheme.BORDER, 8,
            activity.getResources().getDisplayMetrics().density));

        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headingText = new LinearLayout(activity);
        headingText.setOrientation(LinearLayout.VERTICAL);
        headingText.addView(label("UNSC  /  MATCH ARCHIVE", 18,
            HaloTheme.FOCUS_TEXT, true));
        countLabel = label("", 11, HaloTheme.MUTED, false);
        headingText.addView(countLabel);
        heading.addView(headingText, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(activity);
        close.setText("CLOSE");
        close.setAllCaps(false);
        HaloTheme.menuButtonColors(close);
        HaloTheme.font(close, true);
        close.setOnClickListener(view -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(
            dp(90), dp(43)));
        surface.addView(heading);

        LinearLayout filterRow = new LinearLayout(activity);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams filterRowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        filterRowParams.topMargin = dp(7);
        filterRowParams.bottomMargin = dp(7);
        for (int i = 0; i < filters.length; i++) {
            final int option = i;
            Button button = new Button(activity);
            button.setText(FILTER_LABELS[i]);
            button.setAllCaps(false);
            button.setTextSize(11);
            HaloTheme.font(button, true);
            HaloTheme.menuButtonColors(button);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(dp(1), 0, dp(1), 0);
            button.setOnClickListener(v -> applyFilter(option));
            filters[i] = button;
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(
                0, dp(39), 1);
            if (i > 0) item.leftMargin = dp(5);
            filterRow.addView(button, item);
        }
        surface.addView(filterRow, filterRowParams);

        FrameLayout content = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setDividerHeight(dp(7));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new Cards();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            CareerRecords.Match match = visible.get(position);
            if (!expandedIds.add(match.id)) expandedIds.remove(match.id);
            adapter.notifyDataSetChanged();
        });
        content.addView(list);
        TextView empty = label("NO MATCHES IN THIS CATEGORY", 14,
            HaloTheme.MUTED, true);
        empty.setGravity(Gravity.CENTER);
        content.addView(empty, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        surface.addView(content, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(surface);
        applyFilter(MatchHistoryModel.ALL);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.72f);
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            window.setLayout(Math.round(width * .96f),
                Math.round(height * .94f));
        }
    }

    private void applyFilter(int option) {
        filter = option;
        visible.clear();
        visible.addAll(MatchHistoryModel.select(career, filter));
        for (int i = 0; i < filters.length; i++)
            filters[i].setSelected(i == filter);
        countLabel.setText(String.format(Locale.US,
            "%,d shown  •  %,d recorded  •  saved on this phone",
            visible.size(), career.matches.size()));
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private int statusColor(CareerRecords.Match match) {
        if (match.incomplete) return WARNING;
        if (match.cooperative()) return WIN;
        return match.wins == 1 ? WIN : LOSS;
    }

    private static final class Holder {
        LinearLayout card, header, details;
        TextView status, map, mode, summary, detailText;
        ImageView arrow;
        DisclosureChevron chevron;
    }

    private final class Cards extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) {
            return visible.get(position);
        }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View reuse, ViewGroup parent) {
            final Holder h;
            if (reuse == null) {
                h = new Holder();
                h.card = new LinearLayout(activity);
                h.card.setOrientation(LinearLayout.VERTICAL);
                h.card.setPadding(dp(10), dp(8), dp(10), dp(8));
                h.header = new LinearLayout(activity);
                h.header.setGravity(Gravity.CENTER_VERTICAL);
                h.status = label("", 12, WIN, true);
                h.status.setGravity(Gravity.CENTER_VERTICAL);
                h.header.addView(h.status, new LinearLayout.LayoutParams(
                    dp(113), LinearLayout.LayoutParams.WRAP_CONTENT));

                LinearLayout stack = new LinearLayout(activity);
                stack.setOrientation(LinearLayout.VERTICAL);
                h.map = label("", 15, HaloTheme.TEXT, true);
                h.map.setSingleLine(true);
                h.map.setEllipsize(TextUtils.TruncateAt.END);
                stack.addView(h.map);
                h.mode = label("", 11, HaloTheme.MUTED, false);
                h.mode.setSingleLine(true);
                h.mode.setEllipsize(TextUtils.TruncateAt.END);
                stack.addView(h.mode);
                h.header.addView(stack, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                h.arrow = new ImageView(activity);
                h.chevron = new DisclosureChevron(activity.getResources());
                h.arrow.setImageDrawable(h.chevron);
                h.arrow.setContentDescription("Expand match details");
                h.header.addView(h.arrow, new LinearLayout.LayoutParams(
                    dp(25), dp(36)));
                h.card.addView(h.header);

                h.summary = label("", 12, HaloTheme.MUTED, false);
                h.summary.setPadding(0, dp(4), 0, 0);
                h.card.addView(h.summary);

                h.details = new LinearLayout(activity);
                h.details.setOrientation(LinearLayout.VERTICAL);
                h.details.setPadding(dp(8), dp(8), dp(5), dp(5));
                h.detailText = label("", 12, HaloTheme.TEXT, false);
                h.details.addView(h.detailText);
                LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                detailsParams.topMargin = dp(8);
                h.card.addView(h.details, detailsParams);
                h.card.setTag(h);
                reuse = h.card;
            } else {
                h = (Holder) reuse.getTag();
            }
            CareerRecords.Match match = visible.get(position);
            boolean open = expandedIds.contains(match.id);
            h.card.setBackground(HaloTheme.surface(
                open ? HaloTheme.CONTROL_TOP : HaloTheme.PANEL_TOP,
                HaloTheme.PANEL_BOTTOM,
                open ? HaloTheme.FOCUS : HaloTheme.BORDER,
                5, activity.getResources().getDisplayMetrics().density));
            h.status.setText(match.outcome());
            h.status.setTextColor(statusColor(match));
            h.map.setText(match.map);
            h.mode.setText(match.gameType() + "   •   " +
                dates.format(new Date(match.epoch * 1000L)));
            h.summary.setText(MatchHistoryModel.summary(match));
            h.details.setVisibility(open ? View.VISIBLE : View.GONE);
            h.detailText.setText(open ? CareerProfile.matchDetails(match) : "");
            h.chevron.setCardExpanded(open);
            h.arrow.setContentDescription(open ?
                "Collapse match details" : "Expand match details");
            h.card.setContentDescription(match.outcome() + ", " +
                match.gameType() + ", " + match.map + ", " +
                (open ? "expanded" : "collapsed"));
            return reuse;
        }
    }
}
