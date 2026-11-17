package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure-Java v37 local career ledger. Never interprets match results as
 * independently certified proof of competitive rank. */
final class CareerRecords {
    static final String LOG_NAME = "career-matches-v1.log";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final String[] RANKS = {
        "Recruit", "Private", "Corporal", "Sergeant", "Staff Sergeant",
        "Lieutenant", "Captain", "Major", "Colonel", "Brigadier",
        "General", "Legend"
    };
    private static final long[] THRESHOLDS = {
        0, 100, 300, 650, 1100, 1700, 2500, 3500, 5000, 7000, 10000, 15000
    };

    static final class Match {
        final String id;
        final long epoch;
        final int mode;
        final String map;
        final int kills, humanKills, botKills, deaths, assists, wins;
        final int seconds, bestStreak, captures, returns, hillTicks;
        final int suicides, teamKills;

        private Match(String[] parts) {
            id = parts[1];
            epoch = Long.parseLong(parts[2]);
            mode = parse(parts[3], 1, 5);
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
            if ((humanKills == -1) != (botKills == -1) ||
                (humanKills >= 0 && humanKills + botKills != kills) ||
                bestStreak > kills || wins > 1)
                throw new IllegalArgumentException("Inconsistent match stats");
        }

        String gameType() {
            switch (mode) {
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
        final String title, description;
        final boolean earned;
        Achievement(String title, String description, boolean earned) {
            this.title = title;
            this.description = description;
            this.earned = earned;
        }
    }

    static final class Career {
        final List<Match> matches = new ArrayList<>();
        long kills, humanKills, botKills, unknownOpponentKills, deaths;
        long assists, wins, seconds, captures, returns, hillTicks;
        long suicides, teamKills, bestStreak, xp;
        int invalidLines, duplicates;

        long games() { return matches.size(); }

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
            List<Achievement> earned = new ArrayList<>();
            boolean perfect = false, flagHero = false;
            for (Match match : matches) {
                if (match.kills >= 10 && match.deaths == 0)
                    perfect = true;
                if (match.captures >= 3)
                    flagHero = true;
            }
            earned.add(new Achievement("First Blood", "Get your first kill", kills >= 1));
            earned.add(new Achievement("War Veteran", "Finish 10 matches", games() >= 10));
            earned.add(new Achievement("Untouchable", "Get a 10-kill streak", bestStreak >= 10));
            earned.add(new Achievement("Human Hunter", "Defeat 25 human opponents", humanKills >= 25));
            earned.add(new Achievement("Bot Buster", "Defeat 100 bots", botKills >= 100));
            earned.add(new Achievement("Flag Hero", "Capture three flags in a match", flagHero));
            earned.add(new Achievement("Perfect Game", "10 kills and no deaths in a match", perfect));
            earned.add(new Achievement("Team Player", "Earn 50 assists", assists >= 50));
            earned.add(new Achievement("Champion", "Win 10 matches", wins >= 10));
            return earned;
        }

        List<Match> recent() {
            List<Match> result = new ArrayList<>(matches);
            Collections.reverse(result);
            return result.subList(0, Math.min(5, result.size()));
        }

        private void add(Match match) {
            matches.add(match);
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
            bestStreak = Math.max(bestStreak, match.bestStreak);
            if (match.humanKills < 0) {
                unknownOpponentKills += match.kills;
            } else {
                humanKills += match.humanKills;
                botKills += match.botKills;
            }
            // XP is progression, NOT skill rating; hard-cap bot rewards to
            // prevent 64 Recruit bots from overwhelming real-player effort.
            xp += 50L + 40L * match.wins
                + 12L * Math.min(20, Math.max(0, match.humanKills))
                + 2L * Math.min(30, Math.max(0, match.botKills))
                + 2L * Math.min(10, match.humanKills < 0 ? match.kills : 0)
                + 5L * Math.min(20, match.assists)
                + 25L * Math.min(4, match.captures)
                + 15L * Math.min(4, match.returns)
                + Math.min(20, match.seconds / 60);
        }
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
        if (line == null || line.length() > 319 || line.length() < 66)
            throw new IllegalArgumentException("Bad match record length");
        String[] fields = line.split("\\|", -1);
        if (fields.length != 18 || !fields[0].equals("HC1") ||
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
        File source = new File(appDirectory, LOG_NAME);
        if (!source.isFile()) return career;
        if (source.length() > MAX_FILE_BYTES || source.length() < 0)
            throw new IOException("Career log is too large; back it up before adding matches.");
        byte[] content = Files.readAllBytes(source.toPath());
        if (content.length > MAX_FILE_BYTES)
            throw new IOException("Career history grew while it was being read.");
        int end = 0;
        // A crash may leave an incomplete trailing record. Never count it.
        for (int i = content.length - 1; i >= 0; --i) {
            if (content[i] == '\n') { end = i + 1; break; }
        }
        String data = new String(content, 0, end, StandardCharsets.UTF_8);
        Set<String> seen = new HashSet<>();
        for (String raw : data.split("\\n")) {
            if (raw.isEmpty()) continue;
            String line = raw.endsWith("\r") ?
                raw.substring(0, raw.length() - 1) : raw;
            try {
                Match match = parseLine(line);
                if (seen.add(match.id))
                    career.add(match);
                else
                    career.duplicates++;
            } catch (IllegalArgumentException bad) {
                career.invalidLines++;
            }
        }
        return career;
    }
}
