package com.halo.decomp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java read-only view of existing HC1/HC2/HC3 career records. */
final class MatchHistoryModel {
    static final int ALL = 0;
    static final int WINS = 1;
    static final int LOSSES = 2;
    static final int DNF = 3;
    static final int COOP = 4;

    private MatchHistoryModel() { }

    static List<CareerRecords.Match> select(CareerRecords.Career career, int filter) {
        if (filter < ALL || filter > COOP)
            throw new IllegalArgumentException("Invalid history filter");
        List<CareerRecords.Match> matches = new ArrayList<>(career.matches);
        // Keep the latest journal row first when two games share a timestamp.
        Collections.reverse(matches);
        matches.sort((a, b) -> Long.compare(b.epoch, a.epoch));
        List<CareerRecords.Match> selected = new ArrayList<>();
        for (CareerRecords.Match match : matches) {
            boolean includes;
            switch (filter) {
                case WINS: includes = !match.cooperative() &&
                    !match.incomplete && match.wins == 1; break;
                case LOSSES: includes = !match.cooperative() &&
                    !match.incomplete && match.wins == 0; break;
                case DNF: includes = match.incomplete; break;
                case COOP: includes = match.cooperative(); break;
                default: includes = true;
            }
            if (includes) selected.add(match);
        }
        return selected;
    }

    static String summary(CareerRecords.Match match) {
        if (match.cooperative())
            return match.incomplete ? "Co-op • Unfinished" : "Co-op • Mission complete";
        return match.kills + "K  /  " + match.deaths + "D  /  " +
            match.assists + "A    •    " +
            Math.max(0, match.seconds / 60) + " min";
    }
}
