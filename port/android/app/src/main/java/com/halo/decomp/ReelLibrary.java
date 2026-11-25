package com.halo.decomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Private MP4 library. Retains the most recent recording for re-editing,
 * three rendered reels, and no network uploads. A failed reel never erases
 * the original recording. */
final class ReelLibrary {
    private static final String TAG = "HaloVideo";
    private static final long REEL_BYTES_LIMIT = 48L * 1024 * 1024;
    private ReelLibrary() { }

    static File directory(Context context) throws IOException {
        File root = context.getExternalFilesDir(null);
        if (root == null)
            throw new IOException("Halo's private external storage is unavailable");
        File folder = new File(root, ReelSessionIndex.REEL_DIRECTORY);
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Can't create local reel folder");
        return folder;
    }

    static File[] finishedReels(File appRoot) {
        File root = new File(appRoot, ReelSessionIndex.REEL_DIRECTORY);
        File[] files = root.listFiles(file -> file.isFile() && !Files.isSymbolicLink(file.toPath()) &&
            file.getName().matches("reel-[0-9a-f]{32}\\.mp4") &&
            file.length() > 2048 && file.length() <= REEL_BYTES_LIMIT);
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    static File newestReel(File appRoot) {
        File[] candidates = finishedReels(appRoot);
        return candidates.length > 0 ? candidates[0] : null;
    }

    static void addSession(File appRoot, ReelSessionIndex.Session session)
            throws IOException {
        File folder = new File(appRoot, ReelSessionIndex.REEL_DIRECTORY);
        if (!folder.isDirectory() || !session.source(appRoot).isFile())
            throw new IOException("Completed video source unavailable");
        File index = new File(folder, ReelSessionIndex.INDEX);
        if (index.isFile() && index.length() > ReelSessionIndex.INDEX_LIMIT - 200)
            throw new IOException("Recording index is full");
        try (FileOutputStream stream = new FileOutputStream(index, true)) {
            stream.write(ReelSessionIndex.format(session)
                .getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }

    /** The most recent completed source stays intact even when a clip
     * is generated. Earlier sources are deleted only after the latest
     * reel exists, capping privacy exposure and phone storage. */
    static synchronized int assemble(File appRoot) {
        if (appRoot == null || !appRoot.isDirectory()) return 0;
        int complete = 0;
        try {
            List<ReelSessionIndex.Session> sessions = ReelSessionIndex.load(appRoot);
            if (sessions.isEmpty()) return 0;
            CareerRecords.Career career = CareerRecords.load(appRoot);
            List<MatchHighlights.Moment> moments = MatchHighlights.load(appRoot);
            for (ReelSessionIndex.Session session : sessions) {
                File source = session.source(appRoot);
                if (!source.isFile()) continue;
                for (CareerRecords.Match match : career.matches) {
                    if (match.cooperative() || match.incomplete) continue;
                    File out = new File(source.getParentFile(),
                        ReelSessionIndex.reelName(match));
                    if (HaloVideoFiles.suppressed(appRoot, out.getName())) continue;
                    if (out.isFile() && out.length() > 2048) continue;
                    List<ReelSessionIndex.Clip> windows =
                        ReelSessionIndex.windows(session, match,
                            MatchHighlights.forMatch(match, moments));
                    if (windows.isEmpty()) continue;
                    try {
                        if (ReelVideoMuxer.build(source, out, windows)) complete++;
                    } catch (IOException problem) {
                        Log.w(TAG, "Local reel export failed; original recording kept",
                            problem);
                    }
                }
            }
            trimFinished(appRoot);
            trimSources(appRoot);
        } catch (IOException failed) {
            Log.w(TAG, "Could not read local match video metadata", failed);
        }
        return complete;
    }

    /** Shares the assembly lock, so a user-confirmed deletion cannot race
     * the muxer rebuilding or trimming the same completed files. */
    static synchronized HaloVideoFiles.DeleteResult deleteVideos(
            File appRoot, HaloVideoFiles.Entry[] confirmed) {
        return HaloVideoFiles.delete(appRoot, confirmed);
    }

    private static void trimFinished(File root) {
        File[] reels = finishedReels(root);
        for (int i = ReelSessionIndex.RETAIN_REELS; i < reels.length; i++)
            if (!reels[i].delete())
                Log.w(TAG, "Old reel was not removed: " + reels[i].getName());
    }

    private static void trimSources(File root) {
        File parent = new File(root, ReelSessionIndex.REEL_DIRECTORY);
        File[] sources = parent.listFiles(file -> file.isFile() &&
            ReelSessionIndex.safeFileName(file.getName()));
        if (sources == null || sources.length <= 1) return;
        java.util.Arrays.sort(sources,
            (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        // Preserve the two latest source recordings for correction of
        // inaccurate game-time alignment. Never delete earlier source files
        // unless a real reel has been rendered. Total raw storage is capped
        // separately before starting another capture.
        if (finishedReels(root).length == 0) return;
        for (int i = 2; i < sources.length; i++) {
            if (!sources[i].delete())
                Log.w(TAG, "Old raw recording retained: " + sources[i].getName());
        }
    }
}
