package com.halo.decomp;

import java.util.Arrays;

/** Android-free checks for likely-black thumbnail classification. */
public final class VideoFrameBrightnessTest {
    private static int checks;
    private static void check(boolean value, String what) {
        checks++;
        if (!value) throw new AssertionError(what);
    }
    public static void main(String[] args) {
        int[] pixels = new int[64 * 36];
        Arrays.fill(pixels, 0xff000000);
        check(VideoFrameBrightness.nearlyBlack(pixels),
            "solid black frame");
        Arrays.fill(pixels, 0xff101010);
        check(VideoFrameBrightness.nearlyBlack(pixels),
            "near-black codec/encoder frame");
        Arrays.fill(pixels, 0xff282828);
        check(!VideoFrameBrightness.nearlyBlack(pixels),
            "dim but visible scene not classified as black");
        Arrays.fill(pixels, 0xff000000);
        for (int i = 0; i < pixels.length / 100; i++)
            pixels[i] = 0xffffffff;
        check(VideoFrameBrightness.nearlyBlack(pixels),
            "tiny system icon alone not proof of visible gameplay");
        for (int i = 0; i < pixels.length / 10; i++)
            pixels[i] = 0xff446688;
        check(!VideoFrameBrightness.nearlyBlack(pixels),
            "real scene detail classed as visible");
        boolean rejected = false;
        try { VideoFrameBrightness.nearlyBlack(new int[12]); }
        catch (IllegalArgumentException correct) { rejected = true; }
        check(rejected, "too-small thumbnail cannot be trusted");
        rejected = false;
        try { VideoFrameBrightness.nearlyBlack(null); }
        catch (IllegalArgumentException correct) { rejected = true; }
        check(rejected, "missing thumbnail cannot be trusted");
        System.out.println("PASS VideoFrameBrightnessTest: " + checks + " checks");
    }
}
