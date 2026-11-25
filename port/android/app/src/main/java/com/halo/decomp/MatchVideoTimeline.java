package com.halo.decomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exact match identity and sampled monotonic game-clock to video-clock
 * mapping. Legacy RS1 session recordings remain read-only recovery inputs. */
final class MatchVideoTimeline {
    static final int MAX_POINTS = 2600, MAX_BYTES = 96 * 1024;
    static final int EDGE_TICKS = 45; // up to 1.5s startup/final frame tolerance

    static final class Point {
        final int tick;
        final long ms;
        Point(int tick, long ms) { this.tick = tick; this.ms = ms; }
    }

    static final class Builder {
        final String id;
        final boolean keepFull;
        final long startUptime;
        final List<Point> points = new ArrayList<>();
        boolean reliable = true;
        Builder(String id, boolean keepFull, long startUptime) {
            this.id = id; this.keepFull = keepFull; this.startUptime = startUptime;
        }
        void observe(MatchVideoState state) {
            if (state == null || !id.equals(state.id)) return;
            long ms = state.uptime - startUptime;
            if (ms < -10000 || ms > ReelSessionIndex.MAX_RECORDING_MS + 10000) {
                reliable = false; return;
            }
            if (!points.isEmpty()) {
                Point last = points.get(points.size() - 1);
                if (ms <= last.ms) return; // repeated read of the same heartbeat
                if (state.tick < last.tick) { reliable = false; return; }
                if (ms - last.ms > 3000) reliable = false;
            }
            if (points.size() == MAX_POINTS) { reliable = false; return; }
            points.add(new Point(state.tick, ms));
        }
        MatchVideoTimeline finish(File raw, long duration, boolean normal) {
            List<Point> retained = new ArrayList<>();
            for (Point p : points) if (p.ms <= duration + 2000) retained.add(p);
            return new MatchVideoTimeline(id, duration, normal && reliable,
                keepFull, raw.length(), raw.lastModified(), retained);
        }
    }

    static final class Plan {
        final List<ReelSessionIndex.Clip> windows;
        final boolean allCovered;
        Plan(List<ReelSessionIndex.Clip> windows, boolean allCovered) {
            this.windows = windows; this.allCovered = allCovered;
        }
        boolean mayDiscard(MatchVideoTimeline timeline) {
            return timeline.normal && !timeline.keepFull && allCovered &&
                !windows.isEmpty();
        }
    }

    final String id;
    final long durationMs, bytes, modified;
    final boolean normal, keepFull;
    final List<Point> points;

    MatchVideoTimeline(String id, long durationMs, boolean normal, boolean keepFull,
                       long bytes, long modified, List<Point> points) {
        if (!MatchVideoState.id(id) || durationMs <= 0 ||
            durationMs > ReelSessionIndex.MAX_RECORDING_MS + 5000 ||
            bytes < 2048 || bytes > ReelSessionIndex.SOURCE_LIMIT || modified <= 0 ||
            points == null || points.size() > MAX_POINTS)
            throw new IllegalArgumentException("Invalid recording timeline");
        int tick = -1;
        long offset = Long.MIN_VALUE;
        for (Point p : points) {
            if (p == null || p.tick < 0 || p.tick > 8640000 || p.tick < tick ||
                p.ms <= offset || p.ms < -10000 || p.ms > durationMs + 2000)
                throw new IllegalArgumentException("Invalid clock anchors");
            tick = p.tick; offset = p.ms;
        }
        this.id = id; this.durationMs = durationMs; this.normal = normal;
        this.keepFull = keepFull; this.bytes = bytes; this.modified = modified;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
    }

    static File metadata(File raw) { return new File(raw.getParentFile(), raw.getName() + ".match"); }

    boolean stillSame(File raw) {
        return raw != null && ReelSessionIndex.safeFileName(raw.getName()) &&
            Files.isRegularFile(raw.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            raw.length() == bytes && raw.lastModified() == modified;
    }

    void write(File raw) throws IOException {
        if (!stillSame(raw)) throw new IOException("Recording changed before indexing");
        File sidecar = metadata(raw), tmp = new File(sidecar.getPath() + ".tmp");
        if (Files.isSymbolicLink(tmp.toPath()) || Files.isSymbolicLink(sidecar.toPath()))
            throw new IOException("Unsafe recording metadata path");
        StringBuilder row = new StringBuilder("MV1|").append(id).append('|')
            .append(durationMs).append('|').append(normal ? 1 : 0).append('|')
            .append(keepFull ? 1 : 0).append('|').append(bytes).append('|')
            .append(modified).append('\n');
        for (Point p : points) row.append(p.tick).append('|').append(p.ms).append('\n');
        byte[] encoded = row.toString().getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > MAX_BYTES) throw new IOException("Timeline exceeds limit");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(encoded); out.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static MatchVideoTimeline read(File raw) throws IOException {
        if (raw == null || !ReelSessionIndex.safeFileName(raw.getName())) return null;
        File file = metadata(raw);
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        if (file.length() <= 0 || file.length() > MAX_BYTES)
            throw new IOException("Recording metadata is out of bounds");
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length == 0 || data.length > MAX_BYTES || data[data.length - 1] != '\n')
            throw new IOException("Incomplete recording metadata");
        String[] rows = new String(data, StandardCharsets.US_ASCII).split("\\n");
        if (rows.length > MAX_POINTS + 1) throw new IOException("Too many clock anchors");
        String[] header = rows[0].split("\\|", -1);
        if (header.length != 7 || !"MV1".equals(header[0]) ||
            !header[3].matches("[01]") || !header[4].matches("[01]"))
            throw new IOException("Invalid recording metadata");
        try {
            List<Point> points = new ArrayList<>();
            for (int i = 1; i < rows.length; i++) {
                String[] p = rows[i].split("\\|", -1);
                if (p.length != 2) throw new IllegalArgumentException("Invalid anchor");
                points.add(new Point(Integer.parseInt(p[0]), Long.parseLong(p[1])));
            }
            MatchVideoTimeline result = new MatchVideoTimeline(header[1],
                Long.parseLong(header[2]), "1".equals(header[3]), "1".equals(header[4]),
                Long.parseLong(header[5]), Long.parseLong(header[6]), points);
            if (!result.stillSame(raw)) throw new IOException("Recording changed");
            return result;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid recording timeline", malformed);
        }
    }

    private long rawOffset(int tick) {
        Point previous = points.get(0);
        if (tick <= previous.tick) return previous.ms;
        for (int i = 1; i < points.size(); i++) {
            Point next = points.get(i);
            if (tick <= next.tick) {
                long value = next.tick == previous.tick ? next.ms :
                    previous.ms + (next.ms - previous.ms) * (tick - previous.tick) /
                        (next.tick - previous.tick);
                return value;
            }
            previous = next;
        }
        return points.get(points.size() - 1).ms;
    }

    private long offset(int tick) {
        return Math.max(0, Math.min(durationMs, rawOffset(tick)));
    }

    Plan plan(CareerRecords.Match match, List<MatchHighlights.Moment> moments) {
        List<ReelSessionIndex.Clip> windows = new ArrayList<>();
        if (match == null || !id.equals(match.id) || match.cooperative() ||
            match.incomplete || moments == null || moments.isEmpty() || points.size() < 2)
            return new Plan(windows, false);
        Point first = points.get(0), last = points.get(points.size() - 1);
        boolean covered = first.ms <= 1500 && last.ms >= durationMs - 2000;
        for (MatchHighlights.Moment moment : moments) {
            if (!id.equals(moment.matchId) || !match.map.equals(moment.map)) {
                covered = false; continue;
            }
            // Never claim coverage of an event outside captured game ticks.
            if (moment.tick < first.tick || moment.tick > last.tick ||
                moment.start < first.tick - EDGE_TICKS || moment.end > last.tick + EDGE_TICKS)
                covered = false;
            if (moment.tick < first.tick || moment.tick > last.tick) continue;
            long eventMs = rawOffset(moment.tick);
            if (eventMs < 0 || eventMs > durationMs) {
                covered = false;
                continue;
            }
            long start = offset(moment.start) * 1000, end = offset(moment.end) * 1000;
            if (end - start < 500000) { covered = false; continue; }
            windows.add(new ReelSessionIndex.Clip(start, end));
        }
        windows.sort((a, b) -> Long.compare(a.startUs, b.startUs));
        List<ReelSessionIndex.Clip> merged = new ArrayList<>();
        for (ReelSessionIndex.Clip clip : windows) {
            if (!merged.isEmpty()) {
                ReelSessionIndex.Clip old = merged.get(merged.size() - 1);
                if (clip.startUs <= old.endUs + 100000) {
                    merged.set(merged.size() - 1,
                        new ReelSessionIndex.Clip(old.startUs, Math.max(old.endUs, clip.endUs)));
                    continue;
                }
            }
            merged.add(clip);
        }
        return new Plan(merged, covered && !merged.isEmpty());
    }
}
