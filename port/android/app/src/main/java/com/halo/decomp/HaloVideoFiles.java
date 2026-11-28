package com.halo.decomp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** Exact app-private filenames allowed for playback, inspection and
 * user-requested SAF export and deletion. No directory traversal or arbitrary files. */
final class HaloVideoFiles {
    private HaloVideoFiles() { }

    static boolean validName(String filename) {
        return filename != null &&
            (filename.matches("(?:reel|ending)-[0-9a-f]{32}\\.mp4") ||
             ReelSessionIndex.safeFileName(filename));
    }

    static File[] rawRecordings(File appRoot) {
        if (appRoot == null) return new File[0];
        File folder = new File(appRoot, ReelSessionIndex.REEL_DIRECTORY);
        File[] files = folder.listFiles(file ->
            file.isFile() && !Files.isSymbolicLink(file.toPath()) &&
            ReelSessionIndex.safeFileName(file.getName()) &&
            file.length() > 2048 &&
            file.length() <= ReelSessionIndex.SOURCE_LIMIT);
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) ->
            Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    /** A deletion target is an exact completed-file snapshot, never a
     * directory, pending capture, arbitrary path, or external export. */
    static final class Entry {
        final String name;
        final long bytes, modified;
        Entry(File f) {
            name = f.getName();
            bytes = f.length();
            modified = f.lastModified();
        }
        boolean original() { return ReelSessionIndex.safeFileName(name); }
    }

    static final class DeleteResult {
        int removed, skipped;
        long bytes;
    }

    private static File safeFolder(File root) {
        if (root == null) return null;
        File folder = new File(root, ReelSessionIndex.REEL_DIRECTORY);
        return Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)
            ? folder : null;
    }

    static Entry[] deletable(File root) {
        File folder = safeFolder(root);
        if (folder == null) return new Entry[0];
        File[] files = folder.listFiles(f -> validName(f.getName()) &&
            Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS));
        if (files == null) return new Entry[0];
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<Entry> entries = new ArrayList<>();
        for (File file : files) entries.add(new Entry(file));
        return entries.toArray(new Entry[0]);
    }

    /** Explicitly deleted reels must not be regenerated from retained
     * originals. The empty marker contains no footage or career data. */
    static boolean suppressed(File root, String name) {
        if (!validName(name) || ReelSessionIndex.safeFileName(name)) return false;
        File folder = safeFolder(root);
        return folder != null && Files.isRegularFile(
            new File(folder, name + ".deleted").toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static DeleteResult delete(File root, Entry[] confirmed) {
        DeleteResult result = new DeleteResult();
        if (confirmed == null) return result;
        File folder = safeFolder(root);
        for (Entry entry : confirmed) {
            if (folder == null || entry == null || !validName(entry.name)) {
                result.skipped++;
                continue;
            }
            File target = new File(folder, entry.name);
            try {
                if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    target.length() != entry.bytes || target.lastModified() != entry.modified) {
                    result.skipped++;
                    continue;
                }
                if (!entry.original()) {
                    File marker = new File(folder, entry.name + ".deleted");
                    try {
                        Files.createFile(marker.toPath());
                    } catch (FileAlreadyExistsException exists) {
                        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS))
                            throw new IOException("Invalid deletion marker");
                    }
                }
                Files.delete(target.toPath());
                result.removed++;
                result.bytes += entry.bytes;
                if (entry.original()) {
                    // This metadata belongs only to the explicitly removed raw.
                    File metadata = new File(folder, entry.name + ".match");
                    try {
                        if (Files.isRegularFile(metadata.toPath(), LinkOption.NOFOLLOW_LINKS))
                            Files.deleteIfExists(metadata.toPath());
                    } catch (IOException ignored) { /* video itself was removed */ }
                }
            } catch (IOException | SecurityException problem) {
                result.skipped++;
            }
        }
        return result;
    }
}
