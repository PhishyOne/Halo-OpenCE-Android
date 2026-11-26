package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read-only, bounded highlight catalog and random-access byte slices.
 * A peer cannot request a path, an original, a pending file or an APK. */
final class LanReelProtocol {
    static final int MAX_REELS = 32, CHUNK = 256 * 1024;
    static final long MAX_REEL = 48L * 1024 * 1024;
    static final String LIST = "HRL1", HEADER = "HRH1", ITEM = "HRV1";
    static final String RANGE = "HRQ1", DATA = "HRD1";

    static final class Reel {
        final String id;
        final long size, modified;
        Reel(String id, long size, long modified) {
            if (!LanBoardProtocol.idValid(id) || size < 2048 || size > MAX_REEL ||
                modified <= 0 || modified > 4102444800000L)
                throw new IllegalArgumentException("Invalid saved highlight");
            this.id = id; this.size = size; this.modified = modified;
        }
    }

    private LanReelProtocol() { }

    static boolean request(String line) {
        return line != null && (line.startsWith(LIST + "|") || line.startsWith(RANGE + "|"));
    }

    private static long number(String value, long max) throws IOException {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,12})"))
            throw new IOException("Invalid highlight number");
        try {
            long n = Long.parseLong(value);
            if (n > max) throw new IOException("Highlight number out of range");
            return n;
        } catch (NumberFormatException bad) { throw new IOException("Invalid number", bad); }
    }

    private static File folder(File root) {
        if (root == null) return null;
        File dir = new File(root, "reels");
        return Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) ? dir : null;
    }

    private static File file(File root, String id) {
        File dir = folder(root);
        if (dir == null || !LanBoardProtocol.idValid(id)) return null;
        File f = new File(dir, "reel-" + id + ".mp4");
        return Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) ? f : null;
    }

    static List<Reel> catalog(File root) {
        List<Reel> result = new ArrayList<>();
        File dir = folder(root);
        if (dir == null) return result;
        File[] files = dir.listFiles(f -> f.getName().matches("reel-[0-9a-f]{32}\\.mp4") &&
            Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS));
        if (files == null) return result;
        Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (result.size() == MAX_REELS) break;
            try {
                result.add(new Reel(f.getName().substring(5, 37), f.length(), f.lastModified()));
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }

    private static void identity(String nonce, String owner) {
        if (!LanBoardProtocol.nonceValid(nonce) || !LanBoardProtocol.idValid(owner))
            throw new IllegalArgumentException("Invalid highlight identity");
    }

    static String listRequest(String nonce, String owner) {
        identity(nonce, owner);
        return LIST + "|" + nonce + "|" + owner + "\n";
    }
    static String rangeRequest(String nonce, String owner, Reel reel, long offset, int length) {
        identity(nonce, owner);
        if (reel == null || offset < 0 || offset >= reel.size || length <= 0 ||
            length > CHUNK || length > reel.size - offset)
            throw new IllegalArgumentException("Invalid highlight range");
        return RANGE + "|" + nonce + "|" + owner + "|" + reel.id + "|" + reel.size +
            "|" + reel.modified + "|" + offset + "|" + length + "\n";
    }

    static void write(OutputStream out, String row) throws IOException {
        out.write(row.getBytes(StandardCharsets.US_ASCII));
    }

    /** One bounded response per connection. Caller must check private
     * subnet and current sharing consent before invoking this handler. */
    static void serve(String request, File root, String owner, boolean enabled,
                      OutputStream out) throws IOException {
        if (!enabled || !LanBoardProtocol.idValid(owner) || request == null ||
            request.length() > LanBoardProtocol.MAX_LINE)
            throw new IOException("Highlight sharing unavailable");
        String[] p = request.split("\\|", -1);
        if (p.length < 3 || !LanBoardProtocol.nonceValid(p[1]) || !owner.equals(p[2]))
            throw new IOException("Wrong highlight owner");
        if (LIST.equals(p[0]) && p.length == 3) {
            List<Reel> reels = catalog(root);
            write(out, HEADER + "|" + p[1] + "|" + owner + "|" + reels.size() + "\n");
            for (Reel r : reels)
                write(out, ITEM + "|" + r.id + "|" + r.size + "|" + r.modified + "\n");
            out.flush();
            return;
        }
        if (!RANGE.equals(p[0]) || p.length != 8 || !LanBoardProtocol.idValid(p[3]))
            throw new IOException("Invalid highlight request");
        long size = number(p[4], MAX_REEL), modified = number(p[5], 4102444800000L);
        long offset = number(p[6], MAX_REEL);
        int length = (int)number(p[7], CHUNK);
        if (size < 2048 || modified <= 0 || offset >= size ||
            length <= 0 || length > size - offset)
            throw new IOException("Highlight range out of bounds");
        File f = file(root, p[3]);
        if (f == null || f.length() != size || f.lastModified() != modified)
            throw new IOException("Highlight deleted or changed");
        // Read just this slice. Never follow a symlink, even if the directory
        // changed between the catalog and the request.
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel input = Files.newByteChannel(f.toPath(),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.size() != size) throw new IOException("Highlight changed");
            input.position(offset);
            while (buffer.hasRemaining())
                if (input.read(buffer) <= 0) throw new IOException("Truncated highlight");
        }
        if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            f.length() != size || f.lastModified() != modified)
            throw new IOException("Highlight changed during read");
        write(out, DATA + "|" + p[1] + "|" + length + "\n");
        out.write(buffer.array());
        out.flush();
    }

    static List<Reel> readCatalog(InputStream in, String nonce, String owner) throws IOException {
        String[] p = LanBoardProtocol.boundedLine(in).split("\\|", -1);
        if (p.length != 4 || !HEADER.equals(p[0]) || !nonce.equals(p[1]) || !owner.equals(p[2]))
            throw new IOException("Unrelated highlight catalog");
        int count = (int)number(p[3], MAX_REELS);
        List<Reel> reels = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            String[] r = LanBoardProtocol.boundedLine(in).split("\\|", -1);
            if (r.length != 4 || !ITEM.equals(r[0]) || !ids.add(r[1]))
                throw new IOException("Invalid highlight entry");
            try { reels.add(new Reel(r[1], number(r[2], MAX_REEL),
                number(r[3], 4102444800000L))); }
            catch (IllegalArgumentException bad) { throw new IOException("Invalid highlight metadata", bad); }
        }
        return reels;
    }

    static byte[] readRange(InputStream in, String nonce, int length) throws IOException {
        if (length <= 0 || length > CHUNK) throw new IOException("Invalid range size");
        String[] p = LanBoardProtocol.boundedLine(in).split("\\|", -1);
        if (p.length != 3 || !DATA.equals(p[0]) || !nonce.equals(p[1]) ||
            number(p[2], CHUNK) != length) throw new IOException("Unrelated highlight data");
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(bytes, offset, length - offset);
            if (n <= 0) throw new IOException("Incomplete highlight data");
            offset += n;
        }
        return bytes;
    }
}
