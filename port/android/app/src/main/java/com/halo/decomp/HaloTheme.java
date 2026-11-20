package com.halo.decomp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.widget.Button;
import android.widget.TextView;

/** Shared OpenCE visual language for the Android launcher and Android-made
 * in-game overlays. The native game's UI uses Overpass-900 for large/interstate
 * text and Overpass-750 for small text, with #2896FF focus highlighting.
 * Original Halo bitmap UI is not recolored by this class. */
final class HaloTheme {
    static final int FOCUS = Color.rgb(40, 150, 255);
    static final int FOCUS_TEXT = Color.rgb(139, 201, 255);
    static final int TEXT = Color.rgb(234, 242, 250);
    static final int MUTED = Color.rgb(174, 193, 210);
    static final int BORDER = Color.rgb(74, 122, 166);
    static final int WARNING = Color.rgb(255, 182, 106);
    static final int BACKGROUND_TOP = Color.rgb(8, 21, 36);
    static final int BACKGROUND_CENTER = Color.rgb(17, 40, 65);
    static final int BACKGROUND_BOTTOM = Color.rgb(7, 17, 32);
    static final int PANEL_TOP = Color.rgb(22, 47, 74);
    static final int PANEL_BOTTOM = Color.rgb(10, 27, 46);
    static final int CONTROL_TOP = Color.rgb(31, 66, 100);
    static final int CONTROL_BOTTOM = Color.rgb(16, 40, 65);
    static final int HIGHLIGHT_TEXT = Color.rgb(7, 21, 36);

    static final ColorStateList CHECK_TINT = new ColorStateList(new int[][]{
        {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, StateSet.WILD_CARD
    }, new int[]{BORDER, FOCUS, MUTED});

    private static volatile Typeface small;
    private static volatile Typeface large;

    private HaloTheme() { }

    static Typeface face(Context context, boolean heading) {
        Typeface cached = heading ? large : small;
        if (cached != null) return cached;
        synchronized (HaloTheme.class) {
            cached = heading ? large : small;
            if (cached == null) {
                try {
                    cached = Typeface.createFromAsset(context.getAssets(),
                        heading ? "fonts/Overpass-900.ttf" :
                                  "fonts/Overpass-750.ttf");
                } catch (RuntimeException unavailable) {
                    cached = Typeface.create("sans-serif-condensed",
                        heading ? Typeface.BOLD : Typeface.NORMAL);
                }
                if (heading) large = cached;
                else small = cached;
            }
        }
        return cached;
    }

    static void font(TextView view, boolean heading) {
        view.setTypeface(face(view.getContext(), heading));
    }

    /** Bright blue means this tab or section is selected. A tap gives only
     * a brief light ripple, including on one-shot actions such as Play. */
    static void menuButtonColors(Button button) {
        int[] disabled = {-android.R.attr.state_enabled};
        int[] selected = {android.R.attr.state_selected};
        float density = button.getResources().getDisplayMetrics().density;
        StateListDrawable fill = new StateListDrawable();
        fill.addState(disabled, surface(PANEL_BOTTOM, PANEL_BOTTOM, PANEL_TOP, 3, density));
        fill.addState(selected, surface(FOCUS, FOCUS, FOCUS_TEXT, 3, density));
        fill.addState(StateSet.WILD_CARD, surface(CONTROL_TOP, CONTROL_BOTTOM, BORDER, 3, density));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), fill, null));
        button.setTextColor(new ColorStateList(new int[][]{disabled, selected, StateSet.WILD_CARD},
            new int[]{MUTED, HIGHLIGHT_TEXT, TEXT}));
    }

    static GradientDrawable surface(int top, int bottom, int border, float radiusDp,
                                    float density) {
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        bg.setCornerRadius(radiusDp * density);
        bg.setStroke(Math.max(1, Math.round(density)), border);
        return bg;
    }
}
