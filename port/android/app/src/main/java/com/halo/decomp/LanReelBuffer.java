package com.halo.decomp;

import java.io.IOException;

/** Seekable single-block memory cache for the media decoder. */
final class LanReelBuffer {
    interface Fetch { byte[] read(long offset, int length) throws IOException; }
    private final long length;
    private final Fetch fetch;
    private long cachedAt = -1;
    private byte[] cache = new byte[0];

    LanReelBuffer(long length, Fetch fetch) {
        if (length < 2048 || length > LanReelProtocol.MAX_REEL || fetch == null)
            throw new IllegalArgumentException("Invalid remote reel");
        this.length = length; this.fetch = fetch;
    }

    synchronized int read(long position, byte[] target, int offset, int size) throws IOException {
        if (position < 0 || offset < 0 || size < 0 || target == null ||
            offset > target.length || size > target.length - offset)
            throw new IOException("Invalid media decoder request");
        if (size == 0) return 0;
        if (position >= length) return -1;
        int wanted = (int)Math.min(size, length - position), copied = 0;
        while (copied < wanted) {
            long at = position + copied;
            if (at < cachedAt || at >= cachedAt + cache.length) {
                long start = (at / LanReelProtocol.CHUNK) * LanReelProtocol.CHUNK;
                int count = (int)Math.min(LanReelProtocol.CHUNK, length - start);
                byte[] next = fetch.read(start, count);
                if (next == null || next.length != count)
                    throw new IOException("Incomplete highlight block");
                cache = next; cachedAt = start;
            }
            int index = (int)(at - cachedAt);
            int count = Math.min(wanted - copied, cache.length - index);
            System.arraycopy(cache, index, target, offset + copied, count);
            copied += count;
        }
        return copied;
    }
}
