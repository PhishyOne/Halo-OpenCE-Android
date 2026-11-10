package com.halo.decomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Import Halo Custom Edition cache and shared resource maps into the app's
 * own custom_maps folder, through Android's document picker.
 *
 * The user's supplied display name is never trusted as a path; it is checked
 * against a small filename grammar. Maps must have a v609 cache header and
 * matching internal scenario name, while bitmaps/sounds/loc must have the
 * resource type and valid item index layout. Files are copied to temporary
 * files, checked, and promoted without overwriting any existing map.
 *
 * This is a structural filter, NOT a guarantee that arbitrary community
 * scripts, geometry or tags are safe or playable. The game validates these
 * again at load time. Keep the first test offline.
 */
final class CustomMapImporter {
    private static final int CACHE_HEADER_BYTES = 0x800;
    private static final int RESOURCE_HEADER_BYTES = 0x10;
    private static final int CHUNK = 64 * 1024;
    private static final int MAX_RESOURCE_ITEMS = 100000;
    private static final int MAX_NAMES_BYTES = 16 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;

    static final class Result {
        final String name;
        final long size;
        final String sha256;
        final boolean resource;

        Result(String name, long size, String sha256, boolean resource) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.resource = resource;
        }
    }

    private CustomMapImporter() { }

    private static long u32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFFL) |
            (((long) data[offset + 1] & 0xFFL) << 8) |
            (((long) data[offset + 2] & 0xFFL) << 16) |
            (((long) data[offset + 3] & 0xFFL) << 24);
    }

    static boolean isValidName(String name) {
        return name != null && name.length() <= 55 &&
            name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,50}\\.map") &&
            !name.contains("..") &&
            !name.equalsIgnoreCase("con.map") &&
            !name.equalsIgnoreCase("nul.map") &&
            !name.equalsIgnoreCase("prn.map") &&
            !name.equalsIgnoreCase("aux.map");
    }

    private static boolean resource(String name) {
        return name.equals("bitmaps.map") ||
            name.equals("sounds.map") || name.equals("loc.map");
    }

    private static int resourceType(String name) {
        return name.equals("bitmaps.map") ? 1 :
            name.equals("sounds.map") ? 2 : 3;
    }

    private static void validateMap(File file, String name) throws IOException {
        long size = file.length();
        if (size < CACHE_HEADER_BYTES || size > MAX_FILE_BYTES)
            throw new IOException(name + " has an invalid cache length.");
        byte[] header = new byte[CACHE_HEADER_BYTES];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int n = input.read(header, offset, header.length - offset);
                if (n < 0)
                    throw new IOException("Incomplete map header: " + name);
                offset += n;
            }
        }
        if (header[0] != 'd' || header[1] != 'a' ||
            header[2] != 'e' || header[3] != 'h' ||
            header[0x7FC] != 't' || header[0x7FD] != 'o' ||
            header[0x7FE] != 'o' || header[0x7FF] != 'f' ||
            u32(header, 4) != 609)
            throw new IOException(name + " is not a Halo Custom Edition v609 map.");

        int terminator = 0x20;
        while (terminator < 0x40 && header[terminator] != 0)
            terminator++;
        if (terminator == 0x40)
            throw new IOException(name + " has no terminated internal map name.");
        String scenario = new String(header, 0x20, terminator - 0x20,
            StandardCharsets.US_ASCII);
        if (!scenario.equalsIgnoreCase(name.substring(0, name.length() - 4)))
            throw new IOException(name + " does not match the scenario in its header.");

        long expected = u32(header, 8);
        long tagsOffset = u32(header, 0x10);
        long tagsBytes = u32(header, 0x14);
        // Some Invader-built caches declare zero, using the physical size.
        if (expected != 0 && expected != size)
            throw new IOException(name + " has inconsistent file length.");
        if (tagsOffset < CACHE_HEADER_BYTES || tagsBytes < 0x28 ||
            tagsBytes > 0x1700000L || tagsOffset > size ||
            tagsBytes > size - tagsOffset)
            throw new IOException(name + " has an invalid tag data range.");

        // Files requiring an OpenSauce memory upgrade cannot load in OpenCE.
        // The game will perform more detailed tag validation at map load.
    }

    private static void validateResource(File file, String name) throws IOException {
        long size = file.length();
        if (size < RESOURCE_HEADER_BYTES || size > MAX_FILE_BYTES)
            throw new IOException(name + " has an invalid resource-map length.");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[RESOURCE_HEADER_BYTES];
            input.readFully(header);
            long type = u32(header, 0);
            long namesStart = u32(header, 4);
            long indexStart = u32(header, 8);
            long count = u32(header, 12);
            if (type != resourceType(name) || namesStart < RESOURCE_HEADER_BYTES ||
                indexStart < namesStart || indexStart > size ||
                count > MAX_RESOURCE_ITEMS || count > (size - indexStart) / 12 ||
                indexStart - namesStart > MAX_NAMES_BYTES)
                throw new IOException(name + " has invalid resource metadata.");

            int namesBytes = (int) (indexStart - namesStart);
            byte[] names = new byte[namesBytes];
            input.seek(namesStart);
            input.readFully(names);
            input.seek(indexStart);
            byte[] entry = new byte[12];
            for (long i = 0; i < count; i++) {
                input.readFully(entry);
                long nameAt = u32(entry, 0);
                long payloadSize = u32(entry, 4);
                long payloadAt = u32(entry, 8);
                if (nameAt >= namesBytes || payloadAt < RESOURCE_HEADER_BYTES ||
                    payloadAt > namesStart || payloadSize > namesStart - payloadAt)
                    throw new IOException(name + " has invalid resource item " + i);
                boolean terminated = false;
                for (int n = (int)nameAt; n < namesBytes; n++) {
                    if (names[n] == 0) {
                        terminated = true;
                        break;
                    }
                }
                if (!terminated)
                    throw new IOException(name + " has an unterminated resource name.");
            }
        }
    }

    static void validate(File file, String name) throws IOException {
        if (!isValidName(name))
            throw new IOException("Invalid custom map filename.");
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile())
            throw new IOException("Selected custom map is not a regular file.");
        if (resource(name))
            validateResource(file, name);
        else
            validateMap(file, name);
    }

    static synchronized Result install(InputStream source, File dataRoot, String name)
            throws IOException {
        if (!isValidName(name) || source == null)
            throw new IOException("Only standalone Halo Custom Edition .map files are allowed.");
        if (dataRoot == null || !dataRoot.isDirectory())
            throw new IOException("Halo's app storage is unavailable.");

        File custom = new File(dataRoot, "custom_maps");
        if (!custom.isDirectory() && !custom.mkdir())
            throw new IOException("Cannot create the custom_maps folder.");
        if (Files.isSymbolicLink(custom.toPath()) || !custom.isDirectory())
            throw new IOException("The custom_maps folder is not safe to use.");

        Path target = new File(custom, name).toPath();
        if (Files.exists(target))
            throw new IOException(name + " is already installed. No files were overwritten.");

        Path temporary = Files.createTempFile(custom.toPath(), "ce-import-", ".pending");
        try {
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 is not available.", e);
            }
            long written = 0;
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                byte[] bytes = new byte[CHUNK];
                int n;
                while ((n = source.read(bytes)) != -1) {
                    if (n == 0)
                        continue;
                    if (written > MAX_FILE_BYTES - n)
                        throw new IOException(name + " exceeds the safe import size limit.");
                    output.write(bytes, 0, n);
                    sha.update(bytes, 0, n);
                    written += n;
                }
                output.getFD().sync();
            }
            validate(temporary.toFile(), name);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha.digest())
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return new Result(name, written, hex.toString(), resource(name));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
