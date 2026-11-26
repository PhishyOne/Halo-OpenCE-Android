package com.halo.decomp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** One physical Play button, anchored at the lower right. Measure the actual
 * visible columns before allowing it above the menu. If there is no free
 * space, reserve a disjoint bottom strip; the settings remain scrollable.
 * Leaderboard and Tools use the entire viewport without an action dock. */
final class LauncherActionDock extends FrameLayout {
    private final ScrollView scroll;
    private final LinearLayout content;
    private View footer, status;
    private LinearLayout statusContent, page, right;
    private final int edge, minimumStatusRemainder;
    private int scrollHeight, dockWidth, statusWidth;

    LauncherActionDock(Context context, ScrollView scroll, LinearLayout content) {
        super(context);
        this.scroll = scroll;
        this.content = content;
        float density = getResources().getDisplayMetrics().density;
        edge = Math.max(1, Math.round(7 * density));
        minimumStatusRemainder = Math.round(52 * density);
        addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    void setActions(View footer, ScrollView status, LinearLayout statusContent) {
        this.footer = footer;
        this.status = status;
        this.statusContent = statusContent;
        addView(status, new FrameLayout.LayoutParams(-2, -2));
        // Added last, so only the actual button occupies the top touch layer.
        addView(footer, new FrameLayout.LayoutParams(-2, -2));
        requestLayout();
    }

    void setSetupPage(LinearLayout page, LinearLayout right) {
        if (this.page == page && this.right == right) return;
        this.page = page;
        this.right = right;
        requestLayout();
    }

    private static int visibleChildren(ViewGroup group) {
        int count = 0;
        if (group != null) for (int i = 0; i < group.getChildCount(); i++)
            if (group.getChildAt(i).getVisibility() != View.GONE) count++;
        return count;
    }

    private static int verticalMargins(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams)lp;
            return m.topMargin + m.bottomMargin;
        }
        return 0;
    }

    private static int exact(int n) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, n), MeasureSpec.EXACTLY);
    }
    private static int atMost(int n) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, n), MeasureSpec.AT_MOST);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        scrollHeight = h;
        scroll.measure(exact(w), exact(h));
        if (footer == null || footer.getVisibility() == View.GONE) return;

        dockWidth = PlayDockPolicy.width(w, edge);
        statusWidth = Math.max(0, w - dockWidth - 3 * edge);
        footer.measure(exact(dockWidth), atMost(h));
        boolean hasStatus = status != null && status.getVisibility() != View.GONE &&
            visibleChildren(statusContent) > 0;
        if (status != null)
            status.measure(exact(statusWidth),
                atMost(hasStatus ? Math.max(0, h - minimumStatusRemainder) : 0));

        // Overlay only the known, simple two-column shape. Extra full-width
        // rows (for example a pending invite) or RTL layouts use the dock.
        LinearLayout grid = right != null && right.getParent() instanceof LinearLayout
            ? (LinearLayout)right.getParent() : null;
        boolean simplePage = page != null && page.getParent() == content &&
            page.getVisibility() == View.VISIBLE && right != null &&
            right.getVisibility() == View.VISIBLE && grid != null &&
            grid.getParent() == page && grid.getOrientation() == LinearLayout.HORIZONTAL &&
            grid.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR &&
            visibleChildren(content) == 1 && visibleChildren(page) == 1 &&
            grid.getChildCount() == 2 && grid.getChildAt(1) == right;
        int pageBottom = h + 1, rightBottom = h + 1, rightWidth = 0;
        if (simplePage) {
            int outerPadding = content.getPaddingTop() + content.getPaddingBottom();
            pageBottom = outerPadding + verticalMargins(page) + page.getMeasuredHeight();
            rightBottom = outerPadding + verticalMargins(page) +
                page.getPaddingTop() + page.getPaddingBottom() +
                verticalMargins(grid) + grid.getPaddingTop() + grid.getPaddingBottom() +
                verticalMargins(right) + right.getMeasuredHeight();
            // Dock ends at edge, whereas the column ends at the inside of
            // the setup panel. Account for that difference conservatively.
            int rightInset = content.getPaddingRight() + page.getPaddingRight() +
                grid.getPaddingRight();
            rightWidth = right.getMeasuredWidth() - Math.abs(rightInset - edge);
        }
        if (!PlayDockPolicy.overlay(h, pageBottom, rightBottom,
                footer.getMeasuredHeight(), edge, rightWidth, dockWidth,
                simplePage, hasStatus)) {
            int reserve = PlayDockPolicy.reservedHeight(h, footer.getMeasuredHeight(),
                hasStatus ? status.getMeasuredHeight() : 0, edge);
            scrollHeight = h - reserve;
            scroll.measure(exact(w), exact(scrollHeight));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        scroll.layout(0, 0, w, scrollHeight);
        if (footer == null || footer.getVisibility() == View.GONE) return;
        int actionBottom = Math.max(0, h - edge);
        int actionTop = Math.max(0, actionBottom - footer.getMeasuredHeight());
        footer.layout(w - edge - dockWidth, actionTop, w - edge, actionBottom);
        if (status != null && status.getVisibility() != View.GONE) {
            int statusHeight = status.getMeasuredHeight();
            status.layout(edge, actionBottom - statusHeight,
                edge + statusWidth, actionBottom);
        }
        // Overlay is only allowed when the complete page fits. Remove any
        // residual scroll from a previous smaller viewport.
        if (scrollHeight == h && scroll.getScrollY() != 0) scroll.scrollTo(0, 0);
    }
}
