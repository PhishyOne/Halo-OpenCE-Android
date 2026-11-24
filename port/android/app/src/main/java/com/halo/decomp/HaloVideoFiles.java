package com.halo.decomp;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Exact app-private filenames allowed for playback, inspection and
 * user-requested SAF export. No directory traversal or arbitrary files. */
final class HaloVideoFiles {
    private HaloVideoFiles() { }

    static boolean validName(String filename) {
        return filename != null &&
            (filename.matches("reel-[0-9a-f]{32}\\.mp4") ||
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
}
