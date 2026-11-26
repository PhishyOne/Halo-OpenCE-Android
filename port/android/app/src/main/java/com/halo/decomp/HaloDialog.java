package com.halo.decomp;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** App-owned Halo command panels. A plain Dialog supplies the window and
 * Android Back/accessibility lifecycle; every visible surface is ours.
 * Preserve the small builder contract used by the former stock dialogs,
 * including overridable buttons and choice/dismiss/cancel semantics. */
final class HaloDialog extends HaloWindow {
    private final Context context;
    private final Map<Integer, Button> buttons = new LinkedHashMap<>();
    private final boolean tall;
    private final boolean hasInput;

    private HaloDialog(Builder spec) {
        super(spec.context);
        context = spec.context;
        setCancelable(spec.cancelable);
        setCanceledOnTouchOutside(false);
        if (spec.cancelListener != null) setOnCancelListener(spec.cancelListener);
        tall = spec.view instanceof ScrollView || spec.items != null;
        hasInput = containsInput(spec.view);

        LinearLayout body = column();
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        body.setBackground(HaloTheme.surface(HaloTheme.PANEL_TOP, HaloTheme.BACKGROUND_BOTTOM,
            HaloTheme.BORDER, 5, context.getResources().getDisplayMetrics().density));
        TextView eyebrow = text("HALO  /  SYSTEM", 10, HaloTheme.MUTED, true);
        eyebrow.setLetterSpacing(.14f);
        body.addView(eyebrow);
        if (spec.title != null && spec.title.length() > 0)
            body.addView(text(spec.title.toString().toUpperCase(Locale.ROOT), 18,
                HaloTheme.FOCUS_TEXT, true));
        View rule = new View(context);
        rule.setBackgroundColor(HaloTheme.FOCUS);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, dp(1));
        ruleParams.bottomMargin = dp(5);
        body.addView(rule, ruleParams);

        // Reuse a caller-supplied ScrollView, never nest scroll containers.
        if (spec.view instanceof ScrollView && spec.items == null && spec.message == null) {
            applyTheme(spec.view);
            body.addView(spec.view, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(false);
            LinearLayout content = column();
            if (spec.message != null)
                content.addView(text(spec.message, 14, HaloTheme.TEXT, false));
            if (spec.items != null) addChoices(content, spec);
            if (spec.view != null) {
                applyTheme(spec.view);
                content.addView(spec.view, new LinearLayout.LayoutParams(-1, -2));
            }
            scroll.addView(content);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        if (!spec.actions.isEmpty()) {
            LinearLayout footer = new LinearLayout(context);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            int[] order = {BUTTON_NEUTRAL, BUTTON_NEGATIVE, BUTTON_POSITIVE};
            for (int which : order) {
                Action action = spec.actions.get(which);
                if (action == null) continue;
                Button b = button(action.label);
                // Positive is an action, not a permanently highlighted tab.
                b.setOnClickListener(view -> {
                    // Match stock AlertDialog: a default action dismisses
                    // even if its callback is null. Callers can replace
                    // this listener for validation or non-dismissing menus.
                    try {
                        if (action.listener != null) action.listener.onClick(this, which);
                    } finally { dismiss(); }
                });
                buttons.put(which, b);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
                if (footer.getChildCount() > 0) params.leftMargin = dp(6);
                footer.addView(b, params);
            }
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
            fp.topMargin = dp(7);
            body.addView(footer, fp);
        }
        setContentView(body);
    }

    private int dp(int n) {
        return Math.round(n * context.getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private TextView text(CharSequence value, int size, int color, boolean heading) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(3), dp(4), dp(5));
        HaloTheme.font(t, heading);
        return t;
    }

    private Button button(CharSequence label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        HaloTheme.menuButtonColors(b);
        HaloTheme.font(b, true);
        return b;
    }

    private void addChoices(LinearLayout content, Builder spec) {
        for (int i = 0; i < spec.items.length; i++) {
            final int index = i;
            if (spec.multi != null) {
                CheckBox c = new CheckBox(context);
                c.setText(spec.items[i]);
                c.setTextColor(HaloTheme.TEXT);
                c.setTextSize(13);
                c.setButtonTintList(HaloTheme.CHECK_TINT);
                HaloTheme.font(c, false);
                c.setMinHeight(dp(45));
                c.setPadding(dp(5), dp(2), dp(5), dp(2));
                c.setBackground(HaloTheme.surface(HaloTheme.CONTROL_TOP, HaloTheme.CONTROL_BOTTOM,
                    HaloTheme.BORDER, 3, context.getResources().getDisplayMetrics().density));
                c.setChecked(spec.checked != null && spec.checked[i]);
                c.setOnCheckedChangeListener((v, checked) -> {
                    if (spec.checked != null) spec.checked[index] = checked;
                    spec.multi.onClick(this, index, checked);
                });
                addChoice(content, c);
            } else {
                boolean single = spec.single;
                Button b = button(spec.items[i]);
                b.setMinHeight(dp(43));
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setSelected(single && i == spec.selected);
                b.setOnClickListener(view -> {
                    if (single) {
                        for (int n = 0; n < content.getChildCount(); n++)
                            if (content.getChildAt(n) instanceof Button)
                                content.getChildAt(n).setSelected(content.getChildAt(n) == b);
                    }
                    try {
                        if (spec.choice != null) spec.choice.onClick(this, index);
                    } finally { if (!single) dismiss(); }
                });
                addChoice(content, b);
            }
        }
    }

    private void addChoice(LinearLayout content, View row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(4);
        content.addView(row, p);
    }

    private static boolean containsInput(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsInput(group.getChildAt(i))) return true;
        }
        return false;
    }

    /** Style custom caller content without replacing controls, listeners,
     * text values, layout IDs or the original callback behavior. */
    private void applyTheme(View view) {
        if (view instanceof Switch) {
            Switch toggle = (Switch)view;
            toggle.setTextColor(HaloTheme.TEXT);
            toggle.setThumbTintList(HaloTheme.CHECK_TINT);
            toggle.setTrackTintList(HaloTheme.CHECK_TINT);
            HaloTheme.font(toggle, false);
        } else if (view instanceof CompoundButton) {
            CompoundButton toggle = (CompoundButton)view;
            toggle.setButtonTintList(HaloTheme.CHECK_TINT);
            toggle.setTextColor(HaloTheme.TEXT);
            HaloTheme.font(toggle, false);
        } else if (view instanceof Button) {
            HaloTheme.menuButtonColors((Button)view);
            HaloTheme.font((Button)view, true);
        } else if (view instanceof EditText) {
            EditText edit = (EditText)view;
            edit.setTextColor(HaloTheme.TEXT);
            edit.setHintTextColor(HaloTheme.MUTED);
            edit.setBackgroundTintList(ColorStateList.valueOf(HaloTheme.FOCUS));
            HaloTheme.font(edit, false);
        } else if (view instanceof TextView) {
            // Existing custom career/achievement text has intentional colors.
            // Only normalize the standard Android foreground palette.
            TextView t = (TextView)view;
            int c = t.getCurrentTextColor();
            if (c == Color.BLACK || c == Color.WHITE ||
                c == 0xff212121 || c == 0xffdeDEDE)
                t.setTextColor(HaloTheme.TEXT);
            HaloTheme.font(t, t.getTypeface() != null && t.getTypeface().isBold());
        }
        if (view instanceof ProgressBar) {
            ProgressBar bar = (ProgressBar)view;
            ColorStateList accent = ColorStateList.valueOf(HaloTheme.FOCUS);
            bar.setProgressTintList(accent);
            bar.setIndeterminateTintList(accent);
            if (bar instanceof SeekBar) ((SeekBar)bar).setThumbTintList(accent);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) applyTheme(group.getChildAt(i));
        }
    }

    public Button getButton(int which) { return buttons.get(which); }

    @Override public void show() {
        super.show();
        Window w = getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(HaloTheme.BACKGROUND_BOTTOM));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.dimAmount = .64f;
        w.setAttributes(attrs);
        // Fixed header/footer, bounded scrollable content. Use current
        // app-window metrics instead of assuming a portrait desktop screen.
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = Math.min(metrics.widthPixels - dp(12), dp(tall ? 820 : 650));
        int height = (int)(metrics.heightPixels * (tall ? .91 : .79));
        w.setLayout(Math.max(dp(200), width), Math.max(dp(150), height));
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (!hasInput) w.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private static final class Action {
        final CharSequence label;
        final DialogInterface.OnClickListener listener;
        Action(CharSequence label, DialogInterface.OnClickListener listener) {
            this.label = label;
            this.listener = listener;
        }
    }

    static final class Builder {
        private final Context context;
        private CharSequence title, message;
        private View view;
        private CharSequence[] items;
        private boolean[] checked;
        private boolean single, cancelable = true;
        private int selected = -1;
        private DialogInterface.OnClickListener choice;
        private DialogInterface.OnMultiChoiceClickListener multi;
        private DialogInterface.OnCancelListener cancelListener;
        private final Map<Integer, Action> actions = new LinkedHashMap<>();

        Builder(Context context) { this.context = context; }
        Builder setTitle(CharSequence title) { this.title = title; return this; }
        Builder setMessage(CharSequence message) { this.message = message; return this; }
        Builder setView(View view) { this.view = view; return this; }
        Builder setCancelable(boolean cancelable) { this.cancelable = cancelable; return this; }
        Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            cancelListener = listener; return this;
        }
        Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
            this.items = items.clone(); this.choice = listener; single = false; multi = null; return this;
        }
        Builder setSingleChoiceItems(CharSequence[] items, int selected,
                                     DialogInterface.OnClickListener listener) {
            setItems(items, listener); this.selected = selected; single = true; return this;
        }
        Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked,
                                    DialogInterface.OnMultiChoiceClickListener listener) {
            if (checked != null && checked.length != items.length)
                throw new IllegalArgumentException("Choice selection length mismatch");
            this.items = items.clone(); this.checked = checked; this.multi = listener;
            this.single = false; this.choice = null; return this;
        }
        Builder setPositiveButton(CharSequence label, DialogInterface.OnClickListener listener) {
            actions.put(BUTTON_POSITIVE, new Action(label, listener)); return this;
        }
        Builder setNegativeButton(CharSequence label, DialogInterface.OnClickListener listener) {
            actions.put(BUTTON_NEGATIVE, new Action(label, listener)); return this;
        }
        Builder setNeutralButton(CharSequence label, DialogInterface.OnClickListener listener) {
            actions.put(BUTTON_NEUTRAL, new Action(label, listener)); return this;
        }
        HaloDialog create() { return new HaloDialog(this); }
        HaloDialog show() { HaloDialog dialog = create(); dialog.show(); return dialog; }
    }
}
