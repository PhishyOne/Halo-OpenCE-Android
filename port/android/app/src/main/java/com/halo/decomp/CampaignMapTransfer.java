package com.halo.decomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Read-only campaign map audit and one-file transfer through the Android
 * document picker. This handles STOCK Xbox v5 maps only. The separate
 * Custom Edition map-sharing protocol cannot send these game assets.
 *
 * No save file, map, backup, cache, or unrelated file is modified here.
 * Map import is handled by SidewinderRepair.install(), which validates an
 * allowlisted scenario and backs up the original before replacing it.
 */
final class CampaignMapTransfer {
    static final class Check {
        final String filename, title, detail;
        final boolean passed;
        Check(String name, boolean passed, String detail) {
            this.filename = name; this.title = SidewinderRepair.titleFor(name);
            this.passed = passed; this.detail = detail;
        }
    }

    static java.util.List<Check> checks(File root, Progress progress) {
        java.util.List<Check> results = new java.util.ArrayList<>();
        String[] campaign = SidewinderRepair.CAMPAIGN_MAP_FILES;
        for (int i = 0; i <= campaign.length; i++) {
            String name = i == campaign.length ? SidewinderRepair.SIDEWINDER_MAP : campaign[i];
            if (progress != null) progress.checking(name, i + 1, campaign.length + 1);
            try {
                File file = checkedMap(root, name);
                SidewinderRepair.validate(file, name);
                results.add(new Check(name, true, "File check passed"));
            } catch (IOException failure) {
                results.add(new Check(name, false, failure.getMessage()));
            }
        }
        return results;
    }
    interface Progress {
        void checking(String filename, int done, int total);
    }

    static final class Export {
        final String filename;
        final long bytes;
        final String sha256;
        Export(String filename, long bytes, String sha256) {
            this.filename = filename;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final int CHUNK = 65536;
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private CampaignMapTransfer() { }

    private static File checkedMap(File dataRoot, String name) throws IOException {
        boolean allowlisted = SidewinderRepair.SIDEWINDER_MAP.equals(name);
        for (String candidate : SidewinderRepair.CAMPAIGN_MAP_FILES)
            if (candidate.equals(name)) allowlisted = true;
        if (!allowlisted)
            throw new IOException("Only the ten original campaign maps or Xbox Sidewinder may be transferred.");
        if (dataRoot == null)
            throw new IOException("Halo's game-data directory is unavailable.");
        File maps = new File(dataRoot, "maps");
        if (!maps.isDirectory() || Files.isSymbolicLink(maps.toPath()))
            throw new IOException("Halo's maps folder is unavailable.");
        File file = new File(maps, name);
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()))
            throw new IOException(name + " is missing; import it from your own Halo disc data.");
        if (file.length() < 2048 || file.length() > MAX_BYTES)
            throw new IOException(name + " is outside the supported Xbox map size range.");
        return file;
    }

    /** Expensive by design: full decompression catches truncated/corrupt
     * source that a superficially correct cache header would miss.
     * Run on a background thread, never the Android UI thread. */
    static String audit(File root, Progress progress) {
        StringBuilder result = new StringBuilder("ORIGINAL HALO CAMPAIGN MAP AUDIT\n");
        int usable = 0, missing = 0, invalid = 0;
        int total = SidewinderRepair.CAMPAIGN_MAP_FILES.length;
        for (int i = 0; i < total; i++) {
            String name = SidewinderRepair.CAMPAIGN_MAP_FILES[i];
            if (progress != null) progress.checking(name, i + 1, total);
            result.append(name).append(" (")
                .append(SidewinderRepair.CAMPAIGN_MAP_TITLES[i]).append("): ");
            try {
                File file = checkedMap(root, name);
                long decoded = SidewinderRepair.validate(file, name);
                usable++;
                result.append("PASS (").append(file.length() / 1048576)
                    .append(" MiB source, ").append(decoded / 1048576)
                    .append(" MiB decoded)");
            } catch (IOException failure) {
                if (failure.getMessage() != null &&
                    failure.getMessage().contains(" is missing;"))
                    missing++;
                else
                    invalid++;
                result.append("FAILED: ").append(failure.getMessage());
            }
            result.append('\n');
        }
        result.append("Result: ").append(usable).append("/").append(total)
            .append(" passed, ").append(missing).append(" missing, ")
            .append(invalid).append(" invalid.\n")
            .append("This checks file identity, zlib stream and tags signature; ")
            .append("it does not prove a map's complete gameplay or renderer works. ")
            .append("Repair failed maps from verified original game data before hosting.");
        return result.toString();
    }

    /** User explicitly chooses a destination via ACTION_CREATE_DOCUMENT.
     * Export a single validated cache without silently copying bulk maps.
     * The receiving player can import it with MAP REPAIR's scenario picker. */
    static Export export(File root, String name, OutputStream destination)
            throws IOException {
        if (destination == null)
            throw new IOException("No Android file destination was selected.");
        File file = checkedMap(root, name);
        SidewinderRepair.validate(file, name);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException problem) {
            throw new IOException("SHA-256 is unavailable.", problem);
        }
        long written = 0;
        byte[] chunk = new byte[CHUNK];
        try (FileInputStream input = new FileInputStream(file)) {
            int amount;
            while ((amount = input.read(chunk)) != -1) {
                if (amount == 0) continue;
                if (written > MAX_BYTES - amount)
                    throw new IOException("Map source changed or exceeded its limit during export.");
                destination.write(chunk, 0, amount);
                digest.update(chunk, 0, amount);
                written += amount;
            }
        }
        destination.flush();
        if (written != file.length())
            throw new IOException("Map changed during export; retry with Halo closed.");
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return new Export(name, written, hex.toString());
    }
}
