package com.halo.decomp;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Streaming repair of original Xbox maps. ZIP paths never become destinations. */
final class CampaignRepairImport {
    private static final long MAP_LIMIT = 256L * 1024 * 1024;
    private static final long PACK_LIMIT = 3L * 1024 * 1024 * 1024;
    interface Progress { void current(String message); }

    static final class Limit extends FilterInputStream {
        final long limit;
        final long[] total;
        long count;
        Limit(InputStream input, long limit, long[] total) {
            super(input); this.limit = limit; this.total = total;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int n = in.read(bytes, offset, length);
            if (n > 0) {
                count += n; total[0] += n;
                if (count > limit || total[0] > PACK_LIMIT)
                    throw new IOException("Repair file exceeds its safe size limit.");
            }
            return n;
        }
        @Override public void close() { /* caller owns the document/ZIP */ }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] bytes = new byte[65536];
        while (input.read(bytes) != -1) { /* finish this bounded ZIP entry */ }
    }

    static String install(InputStream source, File root, Progress progress) throws IOException {
        if (source == null) throw new IOException("Android could not read the selected file.");
        PushbackInputStream input = new PushbackInputStream(source, 4);
        byte[] magic = new byte[4];
        int done = 0, amount;
        while (done < 4 && (amount = input.read(magic, done, 4 - done)) != -1) done += amount;
        input.unread(magic, 0, done);
        long[] total = {0};
        if (done != 4 || magic[0] != 'P' || magic[1] != 'K')
            return SidewinderRepair.installDetected(new Limit(input, MAP_LIMIT, total), root);

        StringBuilder report = new StringBuilder();
        int entries = 0, maps = 0;
        // Import incrementally: retain successful repairs even if a later entry fails.
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 32) throw new IOException("The repair ZIP contains more than 32 entries.");
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("/") || path.indexOf(':') >= 0 ||
                    java.util.Arrays.asList(path.split("/", -1)).contains(".."))
                    throw new IOException("The repair ZIP contains an unsafe path.");
                boolean map = !entry.isDirectory() && path.toLowerCase(Locale.ROOT).endsWith(".map");
                Limit bounded = new Limit(zip, map ? MAP_LIMIT : 1024 * 1024, total);
                if (map) {
                    if (++maps > 11) throw new IOException("The repair ZIP contains more than 11 maps.");
                    if (progress != null) progress.current("Checking repair map " + maps + "...");
                    try { report.append(SidewinderRepair.installDetected(bounded, root)).append('\n'); }
                    catch (IOException failure) { report.append("✕ FAIL  ").append(path)
                        .append(": ").append(failure.getMessage()).append('\n'); }
                }
                drain(bounded); // includes already-healthy maps and metadata; always bounded
                zip.closeEntry();
            }
            if (maps == 0) throw new IOException("No original Xbox .map files were found in this ZIP.");
        } catch (IOException failure) {
            report.append("✕ FAIL  ZIP stopped: ").append(failure.getMessage())
                .append(" Earlier successful repairs are kept.\n");
        }
        return report.toString().trim();
    }

    private CampaignRepairImport() { }
}
