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

/** Private MP4 library. New match-scoped originals may be discarded
 * only after a verified matching reel. Old recordings and all reels stay
 * until the user deletes them; failures never trigger automatic trimming. */
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
            file.getName().matches("(?:reel|ending)-[0-9a-f]{32}\\.mp4") &&
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

    /** Match-scoped sources are processed first. Legacy sources remain
     * recoverable using their original approximate wall-clock index. */
    static synchronized int assemble(File appRoot) {
        if (appRoot == null || !appRoot.isDirectory()) return 0;
        int complete = 0;
        try {
            CareerRecords.Career career = CareerRecords.load(appRoot);
            List<MatchHighlights.Moment> moments = MatchHighlights.load(appRoot);
            complete += assembleMatched(appRoot, career, moments);
            // A damaged or oversized legacy index cannot block new match reels.
            List<ReelSessionIndex.Session> sessions = ReelSessionIndex.load(appRoot);
            for (ReelSessionIndex.Session session : sessions) {
                File source = session.source(appRoot);
                if (!source.isFile() || MatchVideoTimeline.metadata(source).exists()) continue;
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

    private static int assembleMatched(File root, CareerRecords.Career career,
                                       List<MatchHighlights.Moment> moments) {
        int built = 0;
        for (File raw : HaloVideoFiles.rawRecordings(root)) {
            try {
                MatchVideoTimeline timeline = MatchVideoTimeline.read(raw);
                if (timeline == null) continue;
                CareerRecords.Match match = null;
                for (CareerRecords.Match candidate : career.matches)
                    if (timeline.id.equals(candidate.id)) { match = candidate; break; }
                if (match != null && match.cooperative()) continue;
                MatchVideoTimeline.Plan plan = match == null ? null :
                    timeline.plan(match, MatchHighlights.forMatch(match, moments));
                if(plan == null || plan.windows.isEmpty()){
                    if(!timeline.keepFull && timeline.durationMs>=2000 && timeline.points.size()>=2){
                        File ending=new File(raw.getParentFile(),"ending-"+timeline.id+".mp4");
                        if(!ending.exists() && !HaloVideoFiles.suppressed(root,ending.getName())){
                            long end=Math.min(timeline.durationMs,timeline.points.get(timeline.points.size()-1).ms)*1000;
                            if(end>=2000000 && ReelVideoMuxer.build(raw,ending,java.util.Collections.singletonList(
                                    new ReelSessionIndex.Clip(Math.max(0,end-12000000),end)),true))built++;
                        }
                    }
                    continue;
                }
                File reel = new File(raw.getParentFile(), ReelSessionIndex.reelName(match));
                if (HaloVideoFiles.suppressed(root, reel.getName()) || reel.exists()) continue;

                // Only a newly muxed, fully covered, verified reel can authorize
                // automatic cleanup. Existing reels never authorize deletion.
                if (!ReelVideoMuxer.build(raw, reel, plan.windows, true)) continue;
                VideoCaptureDiagnostic.Result check = VideoCaptureDiagnostic.inspect(reel);
                if (!"PICTURE DETECTED".equals(check.status) || check.decoded < 3 ||
                    check.decoded - check.black < 3) {
                    Log.w(TAG, "Reel picture verification failed; original retained");
                    continue;
                }
                long plannedUs = 0;
                for (ReelSessionIndex.Clip clip : plan.windows)
                    plannedUs += clip.endUs - clip.startUs;
                if ((check.durationSeconds + 2L) * 1000000L < plannedUs) {
                    Log.w(TAG, "Reel shorter than requested; original retained");
                    continue;
                }
                built++;
                if (plan.mayDiscard(timeline) && timeline.stillSame(raw)) {
                    // Sync the completed reel again after its atomic rename.
                    // If any durability check fails, the original is retained.
                    try (java.io.RandomAccessFile synced = new java.io.RandomAccessFile(reel, "rw")) {
                        synced.getFD().sync();
                    }
                    Files.delete(raw.toPath());
                    File metadata = MatchVideoTimeline.metadata(raw);
                    if (Files.isRegularFile(metadata.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        Files.deleteIfExists(metadata.toPath());
                    Log.i(TAG, "Verified match reel retained; full recording removed");
                }
            } catch (IOException | RuntimeException failed) {
                Log.w(TAG, "Match-scoped reel not safely completed; keeping available original", failed);
            }
        }
        return built;
    }
}
