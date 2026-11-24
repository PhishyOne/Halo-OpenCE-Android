package com.halo.decomp;

import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only original-video listing and exact SAF-export name policy. */
public final class HaloVideoFilesTest {
    private static int checks;
    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        String full = "session-1795000000000.mp4";
        String reel = "reel-0123456789abcdef0123456789abcdef.mp4";
        check(HaloVideoFiles.validName(full), "exact source video filename");
        check(HaloVideoFiles.validName(reel), "exact reel video filename");
        for (String invalid : new String[] {
            "../" + full, "session-1795000000000.mp4.pending",
            "session-1795000000000.mp4/../../secret",
            "session-17950000000.mp4", "session-1795000000000.MP4",
            "reel-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz.mp4",
            "career-matches-v1.log", "data.zip"
        }) check(!HaloVideoFiles.validName(invalid),
            "invalid SAF export name must be rejected: " + invalid);
        Path root = Files.createTempDirectory("halo-video-file-policy-");
        try {
            Path dir = Files.createDirectory(root.resolve("reels"));
            Path original = dir.resolve(full);
            Files.write(original, new byte[4096]);
            Path unrelated = dir.resolve("other.mp4");
            Files.write(unrelated, new byte[4096]);
            Path tiny = dir.resolve("session-1795000000001.mp4");
            Files.write(tiny, new byte[3]);
            check(HaloVideoFiles.rawRecordings(root.toFile()).length == 1,
                "only valid private full MP4 within range listed");
            Path symlink = dir.resolve("session-1795000000002.mp4");
            try {
                Files.createSymbolicLink(symlink, original);
                check(HaloVideoFiles.rawRecordings(root.toFile()).length == 1,
                    "symlink from shared external directory must be refused");
            } catch (UnsupportedOperationException |
                     java.nio.file.FileSystemException ignored) {
                // Some environments prohibit symlink creation.
            }
            Files.deleteIfExists(symlink);
            Files.delete(original);
            Files.delete(unrelated);
            Files.delete(tiny);
            Files.delete(dir);
        } finally {
            Files.deleteIfExists(root);
        }
        System.out.println("PASS HaloVideoFilesTest: " + checks + " checks");
    }
}
