package com.halo.decomp;

/** Pixel geometry for the action dock; independent of Android for tests.
 * Overlay is an optimization, never a reason to cover a setting. */
final class PlayDockPolicy {
    private PlayDockPolicy() { }

    static int width(int viewportWidth, int edge) {
        int usable = Math.max(0, viewportWidth - 2 * edge);
        return (usable + 2) / 3;
    }

    static boolean overlay(int height, int pageBottom, int rightBottom,
            int actionHeight, int edge, int rightWidth, int actionWidth,
            boolean simplePage, boolean hasStatus) {
        return simplePage && !hasStatus && actionWidth > 0 && actionHeight > 0
            && height > actionHeight + 2 * edge
            && pageBottom <= height
            && rightWidth >= actionWidth
            && rightBottom <= height - actionHeight - 2 * edge;
    }

    static int reservedHeight(int height, int actionHeight, int statusHeight, int edge) {
        return Math.min(height, Math.max(actionHeight, statusHeight) + 2 * edge);
    }
}
