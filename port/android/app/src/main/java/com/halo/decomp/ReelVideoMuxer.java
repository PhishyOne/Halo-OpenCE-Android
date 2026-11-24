package com.halo.decomp;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Video-only, lossless-track (no extra encoder) MP4 selection of actual
 * recorded gameplay frames. A sync frame may add up to ~2 seconds of
 * pre-roll. Original recording stays untouched if anything fails. */
final class ReelVideoMuxer {
    private static final int MAX_SAMPLE = 2 * 1024 * 1024;

    private ReelVideoMuxer() { }

    static boolean build(File source, File destination,
                         List<ReelSessionIndex.Clip> windows) throws IOException {
        if (source == null || !source.isFile() || source.length() > ReelSessionIndex.SOURCE_LIMIT ||
            windows == null || windows.isEmpty())
            return false;
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory())
            throw new IOException("Reel folder unavailable");
        if (!destination.getName().matches("reel-[0-9a-f]{32}\\.mp4"))
            throw new IOException("Unsafe output reel name");
        File partial = new File(parent, destination.getName() + ".pending");
        if (partial.exists() && !partial.delete())
            throw new IOException("Previous partial reel cannot be discarded");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false, success = false;
        long lastPts = -1, outputStartUs = 0;
        int frameCount = 0;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int inputTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    inputTrack = i;
                    format = candidate;
                    break;
                }
            }
            if (format == null || inputTrack < 0)
                throw new IOException("Source MP4 contains no video track");
            extractor.selectTrack(inputTrack);
            muxer = new MediaMuxer(partial.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(format);
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_SAMPLE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (ReelSessionIndex.Clip window : windows) {
                if (window.endUs <= window.startUs) continue;
                extractor.seekTo(window.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long clipFirstUs = -1;
                long clipLastUs = -1;
                for (;;) {
                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime < 0 || sampleTime > window.endUs) break;
                    int sampleTrack = extractor.getSampleTrackIndex();
                    if (sampleTrack != inputTrack) {
                        if (!extractor.advance()) break;
                        continue;
                    }
                    buffer.clear();
                    int bytes = extractor.readSampleData(buffer, 0);
                    if (bytes <= 0) break;
                    if (bytes > MAX_SAMPLE)
                        throw new IOException("Unexpectedly large encoded video frame");
                    if (clipFirstUs < 0) {
                        // MediaMuxer output must begin with a sync frame.
                        if ((extractor.getSampleFlags() &
                            MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                            if (!extractor.advance()) break;
                            continue;
                        }
                        clipFirstUs = sampleTime;
                    }
                    long pts = outputStartUs + sampleTime - clipFirstUs;
                    if (pts <= lastPts) pts = lastPts + 1;
                    buffer.position(0);
                    buffer.limit(bytes);
                    info.set(0, bytes, pts,
                        (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ?
                        MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(outputTrack, buffer, info);
                    lastPts = clipLastUs = pts;
                    frameCount++;
                    if (!extractor.advance()) break;
                }
                if (clipLastUs >= 0) outputStartUs = clipLastUs + 50_000L;
            }
            success = frameCount >= 8;
        } catch (RuntimeException error) {
            throw new IOException("Reel muxing failed", error);
        } finally {
            try {
                if (muxer != null) {
                    if (started) muxer.stop();
                    muxer.release();
                }
            } catch (RuntimeException stopFailed) {
                success = false;
            }
            extractor.release();
            if (!success) partial.delete();
        }
        if (!success) return false;
        if (partial.length() < 2048 || partial.length() > 48L * 1024 * 1024) {
            partial.delete();
            throw new IOException("Reel output length is out of bounds");
        }
        // Read back the actual MP4 rather than trusting a successful close.
        MediaExtractor check = new MediaExtractor();
        try {
            check.setDataSource(partial.getAbsolutePath());
            if (check.getTrackCount() == 0)
                throw new IOException("Reel has no media tracks");
        } finally {
            check.release();
        }
        try {
            Files.move(partial.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(partial.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
