package com.halo.decomp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Host-only multiplayer pregame bot count.
 *
 * Unlike the native XML lobby buttons, this small overlay is a real Android
 * touch target above SDL and the fullscreen game-controller overlay. Its
 * source of truth is a short-lived authorization published by the native
 * pregame menu. No permission is inferred from Android preferences.
 */
public final class LobbyBotControls extends LinearLayout {
    private static final long POLL_MS = 125;
    private static native int nativeStatus();
    private static native void nativeAdjust(int direction);

    private final TextView count;
    private int shownStatus = -1;
    private boolean attached;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            int status = nativeStatus();
            if (status != shownStatus) {
                shownStatus = status;
                setVisibility(status >= 0 ? View.VISIBLE : View.GONE);
                if (status >= 0) {
                    int value = status & 255;
                    boolean lan = (status & 0x100) != 0;
                    count.setText((lan ? "LAN " : "") + "BOTS  " + value +
                        " / " + (lan ? "16" : "127"));
                }
            }
            postDelayed(this, POLL_MS);
        }
    };

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable surface(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(6));
        shape.setStroke(dp(1), Color.rgb(88, 128, 151));
        return shape;
    }

    private Button adjustButton(String symbol, int direction) {
        Button button = new Button(getContext());
        button.setText(symbol);
        button.setTextSize(22);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackground(surface(Color.rgb(32, 63, 77)));
        button.setMinWidth(dp(49));
        button.setMinHeight(dp(48));
        button.setContentDescription(direction < 0 ?
            "Fewer lobby bots" : "More lobby bots");
        button.setOnClickListener(v -> {
            if (shownStatus >= 0)
                nativeAdjust(direction);
        });
        return button;
    }

    public LobbyBotControls(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(7), dp(4), dp(7), dp(4));
        setBackground(surface(Color.rgb(13, 30, 42)));

        Button fewer = adjustButton("−", -1);
        addView(fewer, new LayoutParams(dp(50), dp(49)));

        count = new TextView(context);
        count.setText("BOTS");
        count.setTextColor(Color.rgb(214, 239, 247));
        count.setTextSize(15);
        count.setGravity(Gravity.CENTER);
        count.setSingleLine(true);
        count.setPadding(dp(10), 0, dp(10), 0);
        addView(count, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(49)));

        Button more = adjustButton("+", 1);
        addView(more, new LayoutParams(dp(50), dp(49)));
        setVisibility(View.GONE);
        setFocusable(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        removeCallbacks(poll);
        post(poll);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(poll);
        shownStatus = -1;
        super.onDetachedFromWindow();
    }
}
