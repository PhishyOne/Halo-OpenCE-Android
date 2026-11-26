package com.halo.decomp;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, nonce-bound identity ping on the existing opted-in board
 * TCP listener. Presence is not a career card, proof of identity or a
 * multiplayer roster. Never save presence as a leaderboard timestamp. */
final class LanBoardPresence {
    static final String QUERY = "HBP1", ANSWER = "HPA1";
    static final long FRESH_MS = 15000;
    static final int MAX_PEERS = 16;

    static final class Peer {
        final String id, name, ip;
        final boolean gameOpen;

        private Peer(String id, String name, String ip, boolean gameOpen) {
            this.id = id; this.name = name; this.ip = ip; this.gameOpen = gameOpen;
        }
        static Peer legacy(String ip) {
            if (LanUpdateProtocol.privateAddress(ip) == null)
                throw new IllegalArgumentException("Not a private peer IP");
            return new Peer(null, "Unnamed Halo", ip, false);
        }
        boolean named() { return id != null; }
        String location() { return named() ? (gameOpen ? "Game open" : "Launcher") : "Discovery only"; }
    }

    static final class Snapshot {
        final List<Peer> peers;
        final long observed;
        final boolean scanned;
        Snapshot(List<Peer> observations, long observed, boolean scanned) {
            // Match identity, not display name. Never duplicate a player
            // merely because their phone answered from two interfaces.
            Map<String, Peer> unique = new LinkedHashMap<>();
            for (Peer p : observations) {
                String key = p.named() ? p.id : p.ip;
                if (!unique.containsKey(key) && unique.size() >= MAX_PEERS) continue;
                unique.putIfAbsent(key, p);
            }
            List<Peer> values = new ArrayList<>(unique.values());
            values.sort(Comparator.comparing((Peer p) -> !p.named())
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(p -> p.ip));
            peers = Collections.unmodifiableList(values);
            this.observed = observed;
            this.scanned = scanned;
        }
        boolean fresh(long now) { return scanned && now >= observed && now - observed <= FRESH_MS; }
        static Snapshot empty() { return new Snapshot(Collections.emptyList(), 0, false); }
        int ready() {
            int count = 0;
            for (Peer p : peers) if (p.named()) count++;
            return count;
        }
        List<String> targets(long now) {
            List<String> ips = new ArrayList<>();
            if (fresh(now))
                for (Peer p : peers) ips.add(p.ip);
            return ips;
        }
    }

    private LanBoardPresence() { }

    static String query(String nonce) {
        if (!LanBoardProtocol.nonceValid(nonce)) throw new IllegalArgumentException("Bad presence nonce");
        return QUERY + "|" + nonce + "\n";
    }
    static String readQuery(String line) {
        if (line == null || line.length() != QUERY.length() + 17 ||
            !line.startsWith(QUERY + "|")) return null;
        String nonce = line.substring(QUERY.length() + 1);
        return LanBoardProtocol.nonceValid(nonce) ? nonce : null;
    }
    static String answer(String nonce, String id, String name, boolean gameOpen) {
        if (!LanBoardProtocol.nonceValid(nonce) || !LanBoardProtocol.idValid(id) ||
            !LanBoardProtocol.nameValid(name)) throw new IllegalArgumentException("Bad presence identity");
        return ANSWER + "|" + nonce + "|" + id + "|" +
            LanBoardProtocol.nameWire(name) + "|" + (gameOpen ? "G" : "L") + "\n";
    }
    static Peer readAnswer(String line, String nonce, String ip) throws IOException {
        if (line == null || line.length() > LanBoardProtocol.MAX_LINE ||
            LanUpdateProtocol.privateAddress(ip) == null || !LanBoardProtocol.nonceValid(nonce))
            throw new IOException("Invalid presence reply");
        String[] p = line.split("\\|", -1);
        if (p.length != 5 || !ANSWER.equals(p[0]) || !nonce.equals(p[1]) ||
            !LanBoardProtocol.idValid(p[2]) || !("G".equals(p[4]) || "L".equals(p[4])))
            throw new IOException("Unrelated presence reply");
        return new Peer(p[2], LanBoardProtocol.readName(p[3]), ip, "G".equals(p[4]));
    }

    static Peer exchange(Socket socket, String challenge, String ip) throws IOException {
        socket.getOutputStream().write(query(challenge).getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return readAnswer(presenceLine(socket), challenge, ip);
    }

    /** Read one bounded ASCII frame with an absolute deadline. A slow or
     * malformed peer cannot keep a foreground scan thread alive. */
    private static String presenceLine(Socket socket) throws IOException {
        long end = System.nanoTime() + 850_000_000L;
        StringBuilder line = new StringBuilder(128);
        java.io.InputStream in = socket.getInputStream();
        while (line.length() < LanBoardProtocol.MAX_LINE) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted())
                throw new IOException("Presence reply timed out");
            socket.setSoTimeout((int)Math.max(1, remaining / 1_000_000L));
            int ch = in.read();
            if (ch == '\n') return line.toString();
            if (ch < 32 || ch > 126) throw new IOException("Invalid presence frame");
            line.append((char)ch);
        }
        throw new IOException("Oversized presence reply");
    }

}
