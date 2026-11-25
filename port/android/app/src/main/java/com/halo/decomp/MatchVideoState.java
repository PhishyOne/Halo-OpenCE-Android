package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

/** Small, consent-scoped IPC files, read by the launcher and native game.
 * No screen content is stored here. Uptime uses the native monotonic clock. */
final class MatchVideoState {
    static final String FOREGROUND = "video-foreground-v1.txt";
    static final String STATE = "video-match-state-v1.txt";
    static final String TOKEN = "halo-video-launch-token";
    static final String VISIBILITY = "com.halo.decomp.VIDEO_VISIBILITY";
    static final long FRESH_MS = 2500;

    final String token, id;
    final boolean active;
    final int tick;
    final long uptime;

    private MatchVideoState(String token, String id, boolean active, int tick, long uptime) {
        this.token = token; this.id = id; this.active = active;
        this.tick = tick; this.uptime = uptime;
    }

    static boolean id(String value) {
        return value != null && value.matches("[0-9a-f]{32}");
    }

    static MatchVideoState parse(String row) {
        if (row == null || row.length() > 160 || !row.endsWith("\n")) return null;
        String[] p = row.substring(0, row.length() - 1).split("\\|", -1);
        if (p.length != 6 || !"VM1".equals(p[0]) || !id(p[1]) || !id(p[2]) ||
            !("A".equals(p[3]) || "I".equals(p[3])) ||
            !p[4].matches("[0-9]{1,7}") || !p[5].matches("[0-9]{1,16}")) return null;
        try {
            int tick = Integer.parseInt(p[4]);
            long uptime = Long.parseLong(p[5]);
            if (tick > 8640000 || uptime <= 0) return null;
            return new MatchVideoState(p[1], p[2], "A".equals(p[3]), tick, uptime);
        } catch (NumberFormatException invalid) { return null; }
    }

    boolean fresh(String expected, long now, long armedAt) {
        return token.equals(expected) && uptime >= Math.max(0, armedAt - 2000) &&
            uptime <= now + 100 && now - uptime <= FRESH_MS;
    }

    private static String readSmall(File file) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            file.length() <= 0 || file.length() > 160) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return bytes.length <= 160 ? new String(bytes, StandardCharsets.US_ASCII) : null;
        } catch (IOException | SecurityException ignored) { return null; }
    }

    static MatchVideoState read(File root) {
        return root == null ? null : parse(readSmall(new File(root, STATE)));
    }

    static boolean foreground(File root, String token) {
        return root != null && id(token) &&
            ("FG1|" + token + "|1\n").equals(readSmall(new File(root, FOREGROUND)));
    }

    static void writeForeground(File root, String token, boolean visible) throws IOException {
        if (root == null || !id(token)) return;
        File target = new File(root, FOREGROUND);
        File tmp = new File(root, FOREGROUND + ".tmp");
        if (Files.isSymbolicLink(tmp.toPath()) || Files.isSymbolicLink(target.toPath()))
            throw new IOException("Unsafe video state path");
        Files.write(tmp.toPath(), ("FG1|" + token + "|" + (visible ? "1" : "0") + "\n")
            .getBytes(StandardCharsets.US_ASCII));
        try {
            Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
