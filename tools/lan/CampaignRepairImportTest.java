package com.halo.decomp;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class CampaignRepairImportTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void put32(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte)(value >>> (8 * i));
    }
    private static byte[] map(String name) throws IOException {
        byte[] header = new byte[2048], body = new byte[8192];
        System.arraycopy("daeh".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 4);
        System.arraycopy("toof".getBytes(StandardCharsets.US_ASCII), 0, header, 2044, 4);
        System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, header, 32, name.length());
        put32(header, 4, 5); put32(header, 8, 10240); put32(header, 16, 2304); put32(header, 20, 128);
        put32(body, 268, 17);
        System.arraycopy("sgat".getBytes(StandardCharsets.US_ASCII), 0, body, 288, 4);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(header);
        try (DeflaterOutputStream z = new DeflaterOutputStream(bytes)) { z.write(body); }
        return bytes.toByteArray();
    }
    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i])); z.write(contents[i]); z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static String install(byte[] bytes, Path root) throws IOException {
        return CampaignRepairImport.install(new ByteArrayInputStream(bytes), root.toFile(), null);
    }
    private static void rejects(Io action, String reason) throws Exception {
        checks++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(reason);
    }
    interface Io { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-auto-map-");
        Path maps = Files.createDirectory(root.resolve("maps"));
        try {
            byte[] c10 = map("c10"), b40 = map("b40");
            Files.write(maps.resolve("c10.map"), new byte[]{1, 2, 3});
            String first = install(c10, root);
            check(first.contains("✓ PASS") && first.contains("343 Guilty Spark"), "Header identity/title");
            check(Arrays.equals(c10, Files.readAllBytes(maps.resolve("c10.map"))), "Wrong destination");
            check(!Files.exists(maps.resolve("b30.map")), "Default Cartographer selection leaked");
            check(Arrays.equals(new byte[]{1, 2, 3}, Files.readAllBytes(maps.resolve("c10.map.previous"))), "Original not retained");
            check(install(c10, root).contains("already healthy"), "Healthy repeat should not fail on existing backup");
            check(!Files.exists(maps.resolve("c10.map.previous.2")), "Healthy repeat made unnecessary backup");
            Files.write(maps.resolve("c10.map"), new byte[]{4, 5});
            check(install(c10, root).contains("repaired"), "Existing backup blocks a real retry");
            check(Arrays.equals(new byte[]{1, 2, 3}, Files.readAllBytes(maps.resolve("c10.map.previous"))), "Retry overwrote first backup");
            check(Arrays.equals(new byte[]{4, 5}, Files.readAllBytes(maps.resolve("c10.map.previous.2"))), "Retry lost damaged current map");

            String batch = install(zip(new String[]{"maps/b30.map", "CAMPAIGN-MAP-INVENTORY.txt"},
                new byte[][]{b40, "inventory".getBytes(StandardCharsets.UTF_8)}), root);
            check(batch.contains("Assault on the Control Room"), "ZIP filename overrode internal level");
            check(Arrays.equals(b40, Files.readAllBytes(maps.resolve("b40.map"))), "ZIP not installed");
            check(!Files.exists(maps.resolve("b30.map")), "Mislabelled ZIP chose wrong level");
            check(install(zip(new String[]{"../a10.map"}, new byte[][]{map("a10")}), root).contains("unsafe path"), "Traversal accepted");
            check(!Files.exists(maps.resolve("a10.map")), "Unsafe ZIP mutated maps");

            byte[] corrupt = map("c20"); corrupt[corrupt.length - 1] ^= 64;
            String partial = install(zip(new String[]{"a10.map", "c20.map"}, new byte[][]{map("a10"), corrupt}), root);
            check(partial.contains("✓ PASS") && partial.contains("✕ FAIL"), "Partial ZIP hides failure/success");
            check(Files.exists(maps.resolve("a10.map")) && !Files.exists(maps.resolve("c20.map")), "Partial ZIP install unsafe");
            check(install(zip(new String[]{"readme.txt"}, new byte[][]{new byte[1]}), root).contains("No original Xbox"), "Empty repair ZIP accepted");
            check(install(zip(new String[]{"readme.txt"}, new byte[][]{new byte[1024 * 1024 + 1]}), root).contains("size limit"), "Metadata bomb unbounded");
            String[] many = new String[33]; byte[][] tiny = new byte[33][];
            for (int i = 0; i < 33; i++) { many[i] = i + ".txt"; tiny[i] = new byte[0]; }
            check(install(zip(many, tiny), root).contains("more than 32"), "ZIP entry count unbounded");
            rejects(() -> install(map("../save"), root), "Internal path escaped allowlist");
            rejects(() -> install(new byte[2048], root), "Wrong format accepted");
            CampaignRepairImport.Limit limited = new CampaignRepairImport.Limit(
                new ByteArrayInputStream(new byte[17]), 16, new long[]{0});
            rejects(() -> limited.read(new byte[17]), "Entry budget not enforced");
            CampaignRepairImport.Limit total = new CampaignRepairImport.Limit(
                new ByteArrayInputStream(new byte[1]), 16, new long[]{3L * 1024 * 1024 * 1024});
            rejects(() -> total.read(), "Pack budget not enforced");
            java.util.List<CampaignMapTransfer.Check> audit = CampaignMapTransfer.checks(root.toFile(), null);
            check(audit.size() == 11, "Audit must include ten campaign maps plus Sidewinder");
            check(audit.get(4).passed && audit.get(4).title.equals("Assault on the Control Room"), "Structured PASS wrong");
            check(!audit.get(6).passed && audit.get(6).filename.equals("c20.map"), "Structured FAIL wrong");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); }
                    catch (IOException e) { throw new RuntimeException(e); } });
            }
        }
        System.out.println("CampaignRepairImportTest PASS (" + checks + " checks)");
    }
}
