package com.halo.decomp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Minimal HTTP/1.0 over private IPv4 sockets. Explicit sockets avoid
 * globally weakening Android's cleartext-traffic policy. No DNS, redirects,
 * caching, chunked encoding, proxy, cookie or arbitrary URL support. */
final class LanUpdateSocket {
    interface Progress { void progress(long transferred, long total); }

    static final class Response implements Closeable {
        final Socket socket;
        final InputStream body;
        final long length;
        Response(Socket socket, InputStream body, long length) {
            this.socket = socket;
            this.body = body;
            this.length = length;
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private LanUpdateSocket() { }

    /** Reject long lines and non-ASCII bytes. HTTP headers must be CRLF,
     * not arbitrary streams which can keep a socket open indefinitely. */
    static String line(InputStream in, int limit) throws IOException {
        byte[] raw = new byte[limit];
        int n = 0;
        for (;;) {
            int c = in.read();
            if (c < 0) throw new IOException("Truncated Halo update HTTP header");
            if (c == '\n') {
                if (n == 0 || raw[n - 1] != '\r')
                    throw new IOException("Invalid Halo update HTTP line ending");
                return new String(raw, 0, n - 1, StandardCharsets.US_ASCII);
            }
            if (n >= limit || (c < 32 && c != '\r') || c > 126)
                throw new IOException("Invalid Halo update HTTP header");
            raw[n++] = (byte)c;
        }
    }

    static Response request(String host, String path) throws IOException {
        if (LanUpdateProtocol.privateAddress(host) == null ||
            !(path.equals(LanUpdateProtocol.META_PATH) ||
              path.equals(LanUpdateProtocol.APK_PATH)))
            throw new IOException("Only a private LAN host and fixed update endpoints are allowed");

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, LanUpdateProtocol.PORT), 1500);
            socket.setSoTimeout(6500);
            OutputStream writer = socket.getOutputStream();
            writer.write(("GET " + path + " HTTP/1.0\r\nHost: " + host +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            writer.flush();

            InputStream input = new BufferedInputStream(socket.getInputStream(), 65536);
            if (!"HTTP/1.0 200 OK".equals(line(input, 128)))
                throw new IOException("Halo update host refused the request");
            long length = -1;
            int lines = 0;
            for (;;) {
                String header = line(input, 512);
                if (header.isEmpty()) break;
                if (++lines > 16) throw new IOException("Too many update headers");
                int colon = header.indexOf(':');
                if (colon < 1) throw new IOException("Malformed update header");
                String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim();
                if (name.equals("transfer-encoding") ||
                    (name.equals("content-encoding") && !value.equalsIgnoreCase("identity")))
                    throw new IOException("Encoded update bodies are unsupported");
                if (name.equals("content-length")) {
                    if (length >= 0 || !value.matches("[0-9]{1,12}"))
                        throw new IOException("Invalid update Content-Length");
                    try { length = Long.parseLong(value); }
                    catch (NumberFormatException invalid) {
                        throw new IOException("Invalid update Content-Length", invalid);
                    }
                }
            }
            if (length < 1 || length > LanUpdateProtocol.MAX_APK_BYTES)
                throw new IOException("Missing or oversized Halo update response");
            return new Response(socket, input, length);
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    static LanUpdateProtocol.Manifest fetchManifest(String host) throws IOException {
        try (Response response = request(host, LanUpdateProtocol.META_PATH)) {
            if (response.length > LanUpdateProtocol.MAX_MANIFEST_BYTES)
                throw new IOException("Host manifest is unexpectedly large");
            byte[] data = new byte[(int)response.length];
            readFully(response.body, data);
            return LanUpdateProtocol.parse(new String(data, StandardCharsets.US_ASCII));
        }
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) throw new IOException("Truncated Halo update response");
            if (count > 0) offset += count;
        }
    }

    /** Download only a manifest-declared, bounded APK; verify the exact
     * bytes against the offered digest before returning to Android code. */
    static void download(String host, LanUpdateProtocol.Manifest offer,
            File destination, Progress progress) throws IOException {
        if (offer.size < 1024 || offer.size > LanUpdateProtocol.MAX_APK_BYTES)
            throw new IOException("Invalid Halo APK size");
        try (Response response = request(host, LanUpdateProtocol.APK_PATH)) {
            transferVerified(response.body, response.length, offer, destination, progress);
        }
    }

    /** Pure streaming verifier extracted for fault injection in JVM tests.
     * The Android caller moves this file into UpdateProvider ONLY after
     * SHA and PackageManager signing checks. */
    static void transferVerified(InputStream body, long length,
            LanUpdateProtocol.Manifest offer, File destination, Progress progress)
            throws IOException {
        if (offer.size < 1024 || offer.size > LanUpdateProtocol.MAX_APK_BYTES ||
            length != offer.size)
            throw new IOException("Host APK size changed");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
        long remaining = length;
        long received = 0;
        byte[] buffer = new byte[65536];
        try (FileOutputStream file = new FileOutputStream(destination)) {
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted())
                    throw new IOException("Halo LAN update canceled");
                int n = body.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (n < 0)
                    throw new IOException("Halo LAN update interrupted");
                if (n == 0) continue;
                file.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                received += n;
                remaining -= n;
                if (progress != null && (received == length ||
                    received % (256 * 1024) < n))
                    progress.progress(received, length);
            }
            file.getFD().sync();
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte b : digest.digest())
            hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!offer.sha256.equals(hash.toString()))
            throw new IOException("Host APK failed SHA-256 verification");
    }

    private static void headers(OutputStream out, int code, String type, long size)
            throws IOException {
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" :
            code == 404 ? "Not Found" : "Bad Request";
        out.write(("HTTP/1.0 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "\r\n" +
            "Content-Length: " + size + "\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    }

    /** One bounded request, two fixed GET routes, no user-named files. The
     * caller bound the socket to a Wi-Fi/AP interface; check peer subnet too. */
    static void serve(Socket socket, File installedApk,
            LanUpdateProtocol.Manifest manifest, byte[] boundIp, int prefix)
            throws IOException {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            OutputStream output = new BufferedOutputStream(connection.getOutputStream(), 65536);
            byte[] peer = connection.getInetAddress().getAddress();
            if (!LanUpdateProtocol.sameSubnet(boundIp, peer, prefix)) {
                headers(output, 403, "text/plain", 0);
                output.flush();
                return;
            }
            serveHttp(connection.getInputStream(), output, installedApk, manifest);
        }
    }

    /** Stream helper, separate from the socket/subnet gate so pure JDK
     * tests can exercise the full response without binding a real port. */
    static void serveHttp(InputStream rawInput, OutputStream output,
            File installedApk, LanUpdateProtocol.Manifest manifest) throws IOException {
            InputStream input = new BufferedInputStream(rawInput, 1024);
            String request = line(input, 256);
            String endpoint = null;
            if (request.equals("GET " + LanUpdateProtocol.META_PATH + " HTTP/1.0") ||
                request.equals("GET " + LanUpdateProtocol.META_PATH + " HTTP/1.1"))
                endpoint = LanUpdateProtocol.META_PATH;
            if (request.equals("GET " + LanUpdateProtocol.APK_PATH + " HTTP/1.0") ||
                request.equals("GET " + LanUpdateProtocol.APK_PATH + " HTTP/1.1"))
                endpoint = LanUpdateProtocol.APK_PATH;
            int headerCount = 0;
            for (;;) {
                String header = line(input, 512);
                if (header.isEmpty()) break;
                if (++headerCount > 16)
                    throw new IOException("Excessive request headers");
            }
            if (endpoint == null) {
                headers(output, 404, "text/plain", 0);
                output.flush();
                return;
            }
            if (endpoint.equals(LanUpdateProtocol.META_PATH)) {
                byte[] body = LanUpdateProtocol.encode(manifest)
                    .getBytes(StandardCharsets.US_ASCII);
                headers(output, 200, "text/plain", body.length);
                output.write(body);
            } else {
                if (!installedApk.isFile() || installedApk.length() != manifest.size)
                    throw new IOException("Installed Halo APK changed while sharing");
                headers(output, 200, "application/vnd.android.package-archive",
                    manifest.size);
                try (FileInputStream apk = new FileInputStream(installedApk)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    long remaining = manifest.size;
                    while (remaining > 0 && (count = apk.read(buffer, 0,
                            (int)Math.min(buffer.length, remaining))) > 0) {
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                    if (remaining != 0)
                        throw new IOException("Installed Halo APK became unreadable");
                }
            }
            output.flush();
    }
}
