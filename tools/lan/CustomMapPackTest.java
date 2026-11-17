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

    private static byte[] bmp(int bits, int height) {
        int stride = ((3 * bits + 31) / 32) * 4;
        byte[] b = new byte[54 + stride * Math.abs(height)];
        b[0]='B'; b[1]='M'; write32(b, 2, b.length);
        write32(b, 10, 54); write32(b, 14, 40);
        write32(b, 18, 3); write32(b, 22, height);
        b[26]=1; b[28]=(byte)bits;
        Arrays.fill(b, 54, b.length, (byte)125);
        return b;
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
            files.put("Halo-Custom-Maps/", new byte[0]);
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

            // Directory data descriptors leave the size unknown at entry
            // open. Reject after one output byte, without draining a bomb.
            byte[] folderBomb = pack(Map.of("payload/", new byte[8 * 1024 * 1024]));
            java.io.InputStream guarded = new java.io.FilterInputStream(
                    new ByteArrayInputStream(folderBomb)) {
                int consumed;
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len);
                    if (n > 0 && (consumed += n) > 2048)
                        throw new AssertionError("Directory payload was drained");
                    return n;
                }
            };
            rejected(() -> CustomMapPack.install(guarded, root.toFile(), null),
                "directory contains data");
            // STORED entries declare their size before any payload is read.
            ByteArrayOutputStream storedBytes = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(storedBytes)) {
                ZipEntry e = new ZipEntry("stored/");
                byte[] payload = {1, 2, 3};
                java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(payload);
                e.setMethod(ZipEntry.STORED); e.setSize(3); e.setCompressedSize(3);
                e.setCrc(crc.getValue()); z.putNextEntry(e); z.write(payload); z.closeEntry();
            }
            rejected(() -> CustomMapPack.install(new ByteArrayInputStream(
                storedBytes.toByteArray()), root.toFile(), null), "directory contains data");
            LinkedHashMap<String,byte[]> folders = new LinkedHashMap<>();
            for (int i = 0; i <= CustomMapPack.MAX_ZIP_ENTRIES; i++)
                folders.put("folder" + i + "/", new byte[0]);
            rejected(() -> CustomMapPack.install(new ByteArrayInputStream(pack(folders)),
                root.toFile(), null), "more than 64 entries");
            rejected(() -> CustomMapPack.install(new ByteArrayInputStream(
                pack(Map.of("../", new byte[0]))), root.toFile(), null), "unsafe");
            rejected(() -> CustomMapPack.install(new ByteArrayInputStream(
                pack(Map.of("a/b/", new byte[0]))), root.toFile(), null), "unsafe");

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

            // A preview-only pack can be imported after the map without
            // touching its bytes. Both BMP orientations/bit depths work.
            byte[] originalMap = Files.readAllBytes(root.resolve("custom_maps/municipality.map"));
            byte[] picture = bmp(24, 2);
            CustomMapPack.Result pictures = CustomMapPack.install(
                new ByteArrayInputStream(pack(Map.of("municipality.bmp", picture,
                    "h2_warlock.bmp", bmp(32, -2)))), root.toFile(), null);
            ok(pictures.files == 2, "Preview-only pack not imported");
            ok(Arrays.equals(picture, Files.readAllBytes(root.resolve("custom_maps/municipality.bmp"))),
                "Preview changed in transit");
            ok(Arrays.equals(originalMap, Files.readAllBytes(root.resolve("custom_maps/municipality.map"))),
                "Preview import changed the map");
            rejected(() -> CustomMapImporter.install(new ByteArrayInputStream(picture),
                root.toFile(), "municipality.bmp"), "already installed");
            ok(!CustomMapImporter.isValidName("municipality.bmp"),
                "Network/cache validation now accepts pictures as maps");
            for (String unsafe : new String[]{"../x.bmp", "/x.bmp", "x/evil.bmp", "x\\evil.bmp", "con.bmp", "a..b.bmp", "x.png", "x.bmp.map.bmp.exe"})
                ok(!CustomMapImporter.isImportName(unsafe), "Unsafe picture name accepted: " + unsafe);
            int[][] corruptions = {{0,0}, {2,54}, {10,53}, {10,9999}, {14,124},
                {18,0}, {18,-1}, {18,2049}, {22,0}, {22,Integer.MIN_VALUE},
                {22,2049}, {26,2}, {28,8}, {30,1}};
            for (int j=0; j<corruptions.length; j++) {
                byte[] corrupt = picture.clone();
                write32(corrupt, corruptions[j][0], corruptions[j][1]);
                final String name = "bad" + j + ".bmp";
                rejected(() -> CustomMapImporter.install(new ByteArrayInputStream(corrupt),
                    root.toFile(), name), "preview");
                ok(!Files.exists(root.resolve("custom_maps/" + name)), "Bad preview installed");
            }
            byte[] truncated = Arrays.copyOf(picture, picture.length - 1);
            write32(truncated, 2, truncated.length);
            rejected(() -> CustomMapImporter.install(new ByteArrayInputStream(truncated),
                root.toFile(), "truncated.bmp"), "truncated");
            rejected(() -> CustomMapPack.install(new ByteArrayInputStream(
                pack(Map.of("../escaped.bmp", picture))), root.toFile(), null), "unsafe");
            Path link = root.resolve("custom_maps/link.bmp");
            Files.createSymbolicLink(link, root.resolve("missing.bmp"));
            rejected(() -> CustomMapImporter.install(new ByteArrayInputStream(picture),
                root.toFile(), "link.bmp"), "already installed");
            Files.delete(link);

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
