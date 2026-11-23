package com.halo.decomp;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.StateSet;
import android.widget.Button;

/** Tiny vector disclosure arrow, not a font glyph or an image asset.
 * Right = collapsed; down = expanded. Uses the button's selected state. */
final class DisclosureChevron extends Drawable {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private boolean expanded;
    private boolean activeButton;
    private boolean cardMode;
    private int alpha = 255;

    DisclosureChevron(Resources resources) {
        float density = resources.getDisplayMetrics().density;
        size = Math.max(12, Math.round(18 * density));
        stroke.setStrokeWidth(Math.max(2f, 2.5f * density));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(HaloTheme.MUTED);
        setBounds(0, 0, size, size);
    }

    static void attach(Button button) {
        DisclosureChevron arrow = new DisclosureChevron(button.getResources());
        button.setCompoundDrawables(null, null, arrow, null);
        button.setCompoundDrawablePadding(Math.round(
            8 * button.getResources().getDisplayMetrics().density));
        arrow.setState(button.getDrawableState());
    }

    void setExpanded(boolean isOpen) {
        if (expanded != isOpen) {
            expanded = isOpen;
            invalidateSelf();
        }
    }

    /** Recycled history rows show the arrow over a dark panel, rather
     * than the bright-blue selected-button background. */
    void setCardExpanded(boolean open) {
        cardMode = true;
        expanded = open;
        activeButton = false;
        invalidateSelf();
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        if (cardMode) return false;
        boolean isSelected = StateSet.stateSetMatches(
            new int[]{android.R.attr.state_selected}, state);
        if (isSelected == activeButton && isSelected == expanded) return false;
        activeButton = isSelected;
        expanded = isSelected;
        invalidateSelf();
        return true;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        float cx = bounds.exactCenterX(), cy = bounds.exactCenterY();
        float arm = Math.min(bounds.width(), bounds.height()) * .23f;
        Path chevron = new Path();
        if (expanded) {
            chevron.moveTo(cx - arm, cy - arm * .40f);
            chevron.lineTo(cx, cy + arm * .65f);
            chevron.lineTo(cx + arm, cy - arm * .40f);
        } else {
            chevron.moveTo(cx - arm * .35f, cy - arm);
            chevron.lineTo(cx + arm * .65f, cy);
            chevron.lineTo(cx - arm * .35f, cy + arm);
        }
        stroke.setColor(cardMode ? (expanded ? HaloTheme.FOCUS_TEXT :
            HaloTheme.MUTED) : (activeButton ? HaloTheme.HIGHLIGHT_TEXT :
            HaloTheme.MUTED));
        stroke.setAlpha(alpha);
        canvas.drawPath(chevron, stroke);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int value) {
        alpha = value;
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        stroke.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
