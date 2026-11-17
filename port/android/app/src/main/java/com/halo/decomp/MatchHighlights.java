package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded event-timeline markers, NOT video recording or gameplay replay.
 * The local match-id joins each event to a durable HC1/HC2 career record.
 * Files are app-private; only complete newline-terminated events are read. */
final class MatchHighlights {
    static final String FILENAME = "career-highlights-v1.log";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    static final class Moment {
        final String matchId, map, type;
        final int tick, score, start, end;
        Moment(String[] p) {
            matchId = p[1]; map = p[2];
            tick = num(p[3], 0, 8640000);
            type = p[4];
            score = num(p[5], 0, 100);
            start = num(p[6], 0, 8640000);
            end = num(p[7], 0, 8640000);
            if (start > tick || tick > end)
                throw new IllegalArgumentException("Bad highlight time window");
        }
        String label() {
            switch (type) {
                case "STREAK_10": return "10-kill streak";
                case "STREAK_5": return "5-kill streak";
                case "STREAK_3": return "3-kill streak";
                case "FLAG_CAPTURE": return "Flag capture";
                case "VICTORY": return "Match victory";
                case "HUMAN_KILL": return "Human opponent defeated";
                case "BOT_KILL": return "Bot defeated";
                default: return "Match moment";
            }
        }
        String timing() {
            return String.format(java.util.Locale.US,
                "%02d:%02d  (clip window %02d:%02d–%02d:%02d)",
                tick / 1800, (tick / 30) % 60,
                start / 1800, (start / 30) % 60,
                end / 1800, (end / 30) % 60);
        }
    }

    private MatchHighlights() { }

    private static int num(String value, int lower, int upper) {
        if (value.length() == 0 || value.length() > 9 ||
            !value.matches("[0-9]+"))
            throw new IllegalArgumentException("Bad highlight number");
        int n = Integer.parseInt(value);
        if (n < lower || n > upper)
            throw new IllegalArgumentException("Highlight number out of range");
        return n;
    }

    static Moment parse(String line) {
        if (line == null || line.length() > 199 || line.length() < 60)
            throw new IllegalArgumentException("Invalid highlight record length");
        String[] p = line.split("\\|", -1);
        if (p.length != 8 || !"HLM1".equals(p[0]) ||
            !p[1].matches("[0-9a-f]{32}") ||
            !p[2].matches("[a-z0-9_-]{1,47}") ||
            !(p[4].equals("BOT_KILL") || p[4].equals("HUMAN_KILL") ||
              p[4].equals("STREAK_3") || p[4].equals("STREAK_5") ||
              p[4].equals("STREAK_10") || p[4].equals("FLAG_CAPTURE") ||
              p[4].equals("VICTORY")))
            throw new IllegalArgumentException("Invalid highlight type/identity");
        return new Moment(p);
    }

    static List<Moment> load(File appDirectory) throws IOException {
        if (appDirectory == null) return Collections.emptyList();
        File source = new File(appDirectory, FILENAME);
        if (!source.isFile()) return Collections.emptyList();
        if (source.length() > MAX_BYTES || source.length() < 0)
            throw new IOException("Highlight markers reached their storage cap.");
        byte[] bytes = Files.readAllBytes(source.toPath());
        if (bytes.length > MAX_BYTES)
            throw new IOException("Highlight markers changed during read.");
        int end = 0;
        for (int i = bytes.length - 1; i >= 0; --i) {
            if (bytes[i] == '\n') {end = i + 1; break;}
        }
        String raw = new String(bytes, 0, end, StandardCharsets.UTF_8);
        List<Moment> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (String text : raw.split("\\n")) {
            if (text.isEmpty()) continue;
            String line = text.endsWith("\r") ? text.substring(0, text.length()-1) : text;
            try {
                Moment m = parse(line);
                String key = m.matchId + ":" + m.tick + ":" + m.type;
                int n = counts.containsKey(m.matchId) ? counts.get(m.matchId) : 0;
                if (n < 3 && seen.add(key)) {
                    events.add(m);
                    counts.put(m.matchId, n + 1);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore a damaged row without affecting valid career records.
            }
        }
        return events;
    }

    static List<Moment> forMatch(CareerRecords.Match match, List<Moment> all) {
        List<Moment> result = new ArrayList<>();
        if (match == null || all == null) return result;
        for (Moment moment : all) {
            if (moment.matchId.equals(match.id) && moment.map.equals(match.map))
                result.add(moment);
        }
        result.sort(Comparator.comparingInt(m -> m.tick));
        return result;
    }

    static List<Moment> mostRecent(CareerRecords.Career career, List<Moment> all) {
        if (career == null || all == null) return Collections.emptyList();
        for (int i = career.matches.size() - 1; i >= 0; i--) {
            List<Moment> found = forMatch(career.matches.get(i), all);
            if (!found.isEmpty()) return found;
        }
        return Collections.emptyList();
    }
}
