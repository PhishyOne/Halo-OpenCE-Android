package com.halo.decomp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Versioned, bounded map-only protocol. No URLs or peer-supplied filesystem paths. */
final class LanMapTransfer {
    static final int PORT = 5164, MAGIC = 0x484d5031, CHUNK = 4 * 1024 * 1024;
    static final long MAX_MAP = 256L * 1024 * 1024;
    static final int LIST = 1, GET = 2, OK = 0, CHANGED = 1, DENIED = 2;
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
        java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1,
            task -> { Thread thread = new Thread(task, "Halo-map-timeouts"); thread.setDaemon(true); return thread; });
        timer.setRemoveOnCancelPolicy(true); return timer;
    }
    static final class DeadlineSocket extends Socket {
        private final java.util.concurrent.ScheduledFuture<?> expiry;
        DeadlineSocket(int milliseconds) {
            expiry = DEADLINES.schedule(() -> { try { close(); } catch (IOException ignored) { } },
                milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        @Override public synchronized void close() throws IOException {
            if (expiry != null) expiry.cancel(false);
            super.close();
        }
    }

    interface Progress { void show(String text); }
    interface Stop { boolean stopped(); }
    interface Connect { Socket open() throws IOException; }
    static final class Offer {
        final int id;
        final long size;
        final byte[] digest;
        Offer(int id, long size, byte[] digest) throws IOException {
            name(id);
            if (size < 2050 || size > MAX_MAP || digest == null || digest.length != 32)
                throw new IOException("Invalid host map size or checksum");
            this.id = id; this.size = size; this.digest = digest.clone();
        }
        String key() { return id + ":" + size + ":" + hex(digest); }
    }
    static final class Source {
        final Offer offer;
        final File file;
        final long modified;
        Source(Offer offer, File file) { this.offer = offer; this.file = file; modified = file.lastModified(); }
    }
    static String name(int id) throws IOException {
        if (id < 0 || id > SidewinderRepair.CAMPAIGN_MAP_FILES.length)
            throw new IOException("Unknown original Xbox map");
        return id == SidewinderRepair.CAMPAIGN_MAP_FILES.length ? SidewinderRepair.SIDEWINDER_MAP :
            SidewinderRepair.CAMPAIGN_MAP_FILES[id];
    }
    static void canceled(Stop stop) throws IOException {
        if (stop.stopped() || Thread.currentThread().isInterrupted())
            throw new IOException("Paused. Choose Repair from host again to resume.");
    }
    static byte[] hash(File file, Stop stop) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) { canceled(stop); digest.update(b, 0, n); }
        }
        return digest.digest();
    }
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    static List<Source> catalog(File root, Progress progress, Stop stop) throws IOException {
        File maps = new File(root, "maps");
        if (!maps.isDirectory() || Files.isSymbolicLink(maps.toPath())) throw new IOException("Maps folder unavailable");
        List<Source> sources = new ArrayList<>();
        for (int id = 0; id <= SidewinderRepair.CAMPAIGN_MAP_FILES.length; id++) {
            canceled(stop);
            String name = name(id);
            progress.show("Checking shareable map " + (id + 1) + "/11: " + SidewinderRepair.titleFor(name));
            File file = new File(maps, name);
            try {
                long size = file.length(), modified = file.lastModified();
                SidewinderRepair.validate(file, name);
                Offer offer = new Offer(id, size, hash(file, stop));
                if (size != file.length() || modified != file.lastModified()) throw new IOException("Map changed while checking");
                sources.add(new Source(offer, file));
            } catch (IOException bad) { canceled(stop); /* never offer failed or missing maps */ }
        }
        return Collections.unmodifiableList(sources);
    }
    static Connect connect(String ip) throws IOException {
        byte[] address = LanUpdateProtocol.privateAddress(ip);
        if (address == null) throw new IOException("Use a private hotspot or Wi-Fi host address");
        InetAddress target = InetAddress.getByAddress(address); // never resolve a hostname
        return () -> {
            Socket s = new DeadlineSocket(30000);
            try {
                InetAddress local = null;
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements() && local == null) {
                    NetworkInterface nic = interfaces.nextElement();
                    if (!nic.isUp() || nic.isLoopback() || !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                    for (InterfaceAddress item : nic.getInterfaceAddresses()) {
                        int prefix = LanUpdateProtocol.usableHostPrefix(nic.getName(), item.getNetworkPrefixLength());
                        if (item.getAddress() instanceof Inet4Address &&
                            LanUpdateProtocol.sameSubnet(item.getAddress().getAddress(), address, prefix)) {
                            local = item.getAddress(); break;
                        }
                    }
                }
                if (local == null) throw new IOException("Connect to the host's hotspot or the same Wi-Fi first");
                s.bind(new InetSocketAddress(local, 0));
                s.connect(new InetSocketAddress(target, PORT), 2500); s.setSoTimeout(12000); return s;
            }
            catch (IOException e) { s.close(); throw e; }
        };
    }
    private static void response(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Host does not support Halo map repair v1");
        int status = in.readUnsignedByte();
        if (status != OK) throw new IOException(status == CHANGED ?
            "Host map changed. Have the host stop and restart sharing, then retry." : "Host refused map repair");
    }
    static List<Offer> list(Connect connection) throws IOException {
        try (Socket s = connection.open()) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(MAGIC); out.writeByte(LIST); out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream()); response(in);
            int count = in.readUnsignedByte();
            if (count > 11) throw new IOException("Too many host maps");
            Set<Integer> seen = new HashSet<>(); List<Offer> offers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int id = in.readUnsignedByte(); long size = in.readLong(); byte[] digest = new byte[32]; in.readFully(digest);
                if (!seen.add(id)) throw new IOException("Duplicate host map");
                offers.add(new Offer(id, size, digest));
            }
            return offers;
        }
    }
    static boolean healthy(File root, Offer offer) {
        try { SidewinderRepair.validate(new File(new File(root, "maps"), name(offer.id)), name(offer.id)); return true; }
        catch (IOException bad) { return false; }
    }
    private static void safe(File f, boolean directory) throws IOException {
        if (Files.isSymbolicLink(f.toPath()) || (f.exists() && (directory ? !f.isDirectory() : !f.isFile())))
            throw new IOException("Unsafe map download storage");
    }
    /** One persistent partial, bound to exact map/size/SHA. A different host IP can resume the same bytes. */
    static File download(Connect connection, Offer offer, File root, Progress progress, Stop stop) throws IOException {
        File dir = new File(root, "lan-map-download"); safe(dir, true);
        if (!dir.isDirectory() && !dir.mkdir()) throw new IOException("Cannot create map download folder");
        File part = new File(dir, "current.part"), meta = new File(dir, "current.key");
        safe(part, false); safe(meta, false);
        String old = meta.isFile() && meta.length() <= 128 ? new String(Files.readAllBytes(meta.toPath()), StandardCharsets.US_ASCII) : "";
        if (!offer.key().equals(old) || part.length() > offer.size) {
            Files.deleteIfExists(part.toPath());
            try (FileOutputStream out = new FileOutputStream(meta)) {
                out.write(offer.key().getBytes(StandardCharsets.US_ASCII)); out.getFD().sync();
            }
        }
        long offset = part.length();
        if (root.getUsableSpace() < offer.size + (offer.size - offset) + 16L * 1024 * 1024)
            throw new IOException("Not enough free storage to download, verify and safely install this map");
        while (offset < offer.size) {
            canceled(stop);
            try (Socket s = connection.open()) {
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                int requested = (int)Math.min(CHUNK, offer.size - offset);
                out.writeInt(MAGIC); out.writeByte(GET); out.writeByte(offer.id); out.write(offer.digest);
                out.writeLong(offset); out.writeInt(requested); out.flush();
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
                response(in);
                long size = in.readLong(), start = in.readLong(); int length = in.readInt();
                byte[] hash = new byte[32]; in.readFully(hash);
                if (size != offer.size || start != offset || length != requested || !Arrays.equals(hash, offer.digest))
                    throw new IOException("Host sent an unexpected map range");
                try (FileOutputStream file = new FileOutputStream(part, true)) {
                    try {
                        byte[] b = new byte[65536]; int left = length;
                        while (left > 0) {
                            canceled(stop);
                            int n = in.read(b, 0, Math.min(b.length, left));
                            if (n < 0) throw new IOException("Connection lost. Choose Repair from host again to resume.");
                            if (n == 0) continue;
                            file.write(b, 0, n); offset += n; left -= n;
                            progress.show(SidewinderRepair.titleFor(name(offer.id)) + ": " +
                                (offset * 100 / offer.size) + "% (" + offset / 1048576 + "/" + offer.size / 1048576 + " MiB)");
                        }
                    } finally { file.getFD().sync(); }
                }
            }
        }
        canceled(stop);
        progress.show("Verifying " + SidewinderRepair.titleFor(name(offer.id)) + "...");
        if (!Arrays.equals(hash(part, stop), offer.digest)) {
            Files.deleteIfExists(part.toPath());
            throw new IOException("Map checksum failed. The damaged download was cleared; retry from your trusted host.");
        }
        try { SidewinderRepair.validate(part, name(offer.id)); }
        catch (IOException bad) { Files.deleteIfExists(part.toPath()); throw bad; }
        return part;
    }
    static String install(File downloaded, File root, Stop stop) throws IOException {
        canceled(stop);
        try (InputStream in = new FileInputStream(downloaded)) {
            String result = SidewinderRepair.installDetected(in, root);
            Files.delete(downloaded.toPath());
            return result;
        }
    }
    private static void status(DataOutputStream out, int code) throws IOException { out.writeInt(MAGIC); out.writeByte(code); }
    /** Socket caller supplies subnet admission and an absolute connection deadline. */
    static void serve(InputStream raw, OutputStream output, List<Source> sources) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output, 65536));
        if (in.readInt() != MAGIC) { status(out, DENIED); out.flush(); return; }
        int op = in.readUnsignedByte();
        if (op == LIST) {
            status(out, OK); out.writeByte(sources.size());
            for (Source source : sources) { out.writeByte(source.offer.id); out.writeLong(source.offer.size); out.write(source.offer.digest); }
        } else if (op == GET) {
            int id = in.readUnsignedByte(); byte[] digest = new byte[32]; in.readFully(digest);
            long offset = in.readLong(); int length = in.readInt(); Source found = null;
            for (Source source : sources) if (source.offer.id == id) found = source;
            if (found == null || offset < 0 || length < 1 || length > CHUNK || offset > found.offer.size - length ||
                !Arrays.equals(digest, found.offer.digest)) { status(out, DENIED); out.flush(); return; }
            File file = found.file;
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() != found.offer.size || file.lastModified() != found.modified) {
                status(out, CHANGED); out.flush(); return;
            }
            try (RandomAccessFile map = new RandomAccessFile(file, "r")) {
                if (map.length() != found.offer.size) { status(out, CHANGED); out.flush(); return; }
                map.seek(offset); status(out, OK); out.writeLong(found.offer.size); out.writeLong(offset);
                out.writeInt(length); out.write(found.offer.digest);
                byte[] b = new byte[65536]; int left = length;
                while (left > 0) { int n = map.read(b, 0, Math.min(b.length, left));
                    if (n < 0) throw new IOException("Host map changed during transfer");
                    out.write(b, 0, n); left -= n;
                }
            }
        } else status(out, DENIED);
        out.flush();
    }
    private LanMapTransfer() { }
}
