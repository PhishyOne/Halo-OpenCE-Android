package com.halo.decomp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Pure JVM career progression, validation, rollback-safe parsing. */
public final class CareerRecordsTest {
    private static int checks;
    private static void check(boolean good, String why) {
        checks++;
        if (!good) throw new AssertionError(why);
    }
    private static String record(String id, String map, int kills,
                                 int human, int bots, int deaths,
                                 int assists, int win, int streak, int caps) {
        return "HC1|" + id + "|1794800000|2|" + map + "|" + kills +
            "|" + human + "|" + bots + "|" + deaths + "|" + assists +
            "|" + win + "|600|" + streak + "|" + caps + "|2|0|0|0\n";
    }
    private static void fails(String input, String reason) {
        checks++;
        try {
            CareerRecords.parseLine(input);
            throw new AssertionError("Accepted invalid " + reason);
        } catch (IllegalArgumentException good) {
            // expected
        }
    }
    private static boolean unlocked(List<CareerRecords.Achievement> list,
                                    String title) {
        for (CareerRecords.Achievement badge : list)
            if (badge.title.equals(title)) return badge.earned;
        throw new AssertionError("No such achievement: " + title);
    }
    public static void main(String[] args) throws Exception {
        String a = "0123456789abcdef0123456789abcdef";
        String b = "fedcba9876543210fedcba9876543210";
        String valid = record(a, "bloodgulch", 12, 10, 2, 4, 3, 1, 10, 0);
        CareerRecords.Match match = CareerRecords.parseLine(valid.trim());
        check(match.kills == 12 && match.humanKills == 10,
            "parse real human/bot split");
        check(match.gameType().equals("Slayer"), "game type Slayer");
        check(match.bestStreak == 10, "best streak");
        check(match.returns == 2, "flag returns");
        check(match.wins == 1, "recorded win");
        fails(valid.replace("bloodgulch", "bad|map").trim(), "delimiter injection");
        fails(valid.replace("|12|10|2|", "|12|11|2|").trim(), "inconsistent split");
        fails(valid.replace("|12|10|2|", "|-12|10|2|").trim(), "negative kills");
        fails(valid.replace("|12|10|2|", "|20001|10|2|").trim(), "huge score");
        fails(valid.replace("HC1|", "HC0|").trim(), "wrong protocol");
        fails(valid.replace(a, a + "A").trim(), "invalid ID");

        Path dir = Files.createTempDirectory("halo-v37-career-");
        try {
            check(CareerRecords.load(dir.toFile()).games() == 0, "initial empty");
            Path ledger = dir.resolve(CareerRecords.LOG_NAME);
            // Same match retransmitted twice must count once. Partial
            // postgame append after a crash is not counted.
            Files.writeString(ledger, valid + valid +
                record(b, "sidewinder", 8, -1, -1, 2, 1, 0, 5, 0) +
                "HC1|incomplete", StandardCharsets.UTF_8);
            CareerRecords.Career career = CareerRecords.load(dir.toFile());
            check(career.games() == 2, "one record per unique completed match");
            check(career.duplicates == 1, "duplicate logged");
            check(career.invalidLines == 0, "trailing partial line ignored");
            check(career.kills == 20 && career.deaths == 6, "aggregate score");
            check(career.humanKills == 10 && career.botKills == 2,
                "classified opponents retained");
            check(career.unknownOpponentKills == 8, "unclassified opponent handling");
            check(career.wins == 1 && career.assists == 4, "win / assist counters");
            check(career.bestStreak == 10, "best career streak");
            check(career.xp == 269 + 111,
                "XP is based on bounded match activity");
            check(career.rank().equals("Recruit"), "long-term rank progression");
            check(career.rankNext() == 1000, "first rank takes several matches");
            check(unlocked(career.achievements(), "First Blood"), "First Blood");
            check(unlocked(career.achievements(), "Spartan Spree"), "10-kill reward achievement");
            check(!unlocked(career.achievements(), "Untouchable"), "20-kill rare medal not farmed");
            check(!unlocked(career.achievements(), "The Immortal"), "legendary medal locked");
            check(!unlocked(career.achievements(), "War Veteran"),
                "veteran achievement still locked");
            check(!unlocked(career.achievements(), "Bot Buster"),
                "bot farming does not spoof badge");
            check(career.recent().size() == 2 &&
                career.recent().get(0).map.equals("sidewinder"),
                "recent match order");
            // Wrongly classified records are skipped, not partly counted.
            Files.writeString(ledger, valid +
                valid.replace("|12|10|2|", "|12|11|2|") +
                "HC1|trailing", StandardCharsets.UTF_8);
            CareerRecords.Career checked = CareerRecords.load(dir.toFile());
            check(checked.games() == 1 && checked.invalidLines == 1,
                "corrupt complete line rejected; partial tail ignored");
            // An unreadable/too-large ledger must not silently reset history.
            byte[] oversized = new byte[2 * 1024 * 1024 + 2];
            Files.write(ledger, oversized);
            boolean rejected = false;
            try { CareerRecords.load(dir.toFile()); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "too-large ledger guarded");
        } finally {
            Files.deleteIfExists(dir.resolve(CareerRecords.LOG_NAME));
            Files.deleteIfExists(dir);
        }
        System.out.println("PASS CareerRecordsTest: " + checks + " checks");
    }
}
