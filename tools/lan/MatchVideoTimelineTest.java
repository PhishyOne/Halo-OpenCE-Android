package com.halo.decomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.io.File;
import java.io.IOException;

/** Pure-JDK behavioral tests; never touches actual user recordings. */
public final class MatchVideoTimelineTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final String ID = "fedcba9876543210fedcba9876543210";
    static final String OTHER = "1234567890abcdef1234567890abcdef";
    static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    static MatchVideoState state(String token, String id, String mode, int tick, long now) {
        return MatchVideoState.parse("VM1|" + token + "|" + id + "|" + mode +
            "|" + tick + "|" + now + "\n");
    }

    static MatchHighlights.Moment moment(String id, int tick, int start, int end) {
        return MatchHighlights.parse("HLM1|" + id + "|bloodgulch|" + tick +
            "|STREAK_3|52|" + start + "|" + end);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("halo-match-video-test-");
        try {
            File root = directory.toFile();
            check(!MatchVideoState.foreground(root, TOKEN), "unarmed starts idle");
            MatchVideoState.writeForeground(root, TOKEN, true);
            check(MatchVideoState.foreground(root, TOKEN), "consented foreground");
            check(!MatchVideoState.foreground(root, OTHER), "other consent rejected");
            MatchVideoState.writeForeground(root, TOKEN, false);
            check(!MatchVideoState.foreground(root, TOKEN), "background disarms capture");
            MatchVideoState good = state(TOKEN, ID, "A", 30, 10000);
            check(good.active && good.fresh(TOKEN, 10100, 9000), "fresh active match");
            check(!good.fresh(OTHER, 10100, 9000), "wrong launch token");
            check(!good.fresh(TOKEN, 13000, 9000), "stale match rejected");
            check(!good.fresh(TOKEN, 5000, 4000), "future timestamp rejected");
            check(state(TOKEN, ID, "I", 30, 10000) != null, "idle parses");
            check(!state(TOKEN, ID, "I", 30, 10000).active, "idle is not playable");
            check(state(TOKEN, "../invalid", "A", 30, 10000) == null, "unsafe ID rejected");
            check(state(TOKEN, ID, "X", 30, 10000) == null, "unknown state rejected");
            check(state(TOKEN, ID, "A", -1, 10000) == null, "negative tick rejected");
            check(MatchVideoState.parse("VM1|" + TOKEN + "|" + ID + "|A|1|10000") == null,
                "torn state rejected");

            Path reels = Files.createDirectory(directory.resolve("reels"));
            File raw = Files.write(reels.resolve("session-1795000000000.mp4"), new byte[4096]).toFile();
            Files.writeString(directory.resolve(CareerRecords.LOG_NAME),
                "HC3|" + ID + "|1795000060|2|bloodgulch|5|3|2|2|1|1|20|3|0|0|0|0|0|0|0|0|0|0|0|0|0\n");
            CareerRecords.Match match = CareerRecords.load(root).matches.get(0);
            MatchVideoTimeline.Builder builder = new MatchVideoTimeline.Builder(ID, false, 10000);
            for (int i = 0; i <= 20; i++)
                builder.observe(state(TOKEN, ID, i == 20 ? "I" : "A", i * 30, 10000 + i * 1000));
            MatchVideoTimeline timeline = builder.finish(raw, 20000, true);
            timeline.write(raw);
            timeline = MatchVideoTimeline.read(raw);
            check(timeline != null && timeline.stillSame(raw), "persisted timeline matches raw");
            MatchVideoTimeline.Plan plan = timeline.plan(match, List.of(moment(ID, 300, 150, 390)));
            check(plan.windows.size() == 1, "one exact-ID interval");
            check(plan.windows.get(0).startUs == 5000000 && plan.windows.get(0).endUs == 13000000,
                "correct game-clock conversion");
            check(plan.mayDiscard(timeline), "normal complete verified-plan eligibility");
            check(!timeline.plan(match, List.of()).mayDiscard(timeline), "no highlights keep raw");
            check(!timeline.plan(match, List.of(moment(OTHER, 300, 150, 390))).mayDiscard(timeline),
                "other match cannot authorize cleanup");
            check(!timeline.plan(match, List.of(moment(ID, 900, 750, 990))).mayDiscard(timeline),
                "event outside captured range cannot authorize cleanup");
            MatchVideoTimeline kept = new MatchVideoTimeline(ID, 20000, true, true,
                raw.length(), raw.lastModified(), timeline.points);
            check(!kept.plan(match, List.of(moment(ID, 300, 150, 390))).mayDiscard(kept),
                "keep full option wins");
            MatchVideoTimeline interrupted = builder.finish(raw, 20000, false);
            check(!interrupted.plan(match, List.of(moment(ID, 300, 150, 390))).mayDiscard(interrupted),
                "partial match never auto-deleted");
            MatchVideoTimeline.Builder gap = new MatchVideoTimeline.Builder(ID, false, 10000);
            gap.observe(state(TOKEN, ID, "A", 0, 10000));
            gap.observe(state(TOKEN, ID, "I", 600, 30000));
            check(!gap.finish(raw, 20000, true).normal, "missing heartbeat keeps original");
            MatchVideoTimeline.Builder late = new MatchVideoTimeline.Builder(ID, false, 10000);
            late.observe(state(TOKEN, ID, "A", 0, 8000));
            for (int i = 0; i <= 20; i++)
                late.observe(state(TOKEN, ID, i == 20 ? "I" : "A", 60 + i * 30, 10000 + i * 1000));
            MatchVideoTimeline lateTimeline = late.finish(raw, 20000, true);
            check(!lateTimeline.plan(match, List.of(moment(ID, 30, 0, 120))).mayDiscard(lateTimeline),
                "event before first encoded frame cannot authorize deletion");
            Files.write(raw.toPath(), new byte[4097]);
            check(!timeline.stillSame(raw), "modified file never auto-deleted");
            boolean rejected = false;
            try { MatchVideoTimeline.read(raw); } catch (IOException expected) { rejected = true; }
            check(rejected, "changed raw sidecar rejected");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path path : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS MatchVideoTimelineTest: " + checks + " checks");
    }
}
