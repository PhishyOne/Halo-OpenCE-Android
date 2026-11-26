package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pure-JDK v53 practice rating, bounded peer cards, atomic local cache. */
public final class LanBoardCoreTest {
    private static int checks;
    private static void check(boolean result, String reason) {
        checks++;
        if (!result) throw new AssertionError(reason);
    }
    private static String id(int number) {
        return String.format(java.util.Locale.US, "%032x", number);
    }
    private static String row(int identity, long epoch, int mode, int kills,
                              int humans, int bots, int deaths, int win,
                              boolean unfinished) {
        StringBuilder record = new StringBuilder();
        record.append("HC3|").append(id(identity)).append('|').append(epoch)
            .append('|').append(mode).append("|bloodgulch|").append(kills)
            .append('|').append(humans).append('|').append(bots)
            .append('|').append(deaths).append("|0|").append(win)
            .append("|600|").append(Math.min(kills, 3));
        for (int i = 0; i < 12; i++) record.append("|0");
        record.append(unfinished ? "|1\n" : "|0\n");
        return record.toString();
    }
    interface Attempt { void run() throws IOException; }
    private static void reject(Attempt task, String reason) {
        checks++;
        try {
            task.run();
            throw new AssertionError("Accepted invalid " + reason);
        } catch (IllegalArgumentException | IOException expected) { }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-board-core-");
        try {
            String data =
                row(1,1794800000L,2,5,5,0,2,1,false) +
                row(2,1794800300L,2,3,3,0,2,0,false) +
                row(3,1794800600L,2,10,5,5,3,1,false) +
                row(4,1794800900L,2,2,2,0,1,0,true) +
                row(5,1794801200L,0,0,0,0,0,1,false) +
                row(6,1794801500L,2,1,-1,-1,1,1,false);
            Path ledger = root.resolve(CareerRecords.LOG_NAME);
            Files.writeString(ledger, data, StandardCharsets.US_ASCII);
            CareerRecords.Career career = CareerRecords.load(root.toFile());
            check(career.matches.size() == 6, "HC3 journal unchanged");
            LanRating.Score score = LanRating.calculate(career);
            check(score.matches == 2 && score.wins == 1 && score.losses == 1,
                "only human-classified bot-kill-free completed results qualify");
            check(score.value >= 1480 && score.value <= 1520,
                "practice SR changes by result, not XP or bot farming");
            check(LanRating.calculate(null).matches == 0 &&
                LanRating.calculate(null).tier().equals("UNRATED"),
                "missing history remains UNRATED");
            check(!LanRating.candidate(career.matches.get(2)),
                "bot-kill match excluded");
            check(!LanRating.candidate(career.matches.get(3)),
                "DNF excluded");
            check(!LanRating.candidate(career.matches.get(4)),
                "co-op excluded");
            check(!LanRating.candidate(career.matches.get(5)),
                "unclassified older match excluded");

            long now = 1794900000000L;
            LanBoardProtocol.Entry me = LanBoardStore.current(id(81),
                "Spartan One", career, now);
            check(me.ratedGames == 2 && me.rating == score.value,
                "board derives rating from local career");
            check(me.xp == career.xp && me.matches == career.games(),
                "no XP/match rewrite");
            check(me.deaths == career.deaths && me.assists == career.assists,
                "head-to-head comparison uses actual lifetime deaths/assists");
            String token = "0123456789abcdef";
            String encoded = LanBoardProtocol.card(token, me);
            LanBoardProtocol.Entry decoded =
                LanBoardProtocol.readCard(encoded.trim(), token);
            check(decoded.id.equals(me.id) && decoded.name.equals(me.name) &&
                decoded.xp == me.xp, "full card round trip");
            check(encoded.length() < LanBoardProtocol.MAX_LINE, "bounded wire card");
            reject(() -> LanBoardProtocol.readCard(encoded.trim(), "1111111111111111"),
                "wrong nonce");
            reject(() -> LanBoardProtocol.readCard(encoded.replace(
                id(81), "../evil").trim(), token), "path traversal ID");
            reject(() -> LanBoardProtocol.readCard(encoded.replace(
                "|" + me.rating + "|", "|99999|").trim(), token),
                "forged out-of-range SR");
            reject(() -> LanBoardProtocol.readCard(encoded.replace(
                "HBC2|", "BAD!|").trim(), token), "wrong magic");
            reject(() -> LanBoardProtocol.readCard(encoded.trim() + "|0", token),
                "extra field");
            check(LanBoardProtocol.readHeader(
                LanBoardProtocol.header(token, 3).trim(), token) == 3,
                "bounded nonce-specific shared board");
            reject(() -> LanBoardProtocol.readHeader(
                LanBoardProtocol.header(token, 3).trim(),
                "1111111111111111"), "unrelated shared board");
            reject(() -> LanBoardProtocol.readHeader(
                "HBL1|" + token + "|25", token), "oversized peer list");
            reject(() -> LanBoardProtocol.boundedLine(
                new ByteArrayInputStream("BAD".getBytes(StandardCharsets.US_ASCII))),
                "truncated network frame");
            reject(() -> LanBoardProtocol.boundedLine(
                new ByteArrayInputStream(("A".repeat(260) + "\n")
                    .getBytes(StandardCharsets.US_ASCII))), "oversized line");
            check(LanBoardProtocol.boundedLine(new ByteArrayInputStream(
                (encoded).getBytes(StandardCharsets.US_ASCII)))
                .equals(encoded.trim()), "framed bounded line read");
            reject(() -> new LanBoardProtocol.Entry(id(82),
                "Bad|Name", 1500, 0, 0, 0, 0, 0, 0, 0, now), "name injection");
            reject(() -> new LanBoardProtocol.Entry(id(82),
                "Good", 1500, 7, 0, 0, 2, 0, 0, 0, now),
                "provisional game count greater than matches");
            check(LanBoardProtocol.safeName("\n Phish | Player\r")
                .equals("Phish  Player"), "wire-safe name sanitizer");

            byte[] probe = LanBoardDiscovery.probe(token);
            byte[] answer = LanBoardDiscovery.reply(token);
            check(LanBoardDiscovery.readProbe(probe, probe.length).equals(token),
                "nonce-validated LAN board discovery");
            check(LanBoardDiscovery.validReply(answer, answer.length, token),
                "nonce-specific LAN reply");
            check(!LanBoardDiscovery.validReply(answer, answer.length,
                "1111111111111111"), "unrelated discovery nonce rejected");
            check(!LanBoardDiscovery.validReply(answer, answer.length - 1,
                token), "truncated UDP discovery response rejected");
            reject(() -> LanBoardDiscovery.probe("../spoof"),
                "malformed discovery nonce");

            LanBoardProtocol.Entry peer = new LanBoardProtocol.Entry(id(82),
                "Other", 1575, 3, 1100, 2, 8, 54, 12, 8, now);
            List<LanBoardProtocol.Entry> merged = LanBoardStore.merge(root.toFile(),
                me, List.of(peer), now);
            check(merged.size() == 2 && merged.get(0).id.equals(peer.id),
                "board sorts provisional SR high first");
            check(LanBoardStore.read(root.toFile()).size() == 2,
                "atomic cache persists two phones");
            check(LanBoardProtocol.readRow(LanBoardProtocol.row(peer).trim())
                .name.equals(peer.name), "store row round trip");
            LanBoardProtocol.Entry renamed = new LanBoardProtocol.Entry(id(82),
                "New Nick", 1575, 3, 1100, 2, 8, 54, 12, 8, now+1000);
            LanBoardStore.merge(root.toFile(), me, List.of(renamed), now+1000);
            List<LanBoardProtocol.Entry> renamedBoard=LanBoardStore.read(root.toFile());
            check(renamedBoard.size() == 2, "rename does not duplicate identity");
            check(renamedBoard.stream().anyMatch(e->e.name.equals("New Nick")),
                "renamed player updates in the board");
            LanBoardProtocol.Entry latest = new LanBoardProtocol.Entry(id(82),
                "Fresh Score", 1600, 5, 1400, 3, 9, 60, 17, 9, now+10000);
            LanBoardProtocol.Entry oldCached = new LanBoardProtocol.Entry(id(82),
                "Old Cached Score", 1450, 3, 1100, 2, 8, 54, 12, 8, now+5000);
            LanBoardStore.merge(root.toFile(), me, List.of(latest), now+10000);
            LanBoardStore.merge(root.toFile(), me, List.of(oldCached), now+11000);
            check(LanBoardStore.read(root.toFile()).stream()
                .anyMatch(e -> e.id.equals(id(82)) &&
                    e.name.equals("Fresh Score") && e.rating == 1600),
                "multi-phone sync preserves freshest peer snapshot");

            List<LanBoardProtocol.Entry> forgedSelf = LanBoardStore.merge(
                root.toFile(), me, List.of(new LanBoardProtocol.Entry(
                    me.id, "IMPERSONATOR", 2400, 4, 99999, 4, 4, 999, 9, 9, now+1000)),
                now+1000);
            check(forgedSelf.stream().anyMatch(e->e.id.equals(me.id) &&
                e.name.equals("Spartan One")), "remote cannot override local self card");
            // A player last synchronized months ago remains visible with
            // the original LAST SYNC date instead of silently expiring.
            final long oldSyncMs = now - 130L * 24 * 60 * 60 * 1000;
            LanBoardProtocol.Entry offline = new LanBoardProtocol.Entry(id(83),
                "Offline Player", 2200, 5, 100, 3, 5, 20, 2, 4, oldSyncMs);
            List<LanBoardProtocol.Entry> allPlayers = LanBoardStore.merge(
                root.toFile(), me, List.of(offline), now);
            check(allPlayers.size() == 3, "offline players kept on standings");
            check(allPlayers.stream().anyMatch(e ->
                e.id.equals(id(83)) && e.seenMs == oldSyncMs),
                "old snapshot timestamp preserved for offline player");
            List<LanBoardProtocol.Entry> reopened = LanBoardStore.merge(
                root.toFile(), me, null, now + 7L * 24 * 60 * 60 * 1000);
            check(reopened.size() == 3 && reopened.stream().anyMatch(e ->
                e.id.equals(id(83)) && e.seenMs == oldSyncMs),
                "disconnected player remains after reopen without peer");
            LanBoardProtocol.Entry future = new LanBoardProtocol.Entry(id(84),
                "Future Claimed", 1550, 1, 0, 0, 1, 1, 1, 0,
                now + 2L * 24 * 60 * 60 * 1000);
            check(LanBoardStore.merge(root.toFile(), me, List.of(future),
                now).size() == 3,
                "peer cards with implausible future timestamps ignored");
            Path cache = root.resolve(LanBoardProtocol.FILE);
            byte[] previous = Files.readAllBytes(cache);
            reject(() -> LanBoardStore.merge(root.toFile(), me,
                new ArrayList<>(java.util.Collections.nCopies(
                    LanBoardProtocol.MAX_PEERS+1,peer)),now),
                "peer list quota");
            check(java.util.Arrays.equals(previous,Files.readAllBytes(cache)),
                "invalid merge preserves original cache");
            Files.writeString(cache, LanBoardProtocol.row(peer) + "partial",
                StandardCharsets.US_ASCII);
            check(LanBoardStore.read(root.toFile()).size()==1,
                "truncated cache tail ignored");
            Files.write(cache,new byte[LanBoardProtocol.MAX_STORED_BYTES+1]);
            reject(() -> LanBoardStore.read(root.toFile()),"oversized cache");

        } finally {
            Files.deleteIfExists(root.resolve(CareerRecords.LOG_NAME));
            Files.deleteIfExists(root.resolve(LanBoardProtocol.FILE));
            Files.deleteIfExists(root.resolve("lan-board-v1.lock"));
            Files.deleteIfExists(root);
        }
        System.out.println("PASS LanBoardCoreTest: " + checks + " checks");
    }
}
