package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CustomMapPackTest {
    private static int checks;

    private interface Check { void run() throws Exception; }

    private static void ok(boolean yes, String why) {
        checks++;
        if (!yes) throw new AssertionError(why);
    }

    private static void rejected(Check test, String reason) throws Exception {
        try {
            test.run();
        } catch (IOException ex) {
            ok(ex.getMessage().toLowerCase().contains(reason.toLowerCase()),
                "Unexpected failure: " + ex.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection " + reason);
    }

    private static void write32(byte[] data, int offset, long value) {
        for (int i=0;i<4;i++) data[offset+i] = (byte)(value >>> (i*8));
    }

    private static byte[] map(String name) {
        byte[] data = new byte[0x800 + 128];
        System.arraycopy("daeh".getBytes(), 0, data, 0, 4);
        write32(data, 4, 609);
        write32(data, 8, data.length);
        write32(data, 0x10, 0x800);
        write32(data, 0x14, 128);
        System.arraycopy(name.getBytes(), 0, data, 0x20, name.length());
        System.arraycopy("toof".getBytes(), 0, data, 0x7FC, 4);
        return data;
    }

    private static byte[] resource() {
        byte[] r = new byte[36];
        write32(r, 0, 3);
        write32(r, 4, 20);
        write32(r, 8, 24);
        write32(r, 12, 1);
        r[16]=33;
        r[20]='x';
        write32(r, 24, 0);
        write32(r, 28, 4);
        write32(r, 32, 16);
        return r;
    }

    private static byte[] pack(Map<String,byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String,byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static void clean(Path root) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-map-zip-test");
        try {
            LinkedHashMap<String,byte[]> files = new LinkedHashMap<>();
            files.put("Halo-Custom-Maps/loc.map", resource());
            files.put("Halo-Custom-Maps/h2_warlock.map", map("h2_warlock"));
            files.put("municipality.map", map("municipality"));
            byte[] archive = pack(files);
            int[] progress = {0};
            CustomMapPack.Result installed = CustomMapPack.install(
                new ByteArrayInputStream(archive), root.toFile(),
                (name, count) -> progress[0]++);
            ok(installed.files == 3, "Did not import all three files");
            ok(progress[0] == 3, "Progress callback was incorrect");
            ok(installed.bytes == 36 + 2 * (0x800 + 128),
                "ZIP unpacked size was incorrect");
            for (String name : new String[] {
                    "loc.map","h2_warlock.map","municipality.map"}) {
                byte[] expected=files.get("Halo-Custom-Maps/"+name);
                if (expected == null) expected=files.get(name);
                byte[] actual=Files.readAllBytes(root.resolve("custom_maps").resolve(name));
                ok(Arrays.equals(actual,expected),"ZIP map damaged: "+name);
            }
            ok(!Files.exists(root.resolve("maps")),
                "Import created or changed original Xbox maps folder");
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream(archive), root.toFile(), null),
                "already installed");

            // Escaping the ZIP hierarchy is never accepted.
            LinkedHashMap<String,byte[]> malicious = new LinkedHashMap<>();
            malicious.put("../evil.map",map("evil"));
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream(pack(malicious)), root.toFile(),null),
                "unsafe");
            ok(!Files.exists(root.resolve("evil.map")),
                "ZIP traversal escaped target folder");

            LinkedHashMap<String,byte[]> badExtension = new LinkedHashMap<>();
            badExtension.put("readme.exe",new byte[20]);
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream(pack(badExtension)), root.toFile(),null),
                "non-Halo");

            LinkedHashMap<String,byte[]> dup = new LinkedHashMap<>();
            dup.put("test.map",map("test"));
            dup.put("TEST.map",map("TEST"));
            Path duplicateRoot = Files.createDirectory(root.resolve("dups"));
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream(pack(dup)), duplicateRoot.toFile(),null),
                "duplicate");
            ok(Files.exists(duplicateRoot.resolve("custom_maps/test.map")),
                "First map not retained after duplicate ZIP member");
            ok(!Files.exists(duplicateRoot.resolve("custom_maps/TEST.map")),
                "Duplicate ZIP map installed");
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream(pack(Map.of())), root.toFile(),null),
                "no Custom Edition");
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream("not a ZIP".getBytes()), root.toFile(),null),
                "no Custom Edition");

            LinkedHashMap<String,byte[]> tooMany = new LinkedHashMap<>();
            for (int i=0;i<33;i++)
                tooMany.put("m"+i+".map", map("m"+i));
            Path extra = Files.createDirectory(root.resolve("extra"));
            rejected(() -> CustomMapPack.install(
                new ByteArrayInputStream(pack(tooMany)),extra.toFile(),null),
                "more than 32");
            ok(!Files.exists(extra.resolve("custom_maps/m32.map")),
                "33rd map was installed");

            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                ok(paths.noneMatch(p -> p.toString().endsWith(".pending")),
                    "A ZIP map importer left a pending file");
            }
            System.out.println("CustomMapPackTest PASS (" + checks + " checks)");
        } finally {
            clean(root);
        }
    }
}
