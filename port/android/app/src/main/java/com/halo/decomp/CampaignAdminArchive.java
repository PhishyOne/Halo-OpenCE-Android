package com.halo.decomp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Backup of verified *original Xbox* campaign map caches for private repair.
 * Always validates a map before archiving it. Never copies invalid/missing
 * maps; never alters app-owned maps, saves or existing .previous backups. */
final class CampaignAdminArchive {
    interface Progress {
        void current(String name, int position, int count);
    }

    static final class Report {
        final int valid, missing, invalid;
        final long bytes;
        Report(int valid, int missing, int invalid, long bytes) {
            this.valid = valid;
            this.missing = missing;
            this.invalid = invalid;
            this.bytes = bytes;
        }
    }

    private static final int COPY = 64 * 1024;
    private static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    private CampaignAdminArchive() { }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(64);
        for (byte octet : digest)
            sb.append(String.format(Locale.ROOT, "%02x", octet & 255));
        return sb.toString();
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }

    static Report export(File dataRoot, OutputStream destination,
                         Progress progress) throws IOException {
        if (dataRoot == null || destination == null)
            throw new IOException("Missing Halo map source or archive destination");
        File maps = new File(dataRoot, "maps");
        if (!maps.isDirectory() || Files.isSymbolicLink(maps.toPath()))
            throw new IOException("Halo's original Xbox maps folder is unavailable");

        StringBuilder manifest = new StringBuilder();
        manifest.append("HALO-CAMPAIGN-REPAIR-LIBRARY V1\n")
            .append("Archive contains only the original Xbox maps that passed ")
            .append("header, scenario and complete-stream validation.\n")
            .append("Never import a failed map as a repair source.\n")
            .append("STATUS|MAP|SOURCE_BYTES|SHA256_OR_REASON\n");
        int good = 0, missing = 0, invalid = 0;
        long totalBytes = 0;

        // Map data is already deflated on Xbox. ZIP level 0 preserves streaming
        // memory and avoids an expensive redundant compression pass.
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(destination, COPY),
                StandardCharsets.UTF_8)) {
            zip.setLevel(0);
            String[] filenames = SidewinderRepair.CAMPAIGN_MAP_FILES;
            for (int index = 0; index < filenames.length; index++) {
                String name = filenames[index];
                if (progress != null)
                    progress.current(name, index + 1, filenames.length);
                File source = new File(maps, name);
                if (!source.isFile() || Files.isSymbolicLink(source.toPath())) {
                    missing++;
                    manifest.append("MISSING|").append(name).append("|0|No verified copy\n");
                    continue;
                }
                long length = source.length();
                if (length < 2048 || length > MAX_SOURCE_BYTES) {
                    invalid++;
                    manifest.append("INVALID|").append(name)
                        .append("|").append(length)
                        .append("|Unsupported Xbox map size\n");
                    continue;
                }
                try {
                    SidewinderRepair.validate(source, name);
                } catch (IOException failed) {
                    invalid++;
                    manifest.append("INVALID|").append(name).append("|")
                        .append(length).append("|Xbox cache validation failed\n");
                    continue;
                }
                MessageDigest digest = sha256();
                ZipEntry entry = new ZipEntry("maps/" + name);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                byte[] buffer = new byte[COPY];
                long copied = 0;
                try (FileInputStream input = new FileInputStream(source)) {
                    int amount;
                    while ((amount = input.read(buffer)) != -1) {
                        if (amount == 0) continue;
                        if (copied > MAX_SOURCE_BYTES - amount)
                            throw new IOException(name + " exceeded archive limit");
                        zip.write(buffer, 0, amount);
                        digest.update(buffer, 0, amount);
                        copied += amount;
                    }
                }
                zip.closeEntry();
                if (copied != length || source.length() != length)
                    throw new IOException(name + " changed during archive; discard ZIP");
                good++;
                totalBytes += copied;
                manifest.append("PASS|").append(name).append("|")
                    .append(copied).append("|").append(hex(digest.digest()))
                    .append('\n');
            }
            manifest.append("SUMMARY|valid=").append(good)
                .append("|missing=").append(missing)
                .append("|invalid=").append(invalid)
                .append("|Only PASS entries appear under maps/\n");
            ZipEntry report = new ZipEntry("CAMPAIGN-MAP-INVENTORY.txt");
            report.setTime(0L);
            zip.putNextEntry(report);
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new Report(good, missing, invalid, totalBytes);
    }
}
