package com.halo.decomp;

import android.media.MediaDataSource;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/** Local, private-IP-only catalog and on-demand MP4 reads. Android's
 * media decoder sees a seekable source; no downloaded MP4 or disk cache. */
final class LanReelClient {
    private static final SecureRandom RNG = new SecureRandom();
    private LanReelClient() { }

    private static String nonce() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        StringBuilder text = new StringBuilder(16);
        for (byte b : bytes) text.append(Character.forDigit((b >> 4) & 15, 16))
            .append(Character.forDigit(b & 15, 16));
        return text.toString();
    }

    private static void connect(Socket socket, LanBoardPresence.Peer peer) throws IOException {
        if (peer == null || !peer.named() || !peer.reels)
            throw new IOException("This player is not sharing highlights");
        byte[] address = LanUpdateProtocol.privateAddress(peer.ip);
        Inet4Address local = address == null ? null : LanBoardClient.localInterface(address);
        if (local == null) throw new IOException("Player is no longer on your local Wi-Fi");
        socket.bind(new InetSocketAddress(local, 0));
        socket.connect(new InetSocketAddress(LanUpdateProtocol.asAddress(address),
            LanBoardProtocol.PORT), 1600);
    }

    private static InputStream response(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        final InputStream input = socket.getInputStream();
        final long end = System.nanoTime() + 5_000_000_000L;
        return new BufferedInputStream(new InputStream() {
            private void remaining() throws IOException {
                long left = end - System.nanoTime();
                if (left <= 0 || Thread.currentThread().isInterrupted())
                    throw new IOException("Highlight stream timed out");
                socket.setSoTimeout((int)Math.max(1, left / 1_000_000L));
            }
            @Override public int read() throws IOException {
                remaining(); return input.read();
            }
            @Override public int read(byte[] bytes, int offset, int size) throws IOException {
                remaining(); return input.read(bytes, offset, size);
            }
        }, 16 * 1024);
    }

    static List<LanReelProtocol.Reel> list(LanBoardPresence.Peer peer) throws IOException {
        try (Socket socket = new Socket()) {
            connect(socket, peer);
            String challenge = nonce();
            return LanReelProtocol.readCatalog(response(socket,
                LanReelProtocol.listRequest(challenge, peer.id)), challenge, peer.id);
        }
    }

    static final class Source extends MediaDataSource {
        private final LanBoardPresence.Peer peer;
        private final LanReelProtocol.Reel reel;
        private volatile boolean closed;
        private volatile Socket current;
        private final LanReelBuffer buffer;

        Source(LanBoardPresence.Peer peer, LanReelProtocol.Reel reel) {
            this.peer = peer; this.reel = reel;
            buffer = new LanReelBuffer(reel.size, this::fetch);
        }

        @Override public long getSize() { return reel.size; }

        @Override public int readAt(long position, byte[] target,
                                    int offset, int size) throws IOException {
            if (closed) throw new IOException("Highlight closed");
            int n = buffer.read(position, target, offset, size);
            if (closed) throw new IOException("Highlight closed");
            return n;
        }

        private byte[] fetch(long start, int length) throws IOException {
            try (Socket socket = new Socket()) {
                current = socket;
                if (closed) throw new IOException("Highlight closed");
                connect(socket, peer);
                String challenge = nonce();
                byte[] next = LanReelProtocol.readRange(response(socket,
                    LanReelProtocol.rangeRequest(challenge, peer.id, reel, start, length)),
                    challenge, length);
                if (closed) throw new IOException("Highlight closed");
                return next;
            } finally { current = null; }
        }

        @Override public void close() {
            // Do not wait for a synchronized read: close its socket to
            // unblock the decoder immediately if the viewer exits.
            closed = true;
            Socket socket = current;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
