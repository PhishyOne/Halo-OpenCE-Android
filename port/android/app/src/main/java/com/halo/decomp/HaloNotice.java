package com.halo.decomp;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Nonmodal Halo HUD notices; never create a stock system toast from an
 * Activity. System-owned permission UI and service-only notifications are
 * intentionally separate. */
final class HaloNotice {
    static final int LENGTH_SHORT = 0, LENGTH_LONG = 1;
    private static volatile java.lang.ref.WeakReference<Activity> foreground =
        new java.lang.ref.WeakReference<>(null);
    private final Context context;
    private final CharSequence message;
    private final int duration;
    private HaloNotice(Context context, CharSequence message, int duration) {
        this.context = context; this.message = message; this.duration = duration;
    }
    static HaloNotice makeText(Context context, CharSequence message, int duration) {
        return new HaloNotice(context, message, duration);
    }
    static Activity activity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity)context;
            if (!(context instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper)context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }
    static void foreground(Activity activity) {
        foreground = new java.lang.ref.WeakReference<>(activity);
    }

    static void background(Activity activity) {
        if (foreground.get() == activity)
            foreground = new java.lang.ref.WeakReference<>(null);
    }

    static boolean showFromService(CharSequence message) {
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        makeText(activity, message, LENGTH_LONG).show();
        return true;
    }

    void show() {
        Activity a = activity(context);
        if (a == null) {
            android.util.Log.i("HaloNotice", String.valueOf(message));
            return;
        }
        a.runOnUiThread(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            android.view.Window window = HaloWindow.activeWindow(a);
            if (window == null) return;
            View decor = window.getDecorView();
            if (!(decor instanceof FrameLayout)) return;
            FrameLayout frame = (FrameLayout)decor;
            String tag = "halo-hud-notice";
            View old = frame.findViewWithTag(tag);
            if (old != null && old.getParent() == frame) frame.removeView(old);
            float density = a.getResources().getDisplayMetrics().density;
            TextView text = new TextView(a);
            text.setTag(tag);
            text.setText(message);
            text.setTextColor(HaloTheme.TEXT);
            text.setTextSize(13);
            text.setGravity(Gravity.CENTER);
            text.setPadding((int)(16*density), (int)(8*density), (int)(16*density), (int)(8*density));
            text.setMaxWidth((int)(Math.min(a.getResources().getDisplayMetrics().widthPixels / density - 32, 640)*density));
            text.setBackground(HaloTheme.surface(HaloTheme.CONTROL_TOP, HaloTheme.PANEL_BOTTOM,
                HaloTheme.FOCUS, 4, density));
            text.setElevation(8*density);
            text.setClickable(false);
            text.setFocusable(false);
            text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            HaloTheme.font(text, true);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            p.bottomMargin = (int)(20*density);
            frame.addView(text, p);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (text.getParent() == frame) frame.removeView(text);
            }, duration == LENGTH_LONG ? 3500 : 2200);
        });
    }
}
