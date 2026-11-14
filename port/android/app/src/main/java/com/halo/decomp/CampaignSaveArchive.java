package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable, user-chosen backup for Halo's actual Xbox save roots:
 * appExternalFiles/save/u (profiles) and /save/z (checkpoints).
 *
 * An Android package UPDATE with the same signer preserves both directories.
 * An UNINSTALL removes app-owned storage, so this ZIP must be saved outside
 * Android/data/ (through ACTION_CREATE_DOCUMENT) to survive a reinstall.
 *
 * No Android APIs, credentials, config.toml, maps or APK files are included.
 * A restore fully validates/stages the archive before switching directories.
 * Existing saves are preserved in a unique rollback directory, never erased.
 */
final class CampaignSaveArchive {
    private static final String MARKER = "Halo-Campaign-Backup-v1.txt";
    private static final byte[] MAGIC =
        "HALO-CAMPAIGN-PROGRESS-BACKUP-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final String[] SAVE_ROOTS = { "u", "z" };
    private static final int MAX_FILES = 4096;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_ENTRY_NAME = 230;
    private static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    static final class Result {
        final int files;
        final long bytes;
        final File previousSave; // non-null only after a successful restore

        Result(int files, long bytes, File previousSave) {
            this.files = files;
            this.bytes = bytes;
            this.previousSave = previousSave;
        }
    }

    private CampaignSaveArchive() { }

    private static Path root(File dataRoot) throws IOException {
        if (dataRoot == null || !dataRoot.isDirectory() ||
            Files.isSymbolicLink(dataRoot.toPath()))
            throw new IOException("Halo's data directory is unavailable.");
        return dataRoot.toPath().toRealPath();
    }

    /** A strict relative name for both archive creation and extraction.
     * No ZIP entry may point at maps, settings, outside paths or devices. */
    private static boolean allowed(String name) {
        if (name == null || name.length() < 8 || name.length() > MAX_ENTRY_NAME ||
            name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 ||
            name.startsWith("/") || name.endsWith("/"))
            return false;
        if (!(name.startsWith("save/u/") || name.startsWith("save/z/")))
            return false;
        String[] components = name.split("/", -1);
        if (components.length < 3 || components.length > MAX_DEPTH + 2)
            return false;
        for (String part : components) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") ||
                !part.matches("[A-Za-z0-9._-]{1,100}"))
                return false;
        }
        return true;
    }

    private static List<Path> collectFiles(Path dataRoot) throws IOException {
        Path save = dataRoot.resolve("save");
        if (Files.isSymbolicLink(save))
            throw new IOException("Refusing a linked Halo save directory.");
        List<Path> collected = new ArrayList<>();
        final long[] total = {0};
        for (String folderName : SAVE_ROOTS) {
            Path folder = save.resolve(folderName);
            if (Files.isSymbolicLink(folder))
                throw new IOException("Refusing a linked Halo save folder.");
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
                continue;
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Halo's save folder is not a directory.");
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(
                        Path path, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() ||
                        folder.relativize(path).getNameCount() > MAX_DEPTH)
                        throw new IOException("Unsafe Halo save directory.");
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(
                        Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                        throw new IOException("Non-regular Halo save file.");
                    String name = dataRoot.relativize(file).toString().replace(File.separatorChar, '/');
                    if (!allowed(name))
                        throw new IOException("Unsafe Halo save filename.");
                    long length = attributes.size();
                    if (length > MAX_FILE_BYTES || total[0] > MAX_TOTAL_BYTES - length ||
                        collected.size() >= MAX_FILES)
                        throw new IOException("Halo campaign backup exceeds safe size limits.");
                    total[0] += length;
                    collected.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (collected.isEmpty())
            throw new IOException("No campaign saves found. Start a campaign and save first.");
        collected.sort((a,b) -> a.toString().compareTo(b.toString()));
        return collected;
    }

    static boolean hasSaves(File dataRoot) {
        try { return !collectFiles(root(dataRoot)).isEmpty(); }
        catch (IOException missingOrUnsafe) { return false; }
    }

    static Result exportTo(File dataRoot, OutputStream destination) throws IOException {
        if (destination == null)
            throw new IOException("Choose a destination for the backup.");
        Path base = root(dataRoot);
        List<Path> files = collectFiles(base);
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.putNextEntry(new ZipEntry(MARKER));
            zip.write(MAGIC);
            zip.closeEntry();
            for (Path file : files) {
                String name = base.relativize(file).toString().replace(File.separatorChar, '/');
                if (!allowed(name) || Files.isSymbolicLink(file))
                    throw new IOException("Unsafe Halo save file changed during backup.");
                zip.putNextEntry(new ZipEntry(name));
                long written = 0;
                try (InputStream in = Files.newInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (n == 0) continue;
                        if (written > MAX_FILE_BYTES - n ||
                            bytes > MAX_TOTAL_BYTES - n)
                            throw new IOException("Campaign save grew beyond the backup limit.");
                        zip.write(buffer, 0, n);
                        written += n;
                        bytes += n;
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
        }
        return new Result(files.size(), bytes, null);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to);
        }
    }

    static Result restoreFrom(File dataRoot, InputStream source) throws IOException {
        if (source == null)
            throw new IOException("Choose a campaign backup ZIP first.");
        Path base = root(dataRoot);
        Path save = base.resolve("save");
        if (Files.isSymbolicLink(save))
            throw new IOException("Refusing linked Halo save storage.");
        if (Files.exists(save) && !Files.isDirectory(save, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Halo save storage isn't a directory.");

        Path stage = Files.createTempDirectory(base, ".halo-save-stage-");
        Path replacement = stage.resolve("save");
        Set<String> seen = new HashSet<>();
        long total = 0;
        int count = 0;
        Path previous = null;
        boolean committed = false;
        try {
            try (ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry first = zip.getNextEntry();
                if (first == null || !MARKER.equals(first.getName()) ||
                    first.isDirectory())
                    throw new IOException("This isn't a Halo campaign backup.");
                byte[] marker = new byte[MAGIC.length + 1];
                int offset = 0;
                while (offset < marker.length) {
                    int n = zip.read(marker, offset, marker.length - offset);
                    if (n < 0) break;
                    if (n > 0) offset += n;
                }
                if (offset != MAGIC.length)
                    throw new IOException("Halo campaign backup version isn't supported.");
                for (int i=0; i<MAGIC.length; i++)
                    if (marker[i] != MAGIC[i])
                        throw new IOException("Halo campaign backup version isn't supported.");
                zip.closeEntry();

                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !allowed(name) ||
                        !seen.add(name.toLowerCase(Locale.ROOT)) ||
                        count >= MAX_FILES ||
                        entry.getSize() > MAX_FILE_BYTES)
                        throw new IOException("Backup contains an unsafe or repeated file.");
                    Path output = stage.resolve(name).normalize();
                    if (!output.startsWith(replacement))
                        throw new IOException("Backup path escapes Halo save storage.");
                    Files.createDirectories(output.getParent());
                    long written = 0;
                    try (OutputStream out = Files.newOutputStream(output,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            if (n == 0) continue;
                            if (written > MAX_FILE_BYTES - n ||
                                total > MAX_TOTAL_BYTES - n)
                                throw new IOException("Campaign backup expands too far.");
                            out.write(buffer, 0, n);
                            total += n;
                            written += n;
                        }
                        out.flush();
                    }
                    zip.closeEntry(); // validates ZIP CRC before committing
                    count++;
                }
            }
            if (count == 0)
                throw new IOException("This archive has no campaign saves.");
            // ZIP fully checked first. Move the current save tree aside and
            // commit only a complete replacement. Never erase the old tree.
            if (Files.exists(save, LinkOption.NOFOLLOW_LINKS)) {
                previous = base.resolve(".halo-save-before-restore-" + UUID.randomUUID());
                move(save, previous);
            }
            try {
                move(replacement, save);
                committed = true;
            } catch (IOException failure) {
                if (previous != null) {
                    try { move(previous, save); }
                    catch (IOException rollbackFailed) { failure.addSuppressed(rollbackFailed); }
                }
                throw failure;
            }
            return new Result(count, total, previous == null ? null : previous.toFile());
        } finally {
            // Don't follow symlinks. If a staged restore fails, leave a
            // readable copy of the original save directory in place.
            if (!committed || Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) {
                    try (java.util.stream.Stream<Path> tree = Files.walk(stage)) {
                        tree.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException ignored) { }
                        });
                    } catch (IOException ignored) { }
                }
            }
        }
    }
}
