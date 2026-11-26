package com.halo.decomp;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

/** One local-only sharing switch, on by default. Preserve an explicitly
 * disabled legacy preference; otherwise enable stats and finished reels.
 * The private atomic file is read by both launcher and game processes. */
final class LanShareSettings {
    private static final String FILE = "halo-lan-sharing-v1.txt";
    static final class State {
        final boolean career, reels;
        State(boolean career, boolean reels) {
            this.career = career; this.reels = career && reels;
        }
    }
    private LanShareSettings() { }
    static State read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        try {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                return new State(context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
                    .getBoolean("lan_board_share", true), true);
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() != 8) return new State(false, false);
            String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
            if ("LS1|1|1\n".equals(value)) return new State(true, true);
            return new State(false, false);
        } catch (IOException | RuntimeException invalid) {
            return new State(false, false);
        }
    }
    static synchronized boolean set(Context context, boolean enabled) {
        File root = context.getFilesDir();
        File temp = null;
        State state = new State(enabled, enabled);
        try {
            temp = File.createTempFile("halo-sharing-", ".tmp", root);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(("LS1|" + (state.career ? "1" : "0") + "|" +
                    (state.reels ? "1" : "0") + "\n").getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            File target = new File(root, FILE);
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // Preserve the old preference for rollback to an older APK.
            context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
                .edit().putBoolean("lan_board_share", state.career).apply();
            return true;
        } catch (IOException | RuntimeException failure) {
            Log.w("HaloLanBoard", "Could not update local sharing consent", failure);
            return false;
        } finally { if (temp != null) temp.delete(); }
    }
    static boolean career(Context context) { return read(context).career; }
    static boolean reels(Context context) { return read(context).reels; }
}
