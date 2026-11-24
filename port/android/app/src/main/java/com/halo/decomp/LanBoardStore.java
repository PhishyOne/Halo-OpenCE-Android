package com.halo.decomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny local, atomic leaderboard cache on the player's phone.
 * Client-supplied rows are clearly UNVERIFIED, never signed match receipts.
 * Does not touch HC3 career, XP, achievements, maps or saves. */
final class LanBoardStore {
    private LanBoardStore() { }

    static LanBoardProtocol.Entry current(String id, String name,
                                          CareerRecords.Career career, long now) {
        LanRating.Score practice = LanRating.calculate(career);
        return new LanBoardProtocol.Entry(id,
            LanBoardProtocol.safeName(name), practice.value,
            practice.matches, career.xp, career.wins, career.games(),
            career.kills, career.deaths, career.assists, now);
    }

    static List<LanBoardProtocol.Entry> read(File root) throws IOException {
        List<LanBoardProtocol.Entry> entries = new ArrayList<>();
        if (root == null || !root.isDirectory()) return entries;
        File file = new File(root, LanBoardProtocol.FILE);
        if (!file.isFile()) return entries;
        if (file.length() > LanBoardProtocol.MAX_STORED_BYTES ||
            file.length() < 0)
            throw new IOException("LAN board at its storage limit");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > LanBoardProtocol.MAX_STORED_BYTES)
            throw new IOException("LAN board changed during read");
        int end = 0;
        for (int i = bytes.length-1; i >= 0; i--)
            if (bytes[i] == '\n') { end = i + 1; break; }
        Map<String, LanBoardProtocol.Entry> byId = new LinkedHashMap<>();
        for (String text : new String(bytes, 0, end,
                StandardCharsets.UTF_8).split("\\n")) {
            if (text.isEmpty()) continue;
            try {
                LanBoardProtocol.Entry entry = LanBoardProtocol.readRow(text);
                LanBoardProtocol.Entry old = byId.get(entry.id);
                if (old == null || old.seenMs <= entry.seenMs)
                    byId.put(entry.id, entry);
            } catch (IOException invalidRow) {
                // A broken/partial entry cannot impersonate another player.
            }
        }
        entries.addAll(byId.values());
        order(entries);
        if (entries.size() > LanBoardProtocol.MAX_PEERS)
            return new ArrayList<>(entries.subList(0, LanBoardProtocol.MAX_PEERS));
        return entries;
    }

    static void order(List<LanBoardProtocol.Entry> entries) {
        entries.sort(Comparator
            .comparingInt((LanBoardProtocol.Entry e) -> e.ratedGames > 0 ? 1 : 0)
            .reversed()
            .thenComparing(Comparator.comparingInt(
                (LanBoardProtocol.Entry e) -> e.rating).reversed())
            .thenComparing(Comparator.comparingLong(
                (LanBoardProtocol.Entry e) -> e.xp).reversed())
            .thenComparing(e -> e.id));
    }

    /** Merge a local identity / peer cards. The local phone's record is
     * authoritative only for its own display, NOT for a public ranked ladder.
     * Rejected writes leave the preceding board untouched. */
    static synchronized List<LanBoardProtocol.Entry> merge(
            File root, LanBoardProtocol.Entry local,
            List<LanBoardProtocol.Entry> received, long now) throws IOException {
        if (root == null || !root.isDirectory() || local == null ||
            !LanBoardProtocol.idValid(local.id) || now < 1500000000000L)
            throw new IOException("Unusable local LAN leaderboard identity");
        if (received != null && received.size() > LanBoardProtocol.MAX_PEERS)
            throw new IOException("Too many leaderboard peers");
        Map<String, LanBoardProtocol.Entry> byId = new LinkedHashMap<>();
        for (LanBoardProtocol.Entry entry : read(root)) {
            // A disconnected player's last valid data is useful even
            // months later. Never delete a card merely because it aged.
            if (entry.seenMs > now + 86400000L) continue;
            if (!entry.id.equals(local.id)) byId.put(entry.id, entry);
        }
        if (received != null) {
            for (LanBoardProtocol.Entry entry : received) {
                if (entry.id.equals(local.id)) continue;
                if (entry.seenMs > now + 86400000L) continue;
                // A stale cached copy from another peer must not
                // overwrite a fresher snapshot already received.
                LanBoardProtocol.Entry old = byId.get(entry.id);
                if (old == null || old.seenMs < entry.seenMs)
                    byId.put(entry.id, entry);
            }
        }
        byId.put(local.id, local.seen(now));
        List<LanBoardProtocol.Entry> board = new ArrayList<>(byId.values());
        order(board);
        if (board.size() > LanBoardProtocol.MAX_PEERS) {
            // Never drop this phone's own card even if many peers outrank it.
            board.subList(LanBoardProtocol.MAX_PEERS, board.size()).clear();
            boolean selfPresent = false;
            for (LanBoardProtocol.Entry e : board)
                if (e.id.equals(local.id)) selfPresent = true;
            if (!selfPresent) {
                board.set(board.size() - 1, local.seen(now));
                order(board);
            }
        }
        StringBuilder lines = new StringBuilder();
        for (LanBoardProtocol.Entry e : board) lines.append(LanBoardProtocol.row(e));
        byte[] payload = lines.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > LanBoardProtocol.MAX_STORED_BYTES)
            throw new IOException("Leaderboard does not fit its bounded file");
        File target = new File(root, LanBoardProtocol.FILE);
        File temp = File.createTempFile("halo-lan-board-", ".pending", root);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(payload);
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temp.delete();
        }
        return board;
    }
}
