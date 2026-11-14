package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Exercises the real ZIP and atomic save-tree swap, without Android.
 * No user profiles, maps or live devices are touched by these tests. */
public final class CampaignSaveArchiveTest {
    private static int checks;

    private static void check(boolean valid, String message) {
        checks++;
        if (!valid) throw new AssertionError(message);
    }

    private static void rejected(String reason, Check op) throws Exception {
        try {
            op.run();
        } catch (IOException expected) {
            check(true, "Rejected " + reason);
            return;
        }
        throw new AssertionError("Expected rejection: " + reason);
    }

    interface Check { void run() throws Exception; }

    private static void write(Path base, String relative, byte[] bytes)
            throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(result)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return result.toByteArray();
    }

    private static byte[] oversizedArchive() throws Exception {
        // >128 MiB of decompressed zeroes but a small ZIP on disk.
        // Testing the real stream limit doesn't consume 129 MiB of RAM.
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(packed)) {
            zip.putNextEntry(new ZipEntry("Halo-Campaign-Backup-v1.txt"));
            zip.write("HALO-CAMPAIGN-PROGRESS-BACKUP-V1\\n"
                .getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("save/z/UDATA/02B2/oversized.bin"));
            byte[] zeros = new byte[64 * 1024];
            for (int chunk = 0; chunk < 2049; chunk++)
                zip.write(zeros);
            zip.closeEntry();
        }
        return packed.toByteArray();
    }

    private static byte[] archive(String key, byte[] payload) throws Exception {
        Map<String,byte[]> entries = new LinkedHashMap<>();
        entries.put("Halo-Campaign-Backup-v1.txt",
            "HALO-CAMPAIGN-PROGRESS-BACKUP-V1\n".getBytes(StandardCharsets.US_ASCII));
        entries.put(key, payload);
        return zip(entries);
    }

    private static void clean(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> tree = Files.walk(dir)) {
            tree.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException error) { throw new RuntimeException(error); }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-campaign-save-test-");
        try {
            write(root, "save/u/UDATA/01A1/SaveMeta.xbx", "PROFILE".getBytes(StandardCharsets.UTF_8));
            write(root, "save/u/UDATA/01A1/blam.sav", new byte[4096]);
            write(root, "save/z/UDATA/02B2/savegame.bin", "CHECKPOINT".getBytes(StandardCharsets.UTF_8));
            write(root, "config.toml", "password=PRIVATE".getBytes(StandardCharsets.UTF_8));
            write(root, "maps/ui.map", new byte[]{1,2,3});

            check(CampaignSaveArchive.hasSaves(root.toFile()), "Profiles not found");
            ByteArrayOutputStream exported = new ByteArrayOutputStream();
            CampaignSaveArchive.Result backedUp =
                CampaignSaveArchive.exportTo(root.toFile(), exported);
            check(backedUp.files == 3, "Wrong backup file count");
            check(backedUp.bytes == 7 + 4096 + 10, "Wrong backup length");
            byte[] backup = exported.toByteArray();
            check(backup.length > 100, "Backup ZIP missing");
            int entries = 0;
            try (ZipInputStream reader =
                    new ZipInputStream(new ByteArrayInputStream(backup))) {
                ZipEntry entry;
                while ((entry = reader.getNextEntry()) != null) {
                    entries++;
                    String name = entry.getName();
                    check(!name.contains("maps") && !name.contains("config.toml"),
                        "Backup leaked game data or configuration");
                    if (entries == 1) check(name.equals("Halo-Campaign-Backup-v1.txt"),
                        "Backup marker must be first");
                    reader.closeEntry();
                }
            }
            check(entries == 4, "ZIP should include marker plus three saves");

            // Existing campaign data are never overwritten until the ZIP
            // validates, then the old save tree is retained as a rollback.
            write(root, "save/u/UDATA/01A1/SaveMeta.xbx", "NEWER-PROFILE".getBytes(StandardCharsets.UTF_8));
            CampaignSaveArchive.Result restored = CampaignSaveArchive.restoreFrom(
                root.toFile(), new ByteArrayInputStream(backup));
            check(restored.files == 3, "Restore should install all files");
            check(restored.previousSave != null &&
                restored.previousSave.isDirectory(), "No prior-save rollback");
            check("PROFILE".equals(Files.readString(
                root.resolve("save/u/UDATA/01A1/SaveMeta.xbx"))), "Lost restored profile");
            check("NEWER-PROFILE".equals(Files.readString(
                restored.previousSave.toPath().resolve("u/UDATA/01A1/SaveMeta.xbx"))),
                "Previous saves were overwritten");
            check("CHECKPOINT".equals(Files.readString(
                root.resolve("save/z/UDATA/02B2/savegame.bin"))),
                "Campaign checkpoint not restored");
            check(Files.size(root.resolve("maps/ui.map")) == 3,
                "Restoring saves must never overwrite maps");
            check("password=PRIVATE".equals(Files.readString(root.resolve("config.toml"))),
                "Restoring saves must not overwrite config.toml");

            for (String bad : new String[] {
                "../config.toml",
                "/save/u/UDATA/evil",
                "save/u/../../config.toml",
                "save/x/UDATA/foo",
                "save/z/UDATA/../../maps/ui.map",
                "save/u/UDATA/safe\\..\\evil",
                "save/z/./foo",
                "save/u/UDATA/space name",
                "save/u/UDATA/evil:0",
            }) {
                final byte[] malicious = archive(bad, new byte[]{5});
                rejected("unsafe path " + bad, () -> CampaignSaveArchive.restoreFrom(
                    root.toFile(), new ByteArrayInputStream(malicious)));
                check("PROFILE".equals(Files.readString(
                    root.resolve("save/u/UDATA/01A1/SaveMeta.xbx"))),
                    "Rejected ZIP altered current saves");
            }
            rejected("foreign ZIP", () -> CampaignSaveArchive.restoreFrom(root.toFile(),
                new ByteArrayInputStream(zip(Map.of(
                    "something.txt", "hello".getBytes(StandardCharsets.UTF_8))))));
            Map<String,byte[]> noFiles = new LinkedHashMap<>();
            noFiles.put("Halo-Campaign-Backup-v1.txt",
                "HALO-CAMPAIGN-PROGRESS-BACKUP-V1\n".getBytes(StandardCharsets.US_ASCII));
            rejected("empty backup", () -> CampaignSaveArchive.restoreFrom(root.toFile(),
                new ByteArrayInputStream(zip(noFiles))));

            // Quota rejection must not replace existing saves.
            byte[] bomb = oversizedArchive();
            check(bomb.length < 256 * 1024, "ZIP bomb test should be compressed");
            rejected("oversized entry", () -> CampaignSaveArchive.restoreFrom(
                root.toFile(), new ByteArrayInputStream(bomb)));
            check("PROFILE".equals(Files.readString(
                root.resolve("save/u/UDATA/01A1/SaveMeta.xbx"))),
                "Oversized archive must not replace current saves");
            byte[] truncated = Arrays.copyOf(backup, 37);
            rejected("truncated backup", () -> CampaignSaveArchive.restoreFrom(
                root.toFile(), new ByteArrayInputStream(truncated)));
            // Two entries that collide on a case-insensitive filesystem are
            // rejected before commit, even if Linux would accept both.
            Map<String,byte[]> dup = new LinkedHashMap<>();
            dup.put("Halo-Campaign-Backup-v1.txt",
                "HALO-CAMPAIGN-PROGRESS-BACKUP-V1\n".getBytes(StandardCharsets.US_ASCII));
            dup.put("save/u/UDATA/Profile.bin", new byte[]{1});
            dup.put("save/u/UDATA/profile.bin", new byte[]{2});
            rejected("duplicate case-folded names", () ->
                CampaignSaveArchive.restoreFrom(root.toFile(),
                    new ByteArrayInputStream(zip(dup))));

            Path empty = Files.createTempDirectory("halo-no-saves-");
            try {
                check(!CampaignSaveArchive.hasSaves(empty.toFile()), "Empty data has saves");
                rejected("no saves", () -> CampaignSaveArchive.exportTo(
                    empty.toFile(), new ByteArrayOutputStream()));
            } finally { clean(empty); }

            Path linked = root.resolve("save/u/UDATA/01A1/Linked");
            try {
                Files.createSymbolicLink(linked, root.resolve("config.toml"));
                rejected("symlink in saved game", () ->
                    CampaignSaveArchive.exportTo(root.toFile(), new ByteArrayOutputStream()));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Android filesystem may not allow test symlinks.
            } finally {
                Files.deleteIfExists(linked);
            }
            System.out.println("CampaignSaveArchiveTest PASS (" + checks + " checks)");
        } finally { clean(root); }
    }
}
