package com.halo.decomp;

import java.io.File;

/** Load the native host and guest journals produced by test_career_results.py. */
public final class CareerJournalTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        for (String fixture : new String[]{"coop_host", "coop_guest", "coop_exit"}) {
            boolean finished = !fixture.equals("coop_exit");
            CareerRecords.Career career = CareerRecords.load(new File(args[0], fixture));
            check(career.invalidLines == 0 && career.matches.size() == 1, fixture);
            CareerRecords.Match match = career.matches.get(0);
            check(match.map.equals("b30") && match.gameType().equals("Co-op"), fixture);
            check(career.coopSessions == 1 && career.coopCompletions == (finished ? 1 : 0), fixture);
            check(career.seconds == 80 && career.xp == (finished ? 51 : 1), fixture);
            check(career.games() == 0 && career.wins == 0 && career.kills == 0, fixture);
            check(career.achievements().stream().anyMatch(a -> a.title.equals("Fireteam") && a.earned)
                == finished, fixture);
        }
        CareerRecords.Career mp = CareerRecords.load(new File(args[0], "ordering"));
        check(mp.invalidLines == 0 && mp.games() == 1 && mp.botKills == 1 && mp.captures == 1 &&
            mp.wins == 1 && mp.xp > 0 && !mp.topAchievements().isEmpty(), "multiplayer profile");
        File damaged = new File(args[0], "damaged");
        java.nio.file.Files.write(new File(damaged, "career-pending-v1.log").toPath(), new byte[1024]);
        CareerRecords.Career recovered = CareerRecords.load(damaged);
        check(recovered.games() == 1 && recovered.invalidLines == 1, "bad checkpoint preserves valid history");
        CareerRecords.Career exit = CareerRecords.load(new File(args[0], "checkpoint"));
        check(exit.games() == 1 && exit.unfinished == 1 && exit.wins == 0 && exit.botKills == 1,
            "disconnected guest");
    }
}
