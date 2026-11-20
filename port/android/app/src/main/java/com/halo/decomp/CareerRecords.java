package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java v37 local career ledger. Never interprets match results as
 * independently certified proof of competitive rank. */
final class CareerRecords {
    static final String LOG_NAME = "career-matches-v1.log";
    static final String SERVICE_RANK_TITLE = "UNSC SERVICE RANK";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final String[] RANKS = {
        "Recruit", "Private", "Corporal", "Sergeant", "Staff Sergeant",
        "Lieutenant", "Captain", "Major", "Colonel", "Brigadier",
        "General", "Legend"
    };
    /* Long-term progression: thousands of actual games to reach Legend.
     * Rank never drops. No premium boosts or account server are required. */
    private static final long[] THRESHOLDS = {
        0, 1000, 3000, 6500, 12000, 20000, 32000, 50000,
        80000, 125000, 190000, 300000
    };

    static final class Match {
        final String id;
        final long epoch;
        final int mode;
        final String map;
        final int kills, humanKills, botKills, deaths, assists, wins;
        final int seconds, bestStreak, captures, returns, hillTicks;
        final int suicides, teamKills;
        final boolean extended, incomplete;
        final int flagGrabs, oddballTicks, ballCarrierKills, killsAsCarrier;
        final int raceLaps, bestLapTicks, lastLapTicks;

        private Match(String[] parts) {
            id = parts[1];
            epoch = Long.parseLong(parts[2]);
            mode = parse(parts[3], parts[0].equals("HC3") ? 0 : 1, 5);
            map = parts[4];
            kills = parse(parts[5], 0, 10000);
            humanKills = parse(parts[6], -1, 10000);
            botKills = parse(parts[7], -1, 10000);
            deaths = parse(parts[8], 0, 10000);
            assists = parse(parts[9], 0, 10000);
            wins = parse(parts[10], 0, 1);
            seconds = parse(parts[11], 0, 86400);
            bestStreak = parse(parts[12], 0, 10000);
            captures = parse(parts[13], 0, 10000);
            returns = parse(parts[14], 0, 10000);
            hillTicks = parse(parts[15], 0, 8640000);
            suicides = parse(parts[16], 0, 10000);
            teamKills = parse(parts[17], 0, 10000);
            boolean hc2 = parts[0].equals("HC2") || parts[0].equals("HC3");
            incomplete = parts[0].equals("HC3") && parse(parts[25], 0, 1) == 1;
            extended = hc2;
            flagGrabs = hc2 ? parse(parts[18], 0, 10000) : 0;
            oddballTicks = hc2 ? parse(parts[19], 0, 8640000) : 0;
            ballCarrierKills = hc2 ? parse(parts[20], 0, 10000) : 0;
            killsAsCarrier = hc2 ? parse(parts[21], 0, 10000) : 0;
            raceLaps = hc2 ? parse(parts[22], 0, 10000) : 0;
            bestLapTicks = hc2 ? parse(parts[23], 0, 8640000) : 0;
            lastLapTicks = hc2 ? parse(parts[24], 0, 8640000) : 0;
            if ((humanKills == -1) != (botKills == -1) ||
                (humanKills >= 0 && humanKills + botKills != kills) ||
                bestStreak > kills || wins > 1 || (incomplete && wins != 0))
                throw new IllegalArgumentException("Inconsistent match stats");
            if (cooperative() && (wins != (incomplete ? 0 : 1) || humanKills != 0 ||
                botKills != 0 || kills + deaths + assists + bestStreak + captures + returns +
                hillTicks + suicides + teamKills + flagGrabs + oddballTicks + ballCarrierKills +
                killsAsCarrier + raceLaps + bestLapTicks + lastLapTicks != 0))
                throw new IllegalArgumentException("Inconsistent co-op result");
        }

        boolean cooperative() { return mode == 0; }
        String outcome() {
            if (cooperative()) return incomplete ? "UNFINISHED" : "MISSION COMPLETE";
            return incomplete ? "DNF / LOSS" : wins == 1 ? "WIN" : "NOT WON";
        }

        String gameType() {
            switch (mode) {
                case 0: return "Co-op";
                case 1: return "CTF";
                case 2: return "Slayer";
                case 3: return "Oddball";
                case 4: return "King";
                case 5: return "Race";
                default: return "Multiplayer";
            }
        }
    }

    static final class Achievement {
        final String title, description, rarity;
        final boolean earned;
        Achievement(String title, String description, String rarity, boolean earned) {
            this.title = title;
            this.description = description;
            this.rarity = rarity;
            this.earned = earned;
        }
    }

    static final class Career {
        final List<Match> matches = new ArrayList<>();
        long kills, humanKills, botKills, unknownOpponentKills, deaths;
        long assists, wins, seconds, captures, returns, hillTicks;
        long flagGrabs, oddballTicks, ballCarrierKills, killsAsCarrier, raceLaps;
        long suicides, teamKills, bestStreak, xp, unfinished;
        int fastestLapTicks, coopSessions, coopCompletions;
        int invalidLines, duplicates;

        long games() { return matches.size() - coopSessions; }
        long completedGames() { return games() - unfinished; }

        int rankIndex() {
            int n = 0;
            while (n + 1 < THRESHOLDS.length && xp >= THRESHOLDS[n + 1])
                n++;
            return n;
        }

        String rank() { return RANKS[rankIndex()]; }
        int rankNumber() { return rankIndex() + 1; }
        long rankCurrent() { return THRESHOLDS[rankIndex()]; }
        long rankNext() {
            return rankIndex() + 1 < THRESHOLDS.length ?
                THRESHOLDS[rankIndex() + 1] : THRESHOLDS[rankIndex()];
        }

        String kd() {
            if (deaths == 0) return kills == 0 ? "0.00" : "Perfect";
            return String.format(java.util.Locale.US, "%.2f",
                (double)kills / (double)deaths);
        }

        List<Achievement> achievements() {
            List<Achievement> awards = new ArrayList<>();
            boolean flagHero = false, flawless = false, immortal = false;
            for (Match match : matches) {
                if (match.cooperative()) continue;
                if (match.captures >= 5) flagHero = true;
                if (!match.incomplete && match.kills >= 20 && match.deaths == 0) flawless = true;
                if (!match.incomplete && match.humanKills >= 30 && match.deaths == 0) immortal = true;
            }
            awards.add(new Achievement("First Blood", "Earn your first kill",
                "COMMON", kills >= 1));
            awards.add(new Achievement("Enlisted", "Finish your first multiplayer match",
                "COMMON", completedGames() >= 1));
            awards.add(new Achievement("First Victory", "Win your first multiplayer match",
                "COMMON", wins >= 1));
            awards.add(new Achievement("Bot Buster", "Defeat 100 bots",
                "UNCOMMON", botKills >= 100));
            awards.add(new Achievement("Human Hunter", "Defeat 250 human opponents",
                "UNCOMMON", humanKills >= 250));
            awards.add(new Achievement("Team Player", "Earn 250 assists",
                "UNCOMMON", assists >= 250));
            awards.add(new Achievement("Flag Runner", "Capture 20 flags",
                "UNCOMMON", captures >= 20));
            awards.add(new Achievement("Spartan Spree", "Get 10 kills without dying",
                "UNCOMMON", bestStreak >= 10));
            awards.add(new Achievement("Untouchable", "Get 20 kills without dying",
                "RARE", bestStreak >= 20));
            awards.add(new Achievement("War Veteran", "Finish 250 matches",
                "RARE", completedGames() >= 250));
            awards.add(new Achievement("Champion", "Win 100 matches",
                "RARE", wins >= 100));
            awards.add(new Achievement("Flag Hero", "Capture 5 flags in one match",
                "RARE", flagHero));
            awards.add(new Achievement("Perfect Game", "20 kills with no deaths in one match",
                "RARE", flawless));
            awards.add(new Achievement("Slayer Elite", "Score 2,000 lifetime kills",
                "RARE", kills >= 2000));
            awards.add(new Achievement("The Immortal",
                "30 human kills and no deaths in one match",
                "LEGENDARY", immortal));
            awards.add(new Achievement("War Eternal", "Finish 1,500 matches",
                "LEGENDARY", completedGames() >= 1500));
            awards.add(new Achievement("Human Reaper", "Defeat 10,000 humans",
                "LEGENDARY", humanKills >= 10000));
            awards.add(new Achievement("Supreme Commander", "Win 1,000 matches",
                "LEGENDARY", wins >= 1000));
            awards.add(new Achievement("Flag Emperor", "Capture 1,000 flags",
                "LEGENDARY", captures >= 1000));
            awards.add(new Achievement("Fireteam", "Complete a co-op mission",
                "COMMON", coopCompletions > 0));
            return awards;
        }

        /** Top three actually earned achievements, rarest first.
         * No automatic display of future/locked medals as earned awards. */
        List<Achievement> topAchievements() {
            List<Achievement> earned = new ArrayList<>();
            for (Achievement medal : achievements())
                if (medal.earned) earned.add(medal);
            earned.sort((left, right) ->
                Integer.compare(achievementTier(right.rarity),
                    achievementTier(left.rarity)));
            return earned.subList(0, Math.min(3, earned.size()));
        }

        List<Match> recent() {
            List<Match> result = new ArrayList<>(matches);
            Collections.reverse(result);
            return result.subList(0, Math.min(5, result.size()));
        }

        private void add(Match match) {
            matches.add(match);
            if (match.cooperative()) {
                coopSessions++;
                seconds += match.seconds;
                if (!match.incomplete) coopCompletions++;
                xp += (match.incomplete ? 0L : 50L) + Math.min(20, match.seconds / 60);
                return;
            }
            if (match.incomplete) unfinished++;
            kills += match.kills;
            deaths += match.deaths;
            assists += match.assists;
            wins += match.wins;
            seconds += match.seconds;
            captures += match.captures;
            returns += match.returns;
            hillTicks += match.hillTicks;
            suicides += match.suicides;
            teamKills += match.teamKills;
            flagGrabs += match.flagGrabs;
            oddballTicks += match.oddballTicks;
            ballCarrierKills += match.ballCarrierKills;
            killsAsCarrier += match.killsAsCarrier;
            raceLaps += match.raceLaps;
            if (match.bestLapTicks > 0 &&
                (fastestLapTicks == 0 || match.bestLapTicks < fastestLapTicks))
                fastestLapTicks = match.bestLapTicks;
            bestStreak = Math.max(bestStreak, match.bestStreak);
            if (match.humanKills < 0) {
                unknownOpponentKills += match.kills;
            } else {
                humanKills += match.humanKills;
                botKills += match.botKills;
            }
            // XP is progression, NOT skill rating; hard-cap bot rewards to
            // prevent 64 Recruit bots from overwhelming real-player effort.
            xp += (match.incomplete ? 0L : 50L) + 40L * match.wins
                + 12L * Math.min(20, Math.max(0, match.humanKills))
                + 2L * Math.min(30, Math.max(0, match.botKills))
                + 2L * Math.min(10, match.humanKills < 0 ? match.kills : 0)
                + 5L * Math.min(20, match.assists)
                + 25L * Math.min(4, match.captures)
                + 15L * Math.min(4, match.returns)
                + Math.min(20, match.seconds / 60);
        }
    }

    private static int achievementTier(String rarity) {
        switch (rarity) {
            case "LEGENDARY": return 4;
            case "RARE": return 3;
            case "UNCOMMON": return 2;
            case "COMMON": return 1;
            default: return 0;
        }
    }

    /** The exact twelve-rank progression shared by the service-record
     * dashboard, insignia gallery, and standalone local match journal. */
    static int rankCount() { return RANKS.length; }

    static String rankName(int index) {
        if (index < 0 || index >= RANKS.length)
            throw new IllegalArgumentException("Invalid UNSC Service Rank");
        return RANKS[index];
    }

    static long rankThreshold(int index) {
        if (index < 0 || index >= THRESHOLDS.length)
            throw new IllegalArgumentException("Invalid UNSC Service Rank");
        return THRESHOLDS[index];
    }

    private CareerRecords() { }

    private static int parse(String field, int min, int max) {
        if (!field.matches("-?[0-9]{1,9}"))
            throw new IllegalArgumentException("Malformed number");
        int n = Integer.parseInt(field);
        if (n < min || n > max)
            throw new IllegalArgumentException("Out-of-range stat");
        return n;
    }

    static Match parseLine(String line) {
        if (line == null || line.length() > 479 || line.length() < 66)
            throw new IllegalArgumentException("Bad match record length");
        String[] fields = line.split("\\|", -1);
        boolean old = fields.length == 18 && "HC1".equals(fields[0]);
        boolean extended = (fields.length == 25 && "HC2".equals(fields[0])) ||
            (fields.length == 26 && "HC3".equals(fields[0]));
        if ((!old && !extended) ||
            (old && line.length() > 319) ||
            !fields[1].matches("[0-9a-f]{32}") ||
            !fields[2].matches("[0-9]{1,11}") ||
            !fields[4].matches("[a-z0-9_-]{1,47}"))
            throw new IllegalArgumentException("Bad match record format");
        long epoch = Long.parseLong(fields[2]);
        if (epoch < 0 || epoch > 4102444800L)
            throw new IllegalArgumentException("Bad match timestamp");
        return new Match(fields);
    }

    static Career load(File appDirectory) throws IOException {
        Career career = new Career();
        if (appDirectory == null) return career;
        java.util.LinkedHashMap<String, Match> records = new java.util.LinkedHashMap<>();
        readRecords(new File(appDirectory, LOG_NAME), MAX_FILE_BYTES, career, records);
        try {
            readRecords(new File(appDirectory, "career-pending-v1.log"), 479, career, records);
        } catch (IOException unreadableCheckpoint) {
            career.invalidLines++;
        }
        for (Match match : records.values()) career.add(match);
        return career;
    }

    private static void readRecords(File source, int limit, Career career,
            java.util.Map<String, Match> records) throws IOException {
        if (!source.isFile()) return;
        if (source.length() > limit || source.length() < 0)
            throw new IOException("Career log is too large; back it up before adding matches.");
        byte[] content = Files.readAllBytes(source.toPath());
        if (content.length > limit) throw new IOException("Career history grew while it was being read.");
        int end = 0;
        for (int i = content.length - 1; i >= 0; --i)
            if (content[i] == '\n') { end = i + 1; break; }
        String data = new String(content, 0, end, StandardCharsets.UTF_8);
        for (String raw : data.split("\\n")) {
            if (raw.isEmpty()) continue;
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            try {
                Match match = parseLine(line);
                Match old = records.get(match.id);
                if (old == null) records.put(match.id, match);
                else {
                    career.duplicates++;
                    // A completed receipt replaces its durable checkpoint.
                    // A later crash-recovery copy cannot downgrade it to DNF.
                    if (old.incomplete && (!match.incomplete || match.seconds > old.seconds))
                        records.put(match.id, match);
                }
            } catch (IllegalArgumentException bad) { career.invalidLines++; }
        }
    }
}
