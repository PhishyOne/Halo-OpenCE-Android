package com.halo.decomp;

/** Pure-Java video thumbnail check. A dark frame is only evidence that
 * a sample is nearly black, never proof that capture failed permanently.
 * A few glowing pixels must not falsely count as gameplay footage. */
final class VideoFrameBrightness {
    private VideoFrameBrightness() { }

    static boolean nearlyBlack(int[] colors) {
        if (colors == null || colors.length < 64)
            throw new IllegalArgumentException("Video thumbnail is too small");
        int visible = 0;
        // Two percent visibly nonblack pixels distinguishes a scene from
        // codec black with a stray UI/status pixel. This remains heuristic.
        int allowance = Math.max(2, colors.length / 50);
        for (int argb : colors) {
            int red = (argb >>> 16) & 0xff;
            int green = (argb >>> 8) & 0xff;
            int blue = argb & 0xff;
            if (Math.max(red, Math.max(green, blue)) >= 40 &&
                ++visible > allowance) return false;
        }
        return true;
    }
}
