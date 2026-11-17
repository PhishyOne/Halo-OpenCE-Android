package com.halo.decomp;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CareerExitTest {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("halo-exits-");
        Path pending = dir.resolve("career-pending-v1.log");
        Path ledger = dir.resolve(CareerRecords.LOG_NAME);
        String row = "HC3|0123456789abcdef0123456789abcdef|1794800000|2|bloodgulch"
            + "|20|20|0|2|3|0|600|10|0|0|0|0|0|0|0|0|0|0|0|0|1\n";
        try {
            Files.writeString(pending, row);
            CareerRecords.Career unfinished = CareerRecords.load(dir.toFile());
            check(unfinished.games() == 1 && unfinished.unfinished == 1, "crash survives without a ledger");
            check(unfinished.kills == 20 && unfinished.deaths == 2 && unfinished.wins == 0, "earned stats, no victory");
            check(unfinished.completedGames() == 0, "unfinished is not completed");
            check(unfinished.matches.get(0).outcome().equals("DNF / LOSS"), "clear exit label");
            String finalRow = row.substring(0, row.length() - 2) + "0\n";
            Files.writeString(ledger, row + finalRow);
            CareerRecords.Career completed = CareerRecords.load(dir.toFile());
            check(completed.games() == 1 && completed.unfinished == 0, "final upgrades checkpoint exactly once");
            check(completed.xp == unfinished.xp + 50, "completion bonus only once and only for finished match");
            Files.writeString(ledger, finalRow + row);
            check(CareerRecords.load(dir.toFile()).unfinished == 0, "recovery never downgrades completion");
            Files.writeString(ledger, "");
            Files.writeString(pending, row.substring(0, row.length() - 1));
            check(CareerRecords.load(dir.toFile()).games() == 0, "torn checkpoint excluded");
            String[] fields = row.trim().split("\\|"); fields[10] = "1";
            boolean rejected = false;
            try { CareerRecords.parseLine(String.join("|", fields)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "unfinished result cannot claim a win");
            System.out.println("PASS CareerExitTest: 9 crash, completion, deduplication and XP checks");
        } finally {
            Files.deleteIfExists(pending); Files.deleteIfExists(ledger); Files.deleteIfExists(dir);
        }
    }
}
