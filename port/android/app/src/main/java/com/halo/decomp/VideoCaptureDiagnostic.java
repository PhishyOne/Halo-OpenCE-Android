package com.halo.decomp;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.Locale;

/** Read-only, bounded thumbnail inspection of existing private MP4s.
 * Helps separate black original capture, bad reel selection, and
 * in-launcher VideoView playback errors. Never exports thumbnails. */
final class VideoCaptureDiagnostic {
    private static final int MAX_SIZE = ReelSessionIndex.SOURCE_LIMIT;
    private static final int THUMB_WIDTH = 64, THUMB_HEIGHT = 36;
    private static final int SAMPLES = 5;

    static final class Result {
        final String status, detail;
        final int decoded, black, durationSeconds;
        Result(String status, String detail, int decoded, int black, int seconds) {
            this.status = status; this.detail = detail;
            this.decoded = decoded; this.black = black;
            this.durationSeconds = seconds;
        }
        String summary() {
            return status + "\n" + detail;
        }
    }

    private VideoCaptureDiagnostic() { }

    static Result inspect(File file) {
        if (file == null || !file.isFile() || file.length() < 2048 ||
            file.length() > MAX_SIZE || !HaloVideoFiles.validName(file.getName()))
            return new Result("UNAVAILABLE", "Not a valid saved Halo MP4.", 0, 0, 0);
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        int decoded = 0, black = 0, duration = 0;
        try {
            reader.setDataSource(file.getAbsolutePath());
            String raw = reader.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (raw == null) return new Result("UNKNOWN",
                "Couldn't read recorded duration.", 0, 0, 0);
            long totalMs = Long.parseLong(raw);
            if (totalMs < 300 || totalMs > 21 * 60 * 1000L)
                return new Result("UNKNOWN", "Duration outside bounded range.",
                    0, 0, 0);
            duration = (int)(totalMs / 1000L);
            for (int i = 0; i < SAMPLES; i++) {
                long atUs = (totalMs * (i * 2L + 1L) * 1000L) /
                    (SAMPLES * 2L);
                Bitmap frame = null;
                try {
                    frame = reader.getScaledFrameAtTime(atUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        THUMB_WIDTH, THUMB_HEIGHT);
                    if (frame == null || frame.getWidth() < 8 ||
                        frame.getHeight() < 8) continue;
                    int[] pixels = new int[frame.getWidth() * frame.getHeight()];
                    frame.getPixels(pixels, 0, frame.getWidth(),
                        0, 0, frame.getWidth(), frame.getHeight());
                    decoded++;
                    if (VideoFrameBrightness.nearlyBlack(pixels)) black++;
                } catch (RuntimeException decodeError) {
                    // Some hardware decoders cannot return a thumbnail.
                    // Never mistake a failed decode for a black frame.
                } finally {
                    if (frame != null) frame.recycle();
                }
            }
        } catch (Exception failed) {
            return new Result("UNKNOWN", "Video decode or metadata check failed.",
                decoded, black, duration);
        } finally {
            try { reader.release(); }
            catch (java.io.IOException ignored) {
                // Failure releasing metadata resources does not establish
                // whether decoded frames contained actual gameplay.
            }
        }
        String detail = String.format(Locale.US,
            "Length %d sec. Decoded %d of %d samples; nearly black %d.",
            duration, decoded, SAMPLES, black);
        if (decoded < 3)
            return new Result("UNKNOWN", detail +
                "\nToo few frames decoded to diagnose.", decoded, black, duration);
        if (black == decoded)
            return new Result("LIKELY BLACK VIDEO", detail +
                "\nAll sampled frames are almost black. Preserve the original.",
                decoded, black, duration);
        return new Result("PICTURE DETECTED", detail +
            "\nAt least one sampled frame contains nonblack image data.",
            decoded, black, duration);
    }
}
