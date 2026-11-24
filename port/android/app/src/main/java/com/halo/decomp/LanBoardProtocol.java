package com.halo.decomp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Bounded, deliberately *unauthenticated* hotspot-only leaderboard cards.
 * No match claims are considered verified. No file transfer, passwords or URLs.
 * The protocol is entirely separate from the gameplay network version.
 */
final class LanBoardProtocol {
    static final int PORT = 5164;
    static final int MAX_PEERS = 24;
    static final int MAX_LINE = 256;
    static final int MAX_STORED_BYTES = 8192;
    static final String CARD = "HBC2";
    static final String ROW = "HBR2";
    static final String BOARD = "HBL2";
    static final String FILE = "lan-board-v1.txt";
    static final long MAX_AGE_MS = 30L * 24 * 3600 * 1000;

    static final class Entry {
        final String id, name;
        final int rating, ratedGames;
        final long xp, wins, matches, kills, deaths, assists, seenMs;

        Entry(String id, String name, int rating, int ratedGames, long xp,
              long wins, long matches, long kills, long deaths,
              long assists, long seenMs) {
            if (!idValid(id) || !nameValid(name) ||
                rating < LanRating.MIN || rating > LanRating.MAX ||
                ratedGames < 0 || ratedGames > 100_000 ||
                xp < 0 || xp > 1_000_000_000L || wins < 0 ||
                wins > matches || matches < 0 || matches > 1_000_000 ||
                kills < 0 || kills > 10_000_000 ||
                deaths < 0 || deaths > 10_000_000 ||
                assists < 0 || assists > 10_000_000 ||
                ratedGames > matches ||
                seenMs < 0 || seenMs > 4102444800000L)
                throw new IllegalArgumentException("Invalid LAN board card");
            this.id = id; this.name = name; this.rating = rating;
            this.ratedGames = ratedGames; this.xp = xp;
            this.wins = wins; this.matches = matches;
            this.kills = kills; this.deaths = deaths;
            this.assists = assists; this.seenMs = seenMs;
        }

        Entry seen(long now) {
            return new Entry(id, name, rating, ratedGames, xp, wins,
                matches, kills, deaths, assists, now);
        }
    }

    private LanBoardProtocol() { }

    static boolean idValid(String id) {
        return id != null && id.matches("[0-9a-f]{32}");
    }

    static boolean nonceValid(String nonce) {
        return nonce != null && nonce.matches("[0-9a-f]{16}");
    }

    static boolean nameValid(String name) {
        if (name == null || name.isEmpty() ||
            name.codePointCount(0, name.length()) > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isISOControl(ch) || ch == '|') return false;
        }
        return name.trim().length() > 0 &&
            name.equals(name.trim());
    }

    static String safeName(String name) {
        if (name == null) return "Spartan";
        StringBuilder safe = new StringBuilder();
        int points = 0;
        for (int i = 0; i < name.length() && points < 16;) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '|' || Character.isISOControl(cp) ||
                Character.isSurrogate((char)cp)) continue;
            safe.appendCodePoint(cp);
            points++;
        }
        String filtered = safe.toString().trim();
        return filtered.isEmpty() ? "Spartan" : filtered;
    }

    private static String nameWire(String name) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            name.getBytes(StandardCharsets.UTF_8));
    }

    private static String readName(String encoded) throws IOException {
        if (!encoded.matches("[A-Za-z0-9_-]{2,100}"))
            throw new IOException("Invalid encoded name");
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException bad) {
            throw new IOException("Malformed encoded name", bad);
        }
        String name = new String(bytes, StandardCharsets.UTF_8);
        if (!nameValid(name) || !nameWire(name).equals(encoded))
            throw new IOException("Invalid LAN player name");
        return name;
    }

    private static long number(String field, long ceiling) throws IOException {
        if (field == null || !field.matches("(0|[1-9][0-9]{0,12})"))
            throw new IOException("Invalid leaderboard number");
        try {
            long n = Long.parseLong(field);
            if (n < 0 || n > ceiling) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException bad) {
            throw new IOException("Out of range leaderboard value", bad);
        }
    }

    static String card(String nonce, Entry e) {
        if (!nonceValid(nonce)) throw new IllegalArgumentException("Bad nonce");
        return CARD + "|" + nonce + "|" + e.id + "|" + nameWire(e.name) +
            "|" + e.rating + "|" + e.ratedGames + "|" + e.xp +
            "|" + e.wins + "|" + e.matches + "|" + e.kills +
            "|" + e.deaths + "|" + e.assists + "\n";
    }

    static Entry readCard(String line, String nonce) throws IOException {
        if (line == null || line.length() > MAX_LINE ||
            !nonceValid(nonce))
            throw new IOException("Bad LAN leaderboard card");
        String[] p = line.split("\\|", -1);
        if (p.length != 12 || !CARD.equals(p[0]) ||
            !nonce.equals(p[1]) || !idValid(p[2]))
            throw new IOException("Invalid card protocol or identity");
        return parseEntry(p, 2, 0);
    }

    static String row(Entry e) {
        return ROW + "|" + e.id + "|" + nameWire(e.name) +
            "|" + e.rating + "|" + e.ratedGames + "|" + e.xp +
            "|" + e.wins + "|" + e.matches + "|" + e.kills +
            "|" + e.deaths + "|" + e.assists +
            "|" + e.seenMs + "\n";
    }

    static Entry readRow(String line) throws IOException {
        if (line == null || line.length() > MAX_LINE)
            throw new IOException("Bad leaderboard row length");
        String[] p = line.split("\\|", -1);
        if (p.length != 12 || !ROW.equals(p[0]) || !idValid(p[1]))
            throw new IOException("Bad leaderboard row identity");
        return parseEntry(p, 1, number(p[11], 4102444800000L));
    }

    private static Entry parseEntry(String[] p, int offset, long timestamp)
            throws IOException {
        try {
            return new Entry(p[offset], readName(p[offset+1]),
                (int)number(p[offset+2], LanRating.MAX),
                (int)number(p[offset+3], 100000),
                number(p[offset+4], 1000000000),
                number(p[offset+5], 1000000),
                number(p[offset+6], 1000000),
                number(p[offset+7], 10000000),
                number(p[offset+8], 10000000),
                number(p[offset+9], 10000000),
                timestamp);
        } catch (IllegalArgumentException bad) {
            throw new IOException("Inconsistent LAN leaderboard entry", bad);
        }
    }

    static String header(String nonce, int count) {
        if (!nonceValid(nonce) || count < 1 || count > MAX_PEERS)
            throw new IllegalArgumentException("Invalid board header");
        return BOARD + "|" + nonce + "|" + count + "\n";
    }

    static int readHeader(String line, String expected) throws IOException {
        String[] p = line == null ? new String[0] : line.split("\\|", -1);
        if (p.length != 3 || !BOARD.equals(p[0]) ||
            !nonceValid(expected) || !expected.equals(p[1]))
            throw new IOException("Host returned unrelated leaderboard");
        int count = (int)number(p[2], MAX_PEERS);
        if (count < 1) throw new IOException("Empty LAN board");
        return count;
    }

    /** Read a network line without BufferedReader.readLine's unbounded
     * allocation or trusting a peer-supplied Content-Length. */
    static String boundedLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        for (int i = 0; i < MAX_LINE; i++) {
            int c = in.read();
            if (c < 0) throw new IOException("Truncated leaderboard request");
            if (c == '\n')
                return new String(out.toByteArray(), StandardCharsets.US_ASCII);
            if (c < 32 || c > 126)
                throw new IOException("Non-ASCII leaderboard frame");
            out.write(c);
        }
        throw new IOException("Oversized LAN leaderboard frame");
    }
}
