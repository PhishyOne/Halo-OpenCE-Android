package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Java, read-only bridge from existing HLM1 moments and HC1/2/3
 * match timestamps to an opt-in screen recording's real MP4 timeline.
 * Epoch clock alignment is approximate until real-device validation. */
final class ReelSessionIndex {
    static final String INDEX = "video-sessions-v1.log";
    static final String REEL_DIRECTORY = "reels";
    static final long MAX_RECORDING_MS = 20 * 60_000L;
    static final int SOURCE_LIMIT = 160 * 1024 * 1024;
    static final int RETAIN_REELS = 3;
    static final int INDEX_LIMIT = 16 * 1024;

    static final class Session {
        final String filename;
        final long startMs, endMs;
        final int width, height;
        Session(String name, long start, long end, int w, int h) {
            filename = name;
            startMs = start;
            endMs = end;
            width = w; height = h;
        }
        File source(File dataRoot) {
            return new File(new File(dataRoot, REEL_DIRECTORY), filename);
        }
        long durationMs() { return endMs - startMs; }
    }

    static final class Clip {
        final long startUs, endUs;
        Clip(long start, long end) { startUs = start; endUs = end; }
    }

    private ReelSessionIndex() { }

    static boolean safeFileName(String name) {
        return name != null && name.matches("session-[0-9]{13}\\.mp4");
    }

    static String format(Session s) {
        return "RS1|" + s.filename + "|" + s.startMs + "|" + s.endMs +
            "|" + s.width + "|" + s.height + "\n";
    }

    static Session parse(String line) {
        if (line == null || line.length() > 150)
            throw new IllegalArgumentException("Invalid session entry");
        String[] parts = line.split("\\|", -1);
        if (parts.length != 6 || !"RS1".equals(parts[0]) ||
                !safeFileName(parts[1]))
            throw new IllegalArgumentException("Unsupported video session");
        long start, end;
        int w, h;
        try {
            start = Long.parseLong(parts[2]);
            end = Long.parseLong(parts[3]);
            w = Integer.parseInt(parts[4]);
            h = Integer.parseInt(parts[5]);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("Bad recording metadata", bad);
        }
        if (start < 1500000000000L || end < start + 1000 ||
            end - start > MAX_RECORDING_MS + 5000L ||
            w < 160 || w > 1920 || h < 144 || h > 1080 ||
            (w & 1) != 0 || (h & 1) != 0)
            throw new IllegalArgumentException("Recording metadata out of bounds");
        return new Session(parts[1], start, end, w, h);
    }

    static List<Session> load(File appRoot) throws IOException {
        if (appRoot == null) return Collections.emptyList();
        File index = new File(new File(appRoot, REEL_DIRECTORY), INDEX);
        if (!index.isFile()) return Collections.emptyList();
        if (index.length() < 0 || index.length() > INDEX_LIMIT)
            throw new IOException("Video session index too large.");
        byte[] raw = Files.readAllBytes(index.toPath());
        if (raw.length > INDEX_LIMIT)
            throw new IOException("Video index changed while reading.");
        int lastComplete = 0;
        for (int i = raw.length - 1; i >= 0; i--)
            if (raw[i] == '\n') { lastComplete = i + 1; break; }
        List<Session> sessions = new ArrayList<>();
        String text = new String(raw, 0, lastComplete, StandardCharsets.UTF_8);
        for (String line : text.split("\\n")) {
            if (line.isEmpty()) continue;
            try {
                Session s = parse(line.endsWith("\r") ?
                    line.substring(0, line.length()-1) : line);
                File file = s.source(appRoot);
                if (file.isFile() && file.length() > 2048 &&
                    file.length() <= SOURCE_LIMIT)
                    sessions.add(s);
            } catch (IllegalArgumentException bad) {
                // Preserve unrelated entries and ignore an invalid session.
            }
        }
        return sessions;
    }

    /** Convert game-tick windows to wall-time source offsets, keeping
     * only highlights that fit a completed video capture session.
     * Real latency and pauses can shift the result; retain original. */
    static List<Clip> windows(Session session, CareerRecords.Match match,
                             List<MatchHighlights.Moment> moments) {
        List<Clip> clipped = new ArrayList<>();
        if (match == null || session == null || moments == null ||
            match.cooperative() || match.incomplete || match.seconds < 1)
            return clipped;
        // Native career timestamp is local time() at postgame; start is
        // approximated from the match's own duration, not the lobby start.
        long matchStartMs = match.epoch * 1000L - match.seconds * 1000L;
        for (MatchHighlights.Moment m : moments) {
            if (!m.matchId.equals(match.id) || !m.map.equals(match.map))
                continue;
            long startMs = matchStartMs + m.start * 1000L / 30L;
            long endMs = matchStartMs + m.end * 1000L / 30L;
            long startUs = Math.max(0, startMs - session.startMs) * 1000L;
            long endUs = Math.min(session.durationMs(),
                endMs - session.startMs) * 1000L;
            if (endUs - startUs < 500_000L) continue;
            if (startUs > session.durationMs() * 1000L) continue;
            clipped.add(new Clip(startUs, endUs));
        }
        clipped.sort((a, b) -> Long.compare(a.startUs, b.startUs));
        List<Clip> merged = new ArrayList<>();
        for (Clip c : clipped) {
            if (merged.isEmpty()) merged.add(c);
            else {
                Clip previous = merged.get(merged.size()-1);
                if (c.startUs <= previous.endUs + 100_000L)
                    merged.set(merged.size()-1,
                        new Clip(previous.startUs, Math.max(previous.endUs, c.endUs)));
                else merged.add(c);
            }
        }
        return merged;
    }

    static String reelName(CareerRecords.Match match) {
        if (match == null || !match.id.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid match");
        return "reel-" + match.id + ".mp4";
    }
}
