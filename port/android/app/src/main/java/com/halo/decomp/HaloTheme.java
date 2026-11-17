package com.halo.decomp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    static GradientDrawable surface(int top, int bottom, int border, float radiusDp,
                                    float density) {
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        bg.setCornerRadius(radiusDp * density);
        bg.setStroke(Math.max(1, Math.round(density)), border);
        return bg;
    }
}
