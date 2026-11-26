package com.halo.decomp;

/** Validate the production geometry policy for common and constrained windows. */
public final class PlayDockPolicyTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        int w = 610, h = 200, edge = 7, action = 44;
        int aw = PlayDockPolicy.width(w, edge);
        check(aw == 199, "one-third inner width");
        check(PlayDockPolicy.overlay(h, 194, 130, action, edge, 285, aw, true, false),
            "left column can extend down while right has room");
        check(PlayDockPolicy.overlay(h, 200, 142, action, edge, 285, aw, true, false),
            "exact safe boundary");
        check(!PlayDockPolicy.overlay(h, 200, 143, action, edge, 285, aw, true, false),
            "one pixel of overlap must dock");
        check(!PlayDockPolicy.overlay(h, 201, 90, action, edge, 285, aw, true, false),
            "scrolling content must dock");
        check(!PlayDockPolicy.overlay(h, 130, 90, action, edge, 285, aw, true, true),
            "warning or Android settings action must dock");
        check(!PlayDockPolicy.overlay(h, 130, 90, action, edge, 285, aw, false, false),
            "full-width invite or unsupported layout must dock");
        check(!PlayDockPolicy.overlay(h, 130, 90, action, edge, 198, aw, true, false),
            "action wider than safe column must dock");
        check(!PlayDockPolicy.overlay(58, 40, 10, action, edge, 285, aw, true, false),
            "very short keyboard window");
        check(!PlayDockPolicy.overlay(h, 130, 90, 0, edge, 285, aw, true, false),
            "zero-size action");
        check(PlayDockPolicy.reservedHeight(h, action, 0, edge) == 58, "ordinary reserved strip");
        check(PlayDockPolicy.reservedHeight(h, action, 88, edge) == 102, "taller status kept visible");
        check(PlayDockPolicy.reservedHeight(40, action, 88, edge) == 40, "never negative scroll height");
        for (int width = 240; width <= 1200; width += 7) {
            int a = PlayDockPolicy.width(width, edge);
            check(a >= (width - 2*edge)/3 && a <= (width-2*edge)/3+1, "responsive width");
            int statusWidth = width - a - 3*edge;
            check(edge + statusWidth + edge == width - edge - a, "horizontal gap");
        }
        System.out.println("PASS PlayDockPolicyTest: " + checks + " checks");
    }
}
