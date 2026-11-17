package com.halo.decomp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Pure JDK: bounded markers, deduping, game-ID join, partial append handling. */
public final class MatchHighlightsTest {
    private static int checks;
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
    private static void reject(String line) {
        checks++;
        try {
            MatchHighlights.parse(line);
            throw new AssertionError("Accepted invalid highlight: " + line);
        } catch (IllegalArgumentException expected) { }
    }

    public static void main(String[] args) throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        String otherId = "fedcba9876543210fedcba9876543210";
        String record = "HLM1|" + id +
            "|bloodgulch|1200|STREAK_5|76|1050|1290";
        MatchHighlights.Moment parsed = MatchHighlights.parse(record);
        check(parsed.tick == 1200 && parsed.score == 76,
            "highlight tick and score");
        check(parsed.label().equals("5-kill streak"), "streak label");
        check(parsed.timing().startsWith("00:40"), "30Hz timestamp display");
        reject(record.replace("HLM1|", "WRONG|"));
        reject(record.replace("|STREAK_5|", "|HELLO_WORLD|"));
        reject(record.replace("|1200|", "|-1|"));
        reject(record.replace("|1050|1290", "|1300|1290"));
        reject(record.replace(id, "../../unauthorized"));
        reject(record.replace("bloodgulch", "../bloodgulch"));
        Path folder = Files.createTempDirectory("halo-hlm-jdk-");
        try {
            String other = "HLM1|" + otherId +
                "|sidewinder|300|FLAG_CAPTURE|93|150|390";
            Path file = folder.resolve(MatchHighlights.FILENAME);
            Files.writeString(file, record + "\n" + record + "\n" + other +
                "\nINCOMPLETE", StandardCharsets.UTF_8);
            List<MatchHighlights.Moment> found =
                MatchHighlights.load(folder.toFile());
            check(found.size() == 2, "duplicate marker and partial tail discarded");
            String careerLog =
                "HC1|" + id +
                "|1794800000|2|bloodgulch|5|4|1|2|1|1|600|5|0|0|0|0|0\n" +
                "HC1|" + otherId +
                "|1794800000|1|sidewinder|0|0|0|1|0|1|600|0|1|0|0|0|0\n";
            Files.writeString(folder.resolve(CareerRecords.LOG_NAME),
                careerLog, StandardCharsets.UTF_8);
            CareerRecords.Career career = CareerRecords.load(folder.toFile());
            check(career.games() == 2, "joined with actual durable game receipts");
            check(MatchHighlights.forMatch(career.matches.get(0),found).size()==1,
                "first match linked by exact ID");
            check(MatchHighlights.mostRecent(career,found).get(0).type.equals(
                "FLAG_CAPTURE"), "newest valid moment selected");
            check(MatchHighlights.load(null).isEmpty(), "null data root empty");
            check(MatchHighlights.load(Files.createDirectory(
                folder.resolve("empty")).toFile()).isEmpty(), "missing file empty");
            Files.writeString(file, record.replace("bloodgulch","sidewinder")+
                "\n", StandardCharsets.UTF_8);
            found = MatchHighlights.load(folder.toFile());
            check(MatchHighlights.mostRecent(career,found).isEmpty(),
                "mismatched map cannot attach to career match");
            Files.write(file, new byte[4*1024*1024 + 1]);
            boolean limit = false;
            try { MatchHighlights.load(folder.toFile()); }
            catch (IOException expected) { limit = true; }
            check(limit, "bounded file size enforced");
        } finally {
            Files.deleteIfExists(folder.resolve(MatchHighlights.FILENAME));
            Files.deleteIfExists(folder.resolve(CareerRecords.LOG_NAME));
            Files.deleteIfExists(folder.resolve("empty"));
            Files.deleteIfExists(folder);
        }
        System.out.println("PASS MatchHighlightsTest: " + checks + " checks");
    }
}
