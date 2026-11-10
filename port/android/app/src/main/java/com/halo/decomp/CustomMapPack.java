package com.halo.decomp;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Optional ZIP fallback when Android's document provider hides unknown
 * .map extensions. The archive is read through the system file picker,
 * without extracting to Downloads or relying on broad storage access.
 *
 * Each entry is validated and copied by CustomMapImporter. No entry name
 * ever becomes a filesystem path: only the safe basename is passed to the
 * importer. Existing files are never overwritten.
 */
final class CustomMapPack {
    static final int MAX_ENTRIES = 32;
    static final long MAX_TOTAL_BYTES = 600L * 1024 * 1024;

    static final class Result {
        final int files;
        final long bytes;
        Result(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    interface Progress {
        void onFile(String filename, int index) throws IOException;
    }

    private CustomMapPack() { }

    private static String safeEntryName(String full) throws IOException {
        if (full == null || full.isEmpty() || full.indexOf('\\') >= 0 ||
            full.startsWith("/") || full.indexOf('\u0000') >= 0)
            throw new IOException("Invalid ZIP entry path.");
        int slash = full.indexOf('/');
        String name = full;
        if (slash >= 0) {
            // Allow ZIPs created from a single enclosing directory, but
            // reject traversal, absolute paths and deeper hierarchies.
            if (slash == 0 || full.indexOf('/', slash + 1) >= 0)
                throw new IOException("Nested ZIP directory paths are unsupported.");
            String folder = full.substring(0, slash);
            if (!folder.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                throw new IOException("Unsafe ZIP directory name.");
            name = full.substring(slash + 1);
        }
        if (!CustomMapImporter.isValidName(name))
            throw new IOException("ZIP contains a non-Halo or unsafe map filename: " + name);
        return name;
    }

    private static final class EntryLimit extends FilterInputStream {
        private long count;
        private final long allowed;

        EntryLimit(InputStream input, long allowed) {
            super(input);
            this.allowed = allowed;
        }

        private void count(long length) throws IOException {
            if (length <= 0)
                return;
            if (count > allowed - length)
                throw new IOException("ZIP expands beyond the safe map-pack limit.");
            count += length;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) count(n);
            return n;
        }
    }

    static Result install(InputStream input, File dataRoot, Progress progress)
            throws IOException {
        if (input == null)
            throw new IOException("No ZIP map pack was supplied.");
        int count = 0;
        long total = 0;
        Set<String> names = new HashSet<>();

        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (count >= MAX_ENTRIES)
                    throw new IOException("ZIP contains more than 32 map files.");
                String name = safeEntryName(entry.getName());
                if (!names.add(name.toLowerCase(Locale.ROOT)))
                    throw new IOException("ZIP contains duplicate map name " + name);
                if (entry.getSize() > MAX_TOTAL_BYTES - total)
                    throw new IOException("ZIP exceeds the permitted unpacked size.");
                if (progress != null)
                    progress.onFile(name, count + 1);
                EntryLimit limited = new EntryLimit(zip, MAX_TOTAL_BYTES - total);
                CustomMapImporter.Result result =
                    CustomMapImporter.install(limited, dataRoot, name);
                zip.closeEntry(); // validates archive entry CRC
                total += result.size;
                count++;
            }
        }
        if (count == 0)
            throw new IOException("ZIP contains no Custom Edition .map files.");
        return new Result(count, total);
    }
}
