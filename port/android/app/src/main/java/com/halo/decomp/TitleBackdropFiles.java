package com.halo.decomp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;

/** Locally generated title assets only. A bounded, indexed JPEG sequence
 * avoids a second game engine or device-specific video encoders. */
final class TitleBackdropFiles {
    static final String FINAL = "title-backdrop-v1";
    static final String PENDING = FINAL + ".pending";
    static final int MAGIC = 0x48425431; // HBT1
    static final int FPS = 15, SECONDS = 60, FRAMES = FPS * SECONDS;
    static final int MIN_FRAMES = FPS * 50;
    static final int MAX_FRAME = 160 * 1024;
    static final long MAX_VIDEO = 32L * 1024 * 1024;
    static final long MAX_AUDIO = 44L + 48000L * 4 * SECONDS;
    private TitleBackdropFiles() { }

    /** One automatic attempt per installed version; valid caches take
     * priority even after an update. New versions may fix capture failures. */
    static boolean shouldPrepare(boolean ready, long attemptedVersion, long installedVersion) {
        return !ready && installedVersion >= 0 && attemptedVersion != installedVersion;
    }


    static File pending(File root) { return new File(root, PENDING); }
    static File directory(File root) { return new File(root, FINAL); }

    private static boolean regular(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static void safeDirectory(File dir) throws IOException {
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe title cache folder");
    }

    /** Only remove our two fixed staging files; never walk arbitrary trees. */
    static void clearPending(File root) throws IOException {
        clearOwnedDirectory(pending(root));
    }

    private static void clearOwnedDirectory(File dir) throws IOException {
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        safeDirectory(dir);
        String[] contents = dir.list();
        if (contents == null) throw new IOException("Cannot list title cache");
        for (String name : contents)
            if (!name.equals("frames.hbt") && !name.equals("audio.wav"))
                throw new IOException("Unexpected file in title cache");
        for (String name : new String[]{"frames.hbt", "audio.wav"})
            Files.deleteIfExists(new File(dir, name).toPath());
        Files.delete(dir.toPath());
    }

    static void prepare(File root) throws IOException {
        safeDirectory(root);
        if (Files.exists(directory(root).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (ready(root)) throw new IOException("Original title is already prepared.");
            // A corrupt cache is disposable, unlike the installed game data.
            clearOwnedDirectory(directory(root));
        }
        if (root.getUsableSpace() < MAX_VIDEO + MAX_AUDIO + 16L * 1024 * 1024)
            throw new IOException("Not enough free space for the original title.");
        clearPending(root);
        Files.createDirectory(pending(root).toPath());
    }

    static final class Writer implements Closeable {
        private final RandomAccessFile file;
        private int count;
        private boolean completed;
        Writer(File target, int width, int height) throws IOException {
            dimensions(width, height, FPS);
            if (!target.createNewFile()) throw new IOException("Title staging file exists");
            file = new RandomAccessFile(target, "rw");
            file.writeInt(MAGIC); file.writeInt(width); file.writeInt(height);
            file.writeInt(FPS); file.writeInt(0);
        }
        void frame(byte[] jpeg) throws IOException {
            if (completed || count >= FRAMES || jpeg == null ||
                jpeg.length < 4 || jpeg.length > MAX_FRAME ||
                (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216 ||
                file.getFilePointer() + 4 + jpeg.length > MAX_VIDEO)
                throw new IOException("Title animation exceeded its cache limit");
            file.writeInt(jpeg.length); file.write(jpeg); count++;
        }
        int count() { return count; }
        void complete() throws IOException {
            if (count < MIN_FRAMES) throw new IOException("Too few title frames");
            file.seek(16); file.writeInt(count); file.getFD().sync(); completed = true;
        }
        @Override public void close() throws IOException { file.close(); }
    }

    private static void dimensions(int width, int height, int fps) throws IOException {
        if (width < 160 || width > 640 || height < 90 || height > 480 ||
            fps != FPS || (width & 1) != 0 || (height & 1) != 0)
            throw new IOException("Invalid title frame dimensions");
    }

    static final class Reader implements Closeable {
        final int width, height, fps, count;
        private final RandomAccessFile file;
        private final long[] offsets;
        private final int[] sizes;
        Reader(File target) throws IOException {
            if (!regular(target) || target.length() < 20 || target.length() > MAX_VIDEO)
                throw new IOException("Title animation cache is unavailable");
            RandomAccessFile input = new RandomAccessFile(target, "r");
            try {
                if (input.readInt() != MAGIC) throw new IOException("Unknown title cache");
                width = input.readInt(); height = input.readInt(); fps = input.readInt();
                dimensions(width, height, fps);
                count = input.readInt();
                if (count < MIN_FRAMES || count > FRAMES)
                    throw new IOException("Incomplete title frame cache");
                offsets = new long[count]; sizes = new int[count];
                for (int i = 0; i < count; i++) {
                    int size = input.readInt();
                    long position = input.getFilePointer();
                    if (size < 4 || size > MAX_FRAME || size > input.length() - position)
                        throw new IOException("Invalid title frame length");
                    if (input.readUnsignedByte() != 255 || input.readUnsignedByte() != 216)
                        throw new IOException("Invalid title JPEG");
                    offsets[i] = position; sizes[i] = size;
                    input.seek(position + size);
                }
                if (input.getFilePointer() != input.length())
                    throw new IOException("Unexpected title cache bytes");
                file = input;
            } catch (IOException | RuntimeException error) {
                input.close(); throw error;
            }
        }
        byte[] read(int index) throws IOException {
            if (index < 0 || index >= count) throw new IOException("Invalid title frame index");
            byte[] bytes = new byte[sizes[index]];
            file.seek(offsets[index]); file.readFully(bytes); return bytes;
        }
        @Override public void close() throws IOException { file.close(); }
    }

    static boolean validAudio(File audio) {
        if (!regular(audio) || audio.length() < 44L + 48000L * 4 * 55 ||
            audio.length() > MAX_AUDIO) return false;
        try (DataInputStream input = new DataInputStream(new FileInputStream(audio))) {
            byte[] header = new byte[44]; input.readFully(header);
            ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            return new String(header,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") &&
                new String(header,8,8,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVEfmt ") &&
                new String(header,36,4,java.nio.charset.StandardCharsets.US_ASCII).equals("data") &&
                b.getInt(4) == audio.length() - 8 && b.getInt(16) == 16 &&
                b.getShort(20) == 1 && b.getShort(22) == 2 && b.getInt(24) == 48000 &&
                b.getInt(28) == 192000 && b.getShort(32) == 4 && b.getShort(34) == 16 &&
                b.getInt(40) == audio.length() - 44 && (b.getInt(40) & 3) == 0;
        } catch (IOException failure) { return false; }
    }

    static boolean ready(File root) {
        File dir = directory(root);
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !validAudio(new File(dir, "audio.wav"))) return false;
        try (Reader reader = new Reader(new File(dir, "frames.hbt"))) {
            return reader.count >= MIN_FRAMES;
        } catch (IOException | RuntimeException failure) { return false; }
    }

    static void commit(File root) throws IOException {
        File dir = pending(root), target = directory(root);
        safeDirectory(dir);
        if (!validAudio(new File(dir, "audio.wav"))) throw new IOException("Title audio not complete");
        try (Reader reader = new Reader(new File(dir, "frames.hbt"))) {
            if (reader.count < MIN_FRAMES) throw new IOException("Title animation not complete");
        }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Previous title cache kept");
        try {
            Files.move(dir.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(dir.toPath(), target.toPath());
        }
    }
}
