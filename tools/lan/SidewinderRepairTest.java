package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.DeflaterOutputStream;

/** Standalone JDK validation and rollback tests. No Android SDK or retail maps. */
public final class SidewinderRepairTest {
    private static int assertions;

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition)
            throw new AssertionError(description);
    }

    private static void put32(byte[] data, int offset, long value) {
        for (int n = 0; n < 4; n++)
            data[offset + n] = (byte) (value >>> (n * 8));
    }

    private static void putAscii(byte[] data, int offset, String string) {
        byte[] b = string.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, data, offset, b.length);
    }

    private static byte[] createMap(boolean compressed, boolean goodTag) throws IOException {
        return createMap(compressed, goodTag, "sidewinder");
    }

    private static byte[] createMap(boolean compressed, boolean goodTag,
            String scenario) throws IOException {
        byte[] header = new byte[0x800];
        byte[] body = new byte[8192];
        for (int n = 0; n < body.length; n++)
            body[n] = (byte) (n % 16 == 0 ? 77 : 0);
        putAscii(header, 0, "daeh");
        put32(header, 4, 5);
        put32(header, 8, header.length + body.length);
        put32(header, 0x10, header.length + 256);
        put32(header, 0x14, 128);
        putAscii(header, 0x20, scenario);
        putAscii(header, 0x40, "01.10.12.2276");
        putAscii(header, 0x7FC, "toof");
        put32(body, 256 + 0x0C, 42);
        putAscii(body, 256 + 0x20, goodTag ? "sgat" : "FAIL");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(header);
        if (compressed) {
            try (DeflaterOutputStream compressor = new DeflaterOutputStream(output)) {
                compressor.write(body);
            }
        } else {
            output.write(body);
        }
        return output.toByteArray();
    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void reject(Checked action, String contains) throws Exception {
        boolean threw = false;
        try {
            action.run();
        } catch (IOException e) {
            threw = true;
            check(e.getMessage().toLowerCase(java.util.Locale.ROOT)
                .contains(contains.toLowerCase(java.util.Locale.ROOT)),
                "Unexpected error: " + e.getMessage());
        }
        check(threw, "Expected IOException: " + contains);
    }

    private static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory))
            return;
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-sidewinder-repair-test");
        Path maps = Files.createDirectory(root.resolve("maps"));
        File dataRoot = root.toFile();
        Path current = maps.resolve("sidewinder.map");
        Path previous = maps.resolve("sidewinder.map.previous");
        Path replaced = maps.resolve("sidewinder.map.replaced");
        byte[] validCompressed = createMap(true, true);
        byte[] validRaw = createMap(false, true);

        try {
            // Both forms are legitimate Halo maps; decompress full zlib body.
            Path reference = root.resolve("reference.map");
            Files.write(reference, validCompressed);
            check(SidewinderRepair.validate(reference.toFile()) == 10240,
                "Valid compressed map rejected");
            Files.write(reference, validRaw);
            check(SidewinderRepair.validate(reference.toFile()) == 10240,
                "Valid raw map rejected");

            // Map identity is taken from the header, never the picker filename.
            byte[] wrongName = validCompressed.clone();
            putAscii(wrongName, 0x20, "bloodgulch");
            Files.write(reference, wrongName);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "not Sidewinder");
            byte[] wrongVersion = validCompressed.clone();
            put32(wrongVersion, 4, 609);
            Files.write(reference, wrongVersion);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "not a supported");
            byte[] damagedTag = createMap(true, false);
            Files.write(reference, damagedTag);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "tag signature");

            byte[] truncated = Arrays.copyOf(validCompressed, validCompressed.length - 8);
            Files.write(reference, truncated);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "truncated");
            byte[] badDeclaredLength = validCompressed.clone();
            put32(badDeclaredLength, 8, 10400);
            Files.write(reference, badDeclaredLength);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "wrong size");
            byte[] huge = validCompressed.clone();
            put32(huge, 8, 0x40000000L);
            Files.write(reference, huge);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "invalid cache/tag");
            byte[] badTagRange = validCompressed.clone();
            put32(badTagRange, 0x10, 10400);
            Files.write(reference, badTagRange);
            reject(() -> SidewinderRepair.validate(reference.toFile()), "invalid cache/tag");

            // Repair staged to its own fixed directory; bad maps do not change
            // the original file and leave no .pending files behind.
            byte[] oldBadMap = "old source Sidewinder is damaged".getBytes();
            Files.write(current, oldBadMap);
            reject(() -> SidewinderRepair.install(
                new ByteArrayInputStream(truncated), dataRoot), "truncated");
            check(Arrays.equals(Files.readAllBytes(current), oldBadMap),
                "Invalid map modified the installed cache");
            check(!Files.exists(previous), "Invalid map created a backup");
            try (java.util.stream.Stream<Path> files = Files.list(maps)) {
                check(files.noneMatch(p -> p.getFileName().toString().endsWith(".pending")),
                    "Incomplete pending file left after rejection");
            }

            // Valid candidate gets verified and swapped, preserving original.
            SidewinderRepair.Result installed = SidewinderRepair.install(
                new ByteArrayInputStream(validCompressed), dataRoot);
            check(installed.sourceBytes == validCompressed.length, "Copied byte count");
            check(installed.decodedBytes == 10240, "Decoded size");
            check(installed.sha256.length() == 64, "SHA-256 not recorded");
            check(installed.backedUpOriginal, "Original map not backed up");
            check(Arrays.equals(Files.readAllBytes(previous), oldBadMap),
                "Original bytes were not preserved");
            check(Arrays.equals(Files.readAllBytes(current), validCompressed),
                "Verified map bytes not installed");
            check(SidewinderRepair.hasPrevious(dataRoot), "Backup button not available");

            // A second repair cannot overwrite the only backup.
            reject(() -> SidewinderRepair.install(
                new ByteArrayInputStream(validRaw), dataRoot), "already exists");
            check(Arrays.equals(Files.readAllBytes(current), validCompressed),
                "Second repair modified an existing map");

            // Explicit restore must preserve BOTH copies, not delete the new one.
            SidewinderRepair.restorePrevious(dataRoot);
            check(Arrays.equals(Files.readAllBytes(current), oldBadMap),
                "Restore did not recover original map");
            check(Arrays.equals(Files.readAllBytes(replaced), validCompressed),
                "Restore lost the replacement map");
            check(!Files.exists(previous), "Backup file not moved back");
            check(!SidewinderRepair.hasPrevious(dataRoot), "Backup state not cleared");
            reject(() -> SidewinderRepair.restorePrevious(dataRoot), "No previous");

            // Fresh installation when Sidewinder is missing: no backup needed.
            Files.delete(current);
            SidewinderRepair.Result fresh = SidewinderRepair.install(
                new ByteArrayInputStream(validRaw), dataRoot);
            check(!fresh.backedUpOriginal, "Missing old map reported as backed up");
            check(Arrays.equals(Files.readAllBytes(current), validRaw),
                "Uncompressed map install failed");

            // Silent Cartographer is independent of Sidewinder and uses a
            // separate target and backup even when both are repaired.
            byte[] cartographerCompressed = createMap(true, true, "b30");
            byte[] cartographerRaw = createMap(false, true, "b30");
            Path cartographer = maps.resolve("b30.map");
            Path cartographerBackup = maps.resolve("b30.map.previous");
            Path cartographerReplaced = maps.resolve("b30.map.replaced");
            Files.write(reference, cartographerCompressed);
            check(SidewinderRepair.validate(reference.toFile(), "b30.map") == 10240,
                "Valid Silent Cartographer zlib cache rejected");
            Files.write(reference, cartographerRaw);
            check(SidewinderRepair.validate(reference.toFile(), "b30.map") == 10240,
                "Valid uncompressed Silent Cartographer cache rejected");
            reject(() -> SidewinderRepair.validate(reference.toFile(), "sidewinder.map"),
                "not Sidewinder");
            reject(() -> SidewinderRepair.validate(reference.toFile(), "../b30.map"),
                "cannot be repaired");

            byte[] badOldCartographer = "corrupt installed b30".getBytes();
            Files.write(cartographer, badOldCartographer);
            reject(() -> SidewinderRepair.install(
                new ByteArrayInputStream(validCompressed), dataRoot, "b30.map"),
                "not The Silent Cartographer");
            check(Arrays.equals(Files.readAllBytes(cartographer), badOldCartographer),
                "Wrong scenario corrupted b30");
            check(!Files.exists(cartographerBackup), "Wrong scenario created backup");

            SidewinderRepair.Result b30Result = SidewinderRepair.install(
                new ByteArrayInputStream(cartographerCompressed), dataRoot, "b30.map");
            check(b30Result.backedUpOriginal &&
                b30Result.sourceBytes == cartographerCompressed.length,
                "Silent Cartographer repair failed");
            check(Arrays.equals(Files.readAllBytes(cartographerBackup), badOldCartographer),
                "Silent Cartographer previous file not kept");
            check(Arrays.equals(Files.readAllBytes(cartographer), cartographerCompressed),
                "Silent Cartographer replacement incorrect");
            check(SidewinderRepair.hasPrevious(dataRoot, "b30.map"),
                "Silent Cartographer restore option missing");
            check(!SidewinderRepair.hasPrevious(dataRoot, "../b30.map"),
                "Path outside allowlist exposed backup");

            // Sidewinder data must not change when Cartographer changes.
            check(Arrays.equals(Files.readAllBytes(current), validRaw),
                "Cartographer repair modified Sidewinder");
            SidewinderRepair.restorePrevious(dataRoot, "b30.map");
            check(Arrays.equals(Files.readAllBytes(cartographer), badOldCartographer),
                "Silent Cartographer rollback failed");
            check(Arrays.equals(Files.readAllBytes(cartographerReplaced), cartographerCompressed),
                "Restoring b30 lost its verified candidate");
            check(Arrays.equals(Files.readAllBytes(current), validRaw),
                "Cartographer restore modified Sidewinder");
            check(!SidewinderRepair.hasPrevious(dataRoot, "b30.map"),
                "Silent Cartographer rollback left a backup behind");

            System.out.println("SidewinderRepairTest PASS (" + assertions + " assertions)");
        } finally {
            removeTree(root);
        }
    }
}
