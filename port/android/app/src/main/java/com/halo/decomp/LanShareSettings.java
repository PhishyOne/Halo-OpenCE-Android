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

/** Local sharing is default-on, with independent video opt-out.
 * Preserve explicit legacy career opt-out. Both processes read the
 * same atomic file; the video preference survives career toggling. */
final class LanShareSettings {
    private static final String FILE = "halo-lan-sharing-v1.txt";
    private LanShareSettings() { }
    static LanShareState read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        try {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                return LanShareState.defaults(context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
                    .getBoolean("lan_board_share", true));
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.length() != 8) return new LanShareState(false, false);
            String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
            return LanShareState.decode(value);
        } catch (IOException | RuntimeException invalid) {
            return new LanShareState(false, false);
        }
    }
    /** Materialize even the default-on state for native code. */
    static synchronized boolean prepareForGame(Context context) {
        return set(context, read(context));
    }

    static synchronized boolean setCareer(Context context, boolean enabled) {
        return set(context, read(context).withCareer(enabled));
    }

    static synchronized boolean setVideos(Context context, boolean enabled) {
        return set(context, read(context).withVideos(enabled));
    }

    private static boolean set(Context context, LanShareState state) {
        File root = context.getFilesDir();
        File temp = null;
        try {
            temp = File.createTempFile("halo-sharing-", ".tmp", root);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(state.encode().getBytes(StandardCharsets.US_ASCII));
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
