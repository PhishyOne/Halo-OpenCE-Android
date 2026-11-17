package com.halo.decomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Import selected Xbox Halo map caches into the app's own Android maps
 * directory. The allowlist is fixed to Sidewinder and the ten original
 * stock campaign maps, with separate backup and rollback paths. No root,
 * ADB, full ISO,
 * ZIP extraction, or arbitrary filesystem access is required.
 *
 * Verify the entire zlib stream, declared size and internal tags signature
 * before renaming the existing map to a backup. A crash between the two
 * renames leaves the old map as <map>.previous, recoverable with
 * restorePrevious(). Never discard an existing backup implicitly.
 *
 * This class has no Android dependency so the source can be tested with a
 * stock JDK before packaging the game.
 */
final class SidewinderRepair {
    private static final int HEADER_BYTES = 0x800;
    private static final int TAG_HEADER_BYTES = 0x24;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    // Xbox cache reader uses at most 0x11600000 decompressed bytes.
    private static final long MAX_DECOMPRESSED_BYTES = 0x11600000L;
    // Allow different disc revisions while bounding untrusted input size.
    private static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    static final String SIDEWINDER_MAP = "sidewinder.map";
    static final String CARTOGRAPHER_MAP = "b30.map";

    /* Original Xbox campaign level IDs in the same order as the native
     * Halo campaign menu (custom_edition_maps.c). Never use an arbitrary
     * document display name as a game map destination. */
    static final String[] CAMPAIGN_MAP_FILES = {
        "a10.map", "a30.map", "a50.map", "b30.map", "b40.map",
        "c10.map", "c20.map", "c40.map", "d20.map", "d40.map"
    };
    static final String[] CAMPAIGN_MAP_TITLES = {
        "The Pillar of Autumn", "Halo", "The Truth and Reconciliation",
        "The Silent Cartographer", "Assault on the Control Room",
        "343 Guilty Spark", "The Library", "Two Betrayals",
        "Keyes", "The Maw"
    };

    /** Fixed allowlist protects map destinations; no arbitrary file names
     * or app-private paths come from user-selected Android documents. */
    private static String scenarioFor(String targetMap) throws IOException {
        if (SIDEWINDER_MAP.equals(targetMap))
            return "sidewinder";
        for (String scenario : CAMPAIGN_MAP_FILES)
            if (scenario.equals(targetMap))
                return scenario.substring(0, scenario.length() - 4);
        throw new IOException("This map cannot be repaired by the launcher.");
    }

    static String titleFor(String targetMap) {
        for (int i = 0; i < CAMPAIGN_MAP_FILES.length; i++)
            if (CAMPAIGN_MAP_FILES[i].equals(targetMap))
                return CAMPAIGN_MAP_TITLES[i];
        return "Sidewinder";
    }

    static final class Result {
        final long sourceBytes;
        final long decodedBytes;
        final boolean backedUpOriginal;
        final String sha256;

        Result(long sourceBytes, long decodedBytes, boolean backedUpOriginal,
                String sha256) {
            this.sourceBytes = sourceBytes;
            this.decodedBytes = decodedBytes;
            this.backedUpOriginal = backedUpOriginal;
            this.sha256 = sha256;
        }
    }

    private SidewinderRepair() { }

    /** Read identity from the cache, never its document/ZIP filename. */
    static String detect(byte[] header) throws IOException {
        if (header.length != HEADER_BYTES || !matches(header, 0, "daeh") ||
            !matches(header, 0x7FC, "toof") || u32(header, 4) != 5)
            throw new IOException("Choose an original Xbox .map file or a campaign repair ZIP. Custom maps belong in Custom Maps.");
        int end = 0x20;
        while (end < 0x40 && header[end] != 0) end++;
        String name = new String(header, 0x20, end - 0x20,
            StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT) + ".map";
        scenarioFor(name);
        return name;
    }

    static String installDetected(InputStream source, File dataRoot) throws IOException {
        java.io.PushbackInputStream input = new java.io.PushbackInputStream(source, HEADER_BYTES);
        byte[] header = new byte[HEADER_BYTES];
        readExactly(input, header);
        String name = detect(header);
        input.unread(header);
        File current = new File(mapsDirectory(dataRoot), name);
        boolean healthy = false;
        try { validate(current, name); healthy = true; }
        catch (IOException needsRepair) { /* missing or invalid: install below */ }
        if (healthy) return "✓ PASS  " + titleFor(name) + " — already healthy; kept your map.";
        install(input, dataRoot, name, true);
        return "✓ PASS  " + titleFor(name) + " — repaired. Your earlier copies are kept.";
    }

    private static boolean matches(byte[] data, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || offset + expected.length > data.length)
            return false;
        for (int i = 0; i < expected.length; i++)
            if (data[offset + i] != expected[i])
                return false;
        return true;
    }

    private static long u32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF) |
            (((long) data[offset + 1] & 0xFF) << 8) |
            (((long) data[offset + 2] & 0xFF) << 16) |
            (((long) data[offset + 3] & 0xFF) << 24);
    }

    private static void readExactly(InputStream input, byte[] out) throws IOException {
        int done = 0;
        while (done < out.length) {
            int read = input.read(out, done, out.length - done);
            if (read < 0)
                throw new IOException("The map header is incomplete.");
            if (read == 0)
                continue;
            done += read;
        }
    }

    private static void validateTagHeader(byte[] tagHeader, String title)
            throws IOException {
        if (!matches(tagHeader, 0x20, "sgat"))
            throw new IOException(title + " tag signature is invalid.");
        long count = u32(tagHeader, 0x0C);
        if (count == 0 || count > 65535)
            throw new IOException(title + " tag count is invalid.");
    }

    /** Check the full map, not just its header. No decompressed file is saved. */
    static long validate(File map) throws IOException {
        return validate(map, SIDEWINDER_MAP);
    }

    static long validate(File map, String targetMap) throws IOException {
        String expectedScenario = scenarioFor(targetMap);
        String title = titleFor(targetMap);
        if (!map.isFile() || Files.isSymbolicLink(map.toPath()))
            throw new IOException("The selected " + title + " file is unavailable.");
        long size = map.length();
        if (size < HEADER_BYTES + 2 || size > MAX_SOURCE_BYTES)
            throw new IOException(title + "'s file size is invalid.");

        try (InputStream input = new FileInputStream(map)) {
            byte[] header = new byte[HEADER_BYTES];
            readExactly(input, header);
            if (!matches(header, 0, "daeh") || !matches(header, 0x7FC, "toof")
                || u32(header, 4) != 5)
                throw new IOException("This is not a supported Xbox Halo map.");

            // The internal scenario must match the requested repair.
            // An arbitrary file renamed to b30.map is not evidence of identity.
            int nameEnd = 0x20;
            while (nameEnd < 0x40 && header[nameEnd] != 0)
                nameEnd++;
            String name = new String(header, 0x20, nameEnd - 0x20,
                StandardCharsets.US_ASCII);
            if (!expectedScenario.equalsIgnoreCase(name))
                throw new IOException("This map is " + name + ", not " + title + ".");

            long expectedSize = u32(header, 8);
            long tagOffset = u32(header, 0x10);
            long tagSize = u32(header, 0x14);
            if (expectedSize < HEADER_BYTES + TAG_HEADER_BYTES ||
                expectedSize > MAX_DECOMPRESSED_BYTES ||
                tagOffset < HEADER_BYTES || tagSize < TAG_HEADER_BYTES ||
                tagOffset > expectedSize - tagSize)
                throw new IOException(title + " has an invalid cache/tag range.");

            byte[] tagHeader = new byte[TAG_HEADER_BYTES];
            if (size == expectedSize) {
                // Some disc images may contain an uncompressed cache.
                try (FileInputStream raw = new FileInputStream(map)) {
                    long skipped = raw.getChannel().position(tagOffset).position();
                    if (skipped != tagOffset)
                        throw new IOException("Cannot seek to the map's tags.");
                    readExactly(raw, tagHeader);
                }
            } else if (size < expectedSize) {
                decodeCompressed(input, expectedSize - HEADER_BYTES,
                    tagOffset - HEADER_BYTES, tagHeader);
            } else {
                throw new IOException(title + " is larger than its declared map size.");
            }
            validateTagHeader(tagHeader, title);
            return expectedSize;
        }
    }

    /** Stream through the entire zlib body and capture the 36-byte tag index
     * header at its logical offset. Require both checksum/EOF and size. */
    private static void decodeCompressed(InputStream input, long expectedBody,
            long tagBodyOffset, byte[] tagHeader) throws IOException {
        Inflater inflater = new Inflater();
        byte[] compressed = new byte[COPY_BUFFER_BYTES];
        byte[] expanded = new byte[COPY_BUFFER_BYTES];
        long produced = 0;
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    int count = input.read(compressed);
                    if (count < 0)
                        throw new IOException("The map's compressed stream is truncated.");
                    if (count == 0)
                        continue;
                    inflater.setInput(compressed, 0, count);
                }
                int count;
                try {
                    count = inflater.inflate(expanded);
                } catch (DataFormatException e) {
                    throw new IOException("The map's compressed stream is corrupt.", e);
                }
                if (count > 0) {
                    if (produced > expectedBody - count)
                        throw new IOException("The map decompresses beyond its declared size.");
                    long begin = Math.max(produced, tagBodyOffset);
                    long end = Math.min(produced + count,
                        tagBodyOffset + tagHeader.length);
                    if (begin < end)
                        System.arraycopy(expanded, (int) (begin - produced),
                            tagHeader, (int) (begin - tagBodyOffset),
                            (int) (end - begin));
                    produced += count;
                } else if (inflater.needsDictionary()) {
                    throw new IOException("The map requires an unsupported zlib dictionary.");
                } else if (!inflater.needsInput() && !inflater.finished()) {
                    throw new IOException("The map's decompression stalled.");
                }
            }
            if (produced != expectedBody)
                throw new IOException("The map decompressed to the wrong size.");
        } finally {
            inflater.end();
        }
    }

    private static File mapsDirectory(File dataRoot) throws IOException {
        if (dataRoot == null)
            throw new IOException("Halo's storage is unavailable.");
        File maps = new File(dataRoot, "maps");
        if (!maps.isDirectory() || Files.isSymbolicLink(maps.toPath()))
            throw new IOException("Halo's maps folder is unavailable.");
        return maps;
    }

    static boolean hasPrevious(File dataRoot) {
        return hasPrevious(dataRoot, SIDEWINDER_MAP);
    }

    static boolean hasPrevious(File dataRoot, String targetMap) {
        if (dataRoot == null)
            return false;
        try {
            scenarioFor(targetMap);
        } catch (IOException e) {
            return false;
        }
        File maps = new File(dataRoot, "maps");
        return new File(maps, targetMap + ".previous").isFile();
    }

    private static void moveInSameDirectory(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from, to);
        }
    }

    static synchronized Result install(InputStream source, File dataRoot)
            throws IOException {
        return install(source, dataRoot, SIDEWINDER_MAP);
    }

    static synchronized Result install(InputStream source, File dataRoot,
            String targetMap) throws IOException {
        return install(source, dataRoot, targetMap, false);
    }

    private static synchronized Result install(InputStream source, File dataRoot,
            String targetMap, boolean keepEarlierBackups) throws IOException {
        String scenario = scenarioFor(targetMap);
        String title = titleFor(targetMap);
        if (source == null)
            throw new IOException("No map file was selected.");
        File maps = mapsDirectory(dataRoot);
        Path target = new File(maps, targetMap).toPath();
        Path previous = new File(maps, targetMap + ".previous").toPath();
        if (keepEarlierBackups) {
            int generation = 1;
            while (Files.exists(previous, java.nio.file.LinkOption.NOFOLLOW_LINKS) && generation < 8)
                previous = new File(maps, targetMap + ".previous." + (++generation)).toPath();
        }
        if (Files.exists(previous, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A previous " + title + " backup already exists. "
                + (keepEarlierBackups ? "Eight earlier copies are preserved; no files were replaced."
                                      : "Restore it before attempting another replacement."));
        if (Files.exists(target) && (!Files.isRegularFile(target) ||
            Files.isSymbolicLink(target)))
            throw new IOException("The installed " + title + " map cannot be safely replaced.");

        Path staged = Files.createTempFile(maps.toPath(), scenario + "-repair-", ".pending");
        boolean madeBackup = false;
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 is unavailable.", e);
            }
            long written = 0;
            try (FileOutputStream output = new FileOutputStream(staged.toFile())) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int amount;
                while ((amount = source.read(buffer)) != -1) {
                    if (amount == 0)
                        continue;
                    if (written > MAX_SOURCE_BYTES - amount)
                        throw new IOException("The selected map is larger than supported.");
                    output.write(buffer, 0, amount);
                    digest.update(buffer, 0, amount);
                    written += amount;
                }
                output.getFD().sync();
            }
            long decoded = validate(staged.toFile(), targetMap);
            byte[] hash = digest.digest();
            StringBuilder sha = new StringBuilder(hash.length * 2);
            for (byte value : hash)
                sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xFF));

            if (Files.exists(target)) {
                moveInSameDirectory(target, previous);
                madeBackup = true;
            }
            try {
                moveInSameDirectory(staged, target);
            } catch (IOException e) {
                if (madeBackup && !Files.exists(target)) {
                    try {
                        moveInSameDirectory(previous, target);
                    } catch (IOException rollback) {
                        e.addSuppressed(rollback);
                    }
                }
                throw e;
            }
            return new Result(written, decoded, madeBackup, sha.toString());
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Explicit rollback, retaining the new map as .replaced for inspection.
     * The old map may itself be corrupt; this operation is only user driven. */
    static synchronized void restorePrevious(File dataRoot) throws IOException {
        restorePrevious(dataRoot, SIDEWINDER_MAP);
    }

    static synchronized void restorePrevious(File dataRoot, String targetMap)
            throws IOException {
        scenarioFor(targetMap);
        String title = titleFor(targetMap);
        File maps = mapsDirectory(dataRoot);
        Path current = new File(maps, targetMap).toPath();
        Path previous = new File(maps, targetMap + ".previous").toPath();
        Path replaced = new File(maps, targetMap + ".replaced").toPath();
        if (!Files.isRegularFile(previous) || Files.isSymbolicLink(previous))
            throw new IOException("No previous " + title + " map is available.");
        if (Files.exists(replaced))
            throw new IOException("A previously replaced " + title + " is already preserved.");
        if (Files.exists(current) && (!Files.isRegularFile(current)
            || Files.isSymbolicLink(current)))
            throw new IOException("The current " + title + " map cannot be safely restored.");
        boolean retainedNew = false;
        if (Files.exists(current)) {
            moveInSameDirectory(current, replaced);
            retainedNew = true;
        }
        try {
            moveInSameDirectory(previous, current);
        } catch (IOException e) {
            if (retainedNew && !Files.exists(current)) {
                try {
                    moveInSameDirectory(replaced, current);
                } catch (IOException rollback) {
                    e.addSuppressed(rollback);
                }
            }
            throw e;
        }
    }
}
