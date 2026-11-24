package com.halo.decomp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** v53: provisional *practice* SR. This is NOT official competitive Elo:
 * HC3 receipts have host-owned results, but no opponent identity, common
 * match ID, signed receipt or proof that no bots were in the lobby.
 * Keep verified Spartan Rating UNRATED until those proofs exist.
 *
 * Uses a fixed 1500 reference opponent and only completed, classified,
 * bot-kill-free, active multiplayer results. This makes a reproducible,
 * immediately visible indication of performance, not a cheat-proof ladder.
 */
final class LanRating {
    static final int BASE = 1500;
    static final int MIN = 800, MAX = 2400;
    static final class Score {
        final int value, matches, wins, losses;
        Score(int value, int matches, int wins, int losses) {
            this.value = value;
            this.matches = matches;
            this.wins = wins;
            this.losses = losses;
        }
        boolean provisional() { return matches > 0; }
        String tier() {
            if (matches == 0) return "UNRATED";
            if (value >= 2100) return "ONYX";
            if (value >= 1900) return "DIAMOND";
            if (value >= 1700) return "PLATINUM";
            if (value >= 1550) return "GOLD";
            if (value >= 1300) return "SILVER";
            return "BRONZE";
        }
    }

    private LanRating() { }

    /** No bot kills, unclassified legacy games or interrupted/co-op matches.
     * This still DOES NOT prove a bot-free lobby; hence practice-only. */
    static boolean candidate(CareerRecords.Match m) {
        return m != null && m.extended && !m.incomplete &&
            !m.cooperative() && m.mode >= 1 && m.mode <= 5 &&
            m.botKills == 0 && m.humanKills >= 0 &&
            m.humanKills == m.kills && m.seconds >= 60 &&
            m.kills + m.deaths + m.assists + m.captures +
            m.returns + m.raceLaps > 0;
    }

    static Score calculate(CareerRecords.Career career) {
        if (career == null) return new Score(BASE, 0, 0, 0);
        List<CareerRecords.Match> ordered = new ArrayList<>(career.matches);
        ordered.sort(Comparator.comparingLong((CareerRecords.Match m) -> m.epoch)
            .thenComparing(m -> m.id));
        double rating = BASE;
        int count = 0, wins = 0, losses = 0;
        for (CareerRecords.Match m : ordered) {
            if (!candidate(m)) continue;
            // Matches are compared against a reference rating, NOT the
            // unknown ratings of anonymous opposing players.
            double expected = 1.0 / (1.0 + Math.pow(10.0, (BASE - rating) / 400.0));
            int k = count < 10 ? 40 : 24;
            double actual = m.wins == 1 ? 1.0 : 0.0;
            rating = Math.max(MIN, Math.min(MAX, rating + k * (actual - expected)));
            count++;
            if (m.wins == 1) wins++; else losses++;
        }
        return new Score((int)Math.round(rating), count, wins, losses);
    }
}
