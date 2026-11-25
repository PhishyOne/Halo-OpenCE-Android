package com.halo.decomp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Exercise actual deletion code against temporary files only. */
public final class HaloVideoDeletionTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static HaloVideoFiles.Entry entry(File root, String name) {
        return Arrays.stream(HaloVideoFiles.deletable(root))
            .filter(e -> e.name.equals(name)).findFirst().orElseThrow();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-video-delete-");
        try {
            Path folder = Files.createDirectory(root.resolve("reels"));
            File data = root.toFile();
            String raw = "session-1795000000000.mp4";
            String reel = "reel-0123456789abcdef0123456789abcdef.mp4";
            String corrupt = "session-1795000000001.mp4";
            String pending = "session-1795000000002.mp4.pending";
            Path source = Files.write(folder.resolve(raw), new byte[4096]);
            Path rendered = Files.write(folder.resolve(reel), new byte[8192]);
            Path tiny = Files.write(folder.resolve(corrupt), new byte[3]);
            Path active = Files.write(folder.resolve(pending), new byte[100]);
            Path index = Files.write(folder.resolve(ReelSessionIndex.INDEX), new byte[100]);
            Path career = Files.write(root.resolve("career-matches-v1.log"), new byte[100]);
            Path outside = Files.write(root.resolve("outside.mp4"), new byte[100]);
            Path link = folder.resolve("session-1795000000003.mp4");
            Files.createSymbolicLink(link, outside);
            check(HaloVideoFiles.deletable(data).length == 3,
                "completed including tiny; never active, index or symlink");
            check(!HaloVideoFiles.suppressed(data, reel), "no implicit suppression");
            HaloVideoFiles.Entry selected = entry(data, reel);
            HaloVideoFiles.DeleteResult r = HaloVideoFiles.delete(data,
                new HaloVideoFiles.Entry[]{selected});
            check(r.removed == 1 && r.skipped == 0 && r.bytes == 8192, "exact count/bytes");
            check(!Files.exists(rendered) && Files.exists(source), "only selected reel gone");
            check(HaloVideoFiles.suppressed(data, reel), "persistent rebuild suppression");
            check(!HaloVideoFiles.suppressed(data, raw), "not a source marker");
            check(HaloVideoFiles.delete(data, new HaloVideoFiles.Entry[]{selected}).skipped == 1,
                "stale selection cannot delete a missing file");
            HaloVideoFiles.Entry changed = entry(data, raw);
            Files.write(source, new byte[4097]);
            check(HaloVideoFiles.delete(data, new HaloVideoFiles.Entry[]{changed}).skipped == 1 &&
                Files.exists(source), "changed file requires fresh confirmation");
            r = HaloVideoFiles.delete(data, new HaloVideoFiles.Entry[]{entry(data, raw)});
            check(r.removed == 1 && r.bytes == 4097 && Files.exists(tiny),
                "source alone deleted");
            check(HaloVideoFiles.deletable(data).length == 1, "gallery refresh");
            r = HaloVideoFiles.delete(data, HaloVideoFiles.deletable(data));
            check(r.removed == 1 && r.bytes == 3, "clear remaining corrupt video");
            check(HaloVideoFiles.deletable(data).length == 0, "all completed removed");
            check(Files.exists(active) && Files.exists(index) && Files.exists(career) &&
                Files.exists(outside) && Files.isSymbolicLink(link),
                "active capture, index, career and unrelated files kept");
            Files.write(rendered, new byte[4096]);
            Files.delete(folder.resolve(reel + ".deleted"));
            Files.createSymbolicLink(folder.resolve(reel + ".deleted"), outside);
            r = HaloVideoFiles.delete(data, new HaloVideoFiles.Entry[]{entry(data, reel)});
            check(r.skipped == 1 && Files.exists(rendered), "invalid marker refuses deletion");
            check(!HaloVideoFiles.suppressed(data, reel), "symlink is not a valid marker");
            check(!HaloVideoFiles.validName("../" + raw), "traversal refused");
            Path linkedRoot = Files.createDirectory(root.resolve("linked-root"));
            Files.createSymbolicLink(linkedRoot.resolve("reels"), folder);
            check(HaloVideoFiles.deletable(linkedRoot.toFile()).length == 0,
                "linked directory refused");
            check(HaloVideoFiles.delete(linkedRoot.toFile(),
                new HaloVideoFiles.Entry[]{entry(data, reel)}).skipped == 1,
                "linked directory cannot delete");
        } finally {
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                for (Path p : (Iterable<Path>)stream.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(p);
            }
        }
        System.out.println("PASS HaloVideoDeletionTest: " + checks + " checks");
    }
}
