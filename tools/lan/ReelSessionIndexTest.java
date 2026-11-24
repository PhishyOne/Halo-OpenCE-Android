package com.halo.decomp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Pure JDK timing and storage guard tests, no Android MediaProjection. */
public final class ReelSessionIndexTest {
    private static int tests;
    private static void check(boolean yes, String reason) {
        tests++;
        if (!yes) throw new AssertionError(reason);
    }

    private static String record(String id, long finishEpoch, int seconds) {
        return "HC3|" + id + "|" + finishEpoch +
            "|2|bloodgulch|5|3|2|2|1|1|" + seconds +
            "|3|0|0|0|0|0|0|0|0|0|0|0|0|0\n";
    }

    public static void main(String[] args) throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        String other = "fedcba9876543210fedcba9876543210";
        long started = 1795000000000L;
        ReelSessionIndex.Session source = ReelSessionIndex.parse(
            "RS1|session-1795000000000.mp4|" + started +
            "|" + (started + 120000) + "|640|288");
        check(source.filename.equals("session-1795000000000.mp4"),
            "safe filename");
        check(source.durationMs() == 120000, "capture duration");
        check(ReelSessionIndex.format(source).startsWith("RS1|"), "appendable index");
        for (String unsafe : new String[] {
                "RS1|../attack.mp4|1795000000000|1795000060000|640|288",
                "RS1|session-1795000000000.mp4|1795000000000|1795002400000|640|288",
                "RS1|session-1795000000000.mp4|1795000000000|1795000060000|641|288",
                "RS1|session-1795000000000.mp4|a|b|640|288"
        }) {
            boolean invalid=false;
            try {ReelSessionIndex.parse(unsafe);}
            catch (IllegalArgumentException expected) { invalid=true; }
            check(invalid, "unsafe recording metadata refused");
        }
        Path dir = Files.createTempDirectory("halo-clip-plan-");
        try {
            Path reels=Files.createDirectory(dir.resolve("reels"));
            Path video=reels.resolve(source.filename);
            Files.write(video, new byte[4096]);
            Files.writeString(reels.resolve(ReelSessionIndex.INDEX),
                ReelSessionIndex.format(source) + "RS1|unfinished",
                StandardCharsets.UTF_8);
            check(ReelSessionIndex.load(dir.toFile()).size() == 1,
                "incomplete index tail ignored");
            String game = record(id, 1795000060L, 40);
            Files.writeString(dir.resolve(CareerRecords.LOG_NAME), game);
            CareerRecords.Career career = CareerRecords.load(dir.toFile());
            check(career.matches.size()==1, "existing HC3 parser unchanged");
            CareerRecords.Match match=career.matches.get(0);
            MatchHighlights.Moment moment = MatchHighlights.parse(
                "HLM1|" + id + "|bloodgulch|450|STREAK_3|52|300|540");
            List<ReelSessionIndex.Clip> windows=ReelSessionIndex.windows(
                source,match,List.of(moment));
            check(windows.size()==1, "one real clip selection");
            check(windows.get(0).startUs == 30000000L,
                "10-sec in-game moment maps to +30-sec source frame");
            check(windows.get(0).endUs == 38000000L,
                "18-sec window maps to +38-sec source frame");
            List<ReelSessionIndex.Clip> none=ReelSessionIndex.windows(
                source,match,List.of(MatchHighlights.parse(
                    "HLM1|" + other + "|bloodgulch|450|STREAK_3|52|300|540")));
            check(none.isEmpty(), "never mix another player's match ID");
            check(ReelSessionIndex.reelName(match).equals("reel-"+id+".mp4"),
                "reel path derives from strict existing match identity");
            MatchHighlights.Moment close = MatchHighlights.parse(
                "HLM1|" + id + "|bloodgulch|500|STREAK_5|76|350|590");
            windows=ReelSessionIndex.windows(source,match,List.of(moment,close));
            check(windows.size()==1 && windows.get(0).endUs>38000000L,
                "overlapping moments merge, avoiding duplicate footage");
            ReelSessionIndex.Session far = ReelSessionIndex.parse(
                "RS1|session-1795000400000.mp4|1795000400000|1795000520000|640|288");
            check(ReelSessionIndex.windows(far,match,List.of(moment)).isEmpty(),
                "past game cannot be clipped from unrelated session");
        } finally {
            Files.deleteIfExists(dir.resolve(CareerRecords.LOG_NAME));
            Files.deleteIfExists(dir.resolve("reels").resolve(source.filename));
            Files.deleteIfExists(dir.resolve("reels").resolve(ReelSessionIndex.INDEX));
            Files.deleteIfExists(dir.resolve("reels"));
            Files.deleteIfExists(dir);
        }
        System.out.println("PASS ReelSessionIndexTest: " + tests + " checks");
    }
}
