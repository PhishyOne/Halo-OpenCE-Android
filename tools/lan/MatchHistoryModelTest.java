package com.halo.decomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Pure-JDK behavior and backward-compatibility test for v50 history filters. */
public final class MatchHistoryModelTest {
    private static int checks;
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }

    private static String record(int id, long epoch, int mode, int kills,
            int humans, int bots, int wins, int unfinished) {
        int deaths = mode == 0 ? 0 : 2;
        int assists = mode == 0 ? 0 : 1;
        int streak = mode == 0 ? 0 : Math.min(kills, 3);
        StringBuilder row = new StringBuilder("HC3|");
        row.append(String.format(java.util.Locale.US, "%032x", id))
            .append('|').append(epoch)
            .append('|').append(mode).append("|bloodgulch")
            .append('|').append(kills)
            .append('|').append(humans).append('|').append(bots)
            .append('|').append(deaths).append('|').append(assists)
            .append('|').append(wins).append("|600|").append(streak);
        for (int index = 0; index < 12; index++) row.append("|0");
        row.append('|').append(unfinished).append('\n');
        return row.toString();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("halo-history-v50-");
        try {
            String data =
                record(1, 1795000000L, 2, 4, 3, 1, 1, 0) +
                record(2, 1795000500L, 1, 2, 0, 2, 0, 0) +
                record(3, 1795001000L, 4, 3, 3, 0, 0, 1) +
                record(4, 1795001500L, 0, 0, 0, 0, 1, 0) +
                record(5, 1795002000L, 0, 0, 0, 0, 0, 1);
            Files.writeString(dir.resolve(CareerRecords.LOG_NAME), data);
            CareerRecords.Career career = CareerRecords.load(dir.toFile());
            check(career.matches.size() == 5, "all HC3 results preserved");

            List<CareerRecords.Match> all = MatchHistoryModel.select(
                career, MatchHistoryModel.ALL);
            check(all.size() == 5, "ALL includes multiplayer and co-op");
            check(all.get(0).cooperative() && all.get(0).incomplete,
                "latest record displayed first");
            check(all.get(4).wins == 1 && !all.get(4).cooperative(),
                "oldest last");

            List<CareerRecords.Match> wins = MatchHistoryModel.select(
                career, MatchHistoryModel.WINS);
            check(wins.size() == 1 && wins.get(0).wins == 1 &&
                !wins.get(0).cooperative(), "co-op wins excluded from PvP WINS");
            List<CareerRecords.Match> losses = MatchHistoryModel.select(
                career, MatchHistoryModel.LOSSES);
            check(losses.size() == 1 && losses.get(0).mode == 1,
                "completed human matches alone count as LOSSES");
            List<CareerRecords.Match> exits = MatchHistoryModel.select(
                career, MatchHistoryModel.DNF);
            check(exits.size() == 2 && exits.get(0).cooperative() &&
                exits.get(1).mode == 4, "DNF includes both unfinished game types");
            List<CareerRecords.Match> coop = MatchHistoryModel.select(
                career, MatchHistoryModel.COOP);
            check(coop.size() == 2 && coop.get(0).incomplete &&
                !coop.get(1).incomplete, "CO-OP includes complete and unfinished");
            check(MatchHistoryModel.summary(coop.get(0)).contains("Unfinished"),
                "unfinished co-op labeled correctly");
            check(MatchHistoryModel.summary(coop.get(1)).contains("Mission complete"),
                "completed co-op labeled correctly");
            check(MatchHistoryModel.summary(wins.get(0)).contains("4K  /  2D  /  1A"),
                "compact multiplayer stats correct");

            boolean rejected = false;
            try { MatchHistoryModel.select(career, 15); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "bad filter must not silently select a category");

            // Parsing and XP remain the existing pure-Java career model's work.
            check(career.games() == 3, "co-op excluded from multiplayer match count");
            check(career.unfinished == 1, "one unfinished multiplayer result");
            check(career.coopSessions == 2, "co-op counters preserved");
            System.out.println("PASS MatchHistoryModelTest: " + checks + " checks");
        } finally {
            Files.deleteIfExists(dir.resolve(CareerRecords.LOG_NAME));
            Files.deleteIfExists(dir);
        }
    }
}
