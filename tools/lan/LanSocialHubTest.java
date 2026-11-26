package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class LanSocialHubTest {
    static final String TOKEN = "0123456789abcdef";
    static final String OWNER = "0123456789abcdef0123456789abcdef";
    static final String OTHER = "fedcba9876543210fedcba9876543210";
    static final String REEL = "11112222333344445555666677778888";
    static int checks;
    interface Attempt { void run() throws IOException; }
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    static void reject(Attempt task, String label) {
        checks++;
        try { task.run(); throw new AssertionError("Accepted invalid " + label); }
        catch (IOException | IllegalArgumentException expected) { }
    }
    static LanBoardPresence.Peer peer(String id, String ip, boolean videos) throws IOException {
        return LanBoardPresence.readAnswer(LanBoardPresence.answerModern(
            TOKEN, id, "Spartan", true, videos).trim(), TOKEN, ip);
    }
    static LanBoardPresence.Snapshot snapshot(long now, LanBoardPresence.Peer... peers) {
        return new LanBoardPresence.Snapshot(List.of(peers), now, true);
    }
    static byte[] serve(File root, String request, boolean enabled) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LanReelProtocol.serve(request.trim(), root, OWNER, enabled, bytes);
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        LanShareState defaults = LanShareState.defaults(true);
        check(defaults.career && defaults.reels, "stats and videos default on");
        LanShareState legacyOff = LanShareState.defaults(false);
        check(!legacyOff.career && !legacyOff.reels && legacyOff.videoAllowed,
            "preserve earlier career opt-out");
        LanShareState videosOff = defaults.withVideos(false);
        check(videosOff.career && !videosOff.reels, "video opt-out preserves stats");
        check(!LanShareState.decode(videosOff.encode()).reels &&
            LanShareState.decode(videosOff.encode()).career, "opt-out survives persistence");
        check(!videosOff.withCareer(false).withCareer(true).reels,
            "career toggle cannot re-enable opted-out video");
        check(videosOff.withVideos(true).reels, "explicitly re-enable video");
        check(legacyOff.withCareer(true).reels, "default video resumes with career");
        check(!LanShareState.decode("LS1|1|2\n").career &&
            !LanShareState.decode("LS1|1|").reels, "malformed setting fails closed");
        for (boolean career : new boolean[]{false, true}) {
            for (boolean video : new boolean[]{false, true}) {
                LanShareState state = LanShareState.decode(new LanShareState(career, video).encode());
                check(state.career == career && state.videoAllowed == video &&
                    state.reels == (career && video), "round trip both independent preferences");
            }
        }
        check(LanBoardPresence.readQuery(LanBoardPresence.queryModern(TOKEN).trim()).equals(TOKEN),
            "versioned discovery query");
        LanBoardPresence.Peer a = peer(OWNER, "192.168.43.2", true);
        LanBoardPresence.Peer b = peer(OTHER, "192.168.43.3", false);
        check(a.modern && a.reels && a.gameOpen, "video-sharing flag");
        check(b.modern && !b.reels, "explicit video off");
        LanBoardPresence.Peer legacy = LanBoardPresence.readAnswer(
            LanBoardPresence.answer(TOKEN, OWNER, "Old", false).trim(), TOKEN, a.ip);
        check(!legacy.modern && !legacy.reels, "old presence remains readable");
        reject(() -> LanBoardPresence.readAnswer(
            LanBoardPresence.answerModern(TOKEN, OWNER, "Spartan", true, true)
                .trim().replace("|G|1", "|G|2"), TOKEN, a.ip), "unknown video flag");

        LanBoardAutoSync auto = new LanBoardAutoSync();
        check(auto.due(snapshot(1000, a, b), 1000).size() == 2, "first sight immediate");
        check(auto.due(snapshot(5000, a, b), 5000).isEmpty(), "no traffic every presence ping");
        check(auto.due(snapshot(31000, a, b), 31000).size() == 2, "periodic automatic refresh");
        check(auto.due(snapshot(32000, a), 32000).isEmpty(), "disconnect no sync");
        check(auto.due(snapshot(33000, a, b), 33000).get(0).id.equals(OTHER), "reconnect immediate");
        LanBoardPresence.Peer moved = peer(OWNER, "192.168.43.9", true);
        check(auto.due(snapshot(34000, moved, b), 34000).get(0).ip.equals(moved.ip), "changed IP");
        check(auto.due(snapshot(34000, a, b), 50000).isEmpty(), "stale presence cannot sync");
        check(auto.due(snapshot(51000, LanBoardPresence.Peer.legacy("192.168.43.8")), 51000).isEmpty(),
            "anonymous discovery not automatic");
        auto.reset();
        List<LanBoardPresence.Peer> many = new ArrayList<>();
        for (int i = 1; i <= 10; i++)
            many.add(peer(String.format(java.util.Locale.US, "%032x", i),
                "192.168.43." + (10 + i), true));
        LanBoardPresence.Snapshot group = new LanBoardPresence.Snapshot(many, 1000, true);
        check(auto.due(group, 1000).size() == 4 && auto.due(group, 1001).size() == 4 &&
            auto.due(group, 1002).size() == 2, "bounded fair batches");
        check(auto.due(group, 1003).isEmpty(), "no repeated batch");

        Path directory = Files.createTempDirectory("halo-social-reels-");
        try {
            File root = directory.toFile();
            Path reels = Files.createDirectory(directory.resolve("reels"));
            byte[] data = new byte[LanReelProtocol.CHUNK * 2 + 731];
            for (int i = 0; i < data.length; i++) data[i] = (byte)(i * 31);
            Path good = Files.write(reels.resolve("reel-" + REEL + ".mp4"), data);
            Files.write(reels.resolve("session-1795000000000.mp4"), data);
            Files.write(reels.resolve("reel-" + OTHER + ".mp4.pending"), data);
            Files.write(reels.resolve("anything.mp4"), data);
            Files.write(reels.resolve("reel-" + OWNER + ".mp4"), new byte[10]);
            Path outside = Files.write(directory.resolve("secret.mp4"), data);
            String linkId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            Files.createSymbolicLink(reels.resolve("reel-" + linkId + ".mp4"), outside);
            List<LanReelProtocol.Reel> list = LanReelProtocol.readCatalog(
                new ByteArrayInputStream(serve(root, LanReelProtocol.listRequest(TOKEN, OWNER), true)),
                TOKEN, OWNER);
            check(list.size() == 1 && list.get(0).id.equals(REEL) &&
                list.get(0).size == data.length, "only valid completed highlight listed");
            LanReelProtocol.Reel reel = list.get(0);
            reject(() -> serve(root, LanReelProtocol.listRequest(TOKEN, OWNER), false), "sharing off");
            reject(() -> serve(root, LanReelProtocol.listRequest(TOKEN, OTHER), true), "wrong owner");
            reject(() -> serve(root, "HRQ1|" + TOKEN + "|" + OWNER +
                "|../secret.mp4|2048|1|0|10", true), "path traversal");
            reject(() -> serve(root, "HRQ1|" + TOKEN + "|" + OWNER +
                "|session-1795000000000.mp4|2048|1|0|10", true), "original recording");
            reject(() -> serve(root, LanReelProtocol.rangeRequest(TOKEN, OWNER,
                new LanReelProtocol.Reel(linkId, data.length, outside.toFile().lastModified()),
                0, 16), true), "symlink");
            reject(() -> LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, -1, 1), "negative offset");
            reject(() -> LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, 0,
                LanReelProtocol.CHUNK+1), "oversized block");
            reject(() -> LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, reel.size-1, 2), "past EOF");
            byte[] slice = LanReelProtocol.readRange(new ByteArrayInputStream(serve(root,
                LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, 123, 2048), true)), TOKEN, 2048);
            check(Arrays.equals(slice, Arrays.copyOfRange(data, 123, 2171)), "exact range");
            byte[] reply = serve(root, LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, 1, 128), true);
            reject(() -> LanReelProtocol.readRange(new ByteArrayInputStream(
                Arrays.copyOf(reply, reply.length - 1)), TOKEN, 128), "truncated response");
            reject(() -> LanReelProtocol.readRange(new ByteArrayInputStream(reply),
                "1111111111111111", 128), "wrong response nonce");

            int[] fetches = {0};
            LanReelBuffer buffer = new LanReelBuffer(reel.size, (offset, length) -> {
                fetches[0]++;
                return LanReelProtocol.readRange(new ByteArrayInputStream(serve(root,
                    LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, offset, length), true)),
                    TOKEN, length);
            });
            byte[] target = new byte[64];
            int boundary = LanReelProtocol.CHUNK - 16;
            check(buffer.read(boundary, target, 3, 40) == 40 &&
                Arrays.equals(Arrays.copyOfRange(target, 3, 43),
                    Arrays.copyOfRange(data, boundary, boundary + 40)) &&
                fetches[0] == 2, "media read crosses block boundary");
            check(buffer.read(boundary + 40, target, 0, 12) == 12 &&
                fetches[0] == 2, "reuse cached block");
            check(buffer.read(0, target, 0, 12) == 12 &&
                Arrays.equals(Arrays.copyOf(target, 12), Arrays.copyOf(data, 12)),
                "seek backwards");
            check(buffer.read(data.length - 7, target, 0, 64) == 7, "short final read");
            check(buffer.read(data.length, target, 0, 64) == -1, "EOF");
            reject(() -> buffer.read(0, target, 63, 2), "invalid decoder buffer");
            LanReelBuffer broken = new LanReelBuffer(reel.size, (offset,length) -> new byte[1]);
            reject(() -> broken.read(0, target, 0, 1), "failed block never becomes video");

            // Exercise actual wire framing and exact bytes over a real TCP socket.
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                FutureTask<Void> responder = new FutureTask<>(() -> {
                    try (Socket connection = listener.accept()) {
                        connection.setSoTimeout(3000);
                        LanReelProtocol.serve(LanBoardProtocol.boundedLine(connection.getInputStream()),
                            root, OWNER, true, connection.getOutputStream());
                    }
                    return null;
                });
                Thread worker = new Thread(responder); worker.setDaemon(true); worker.start();
                try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                    client.setSoTimeout(3000);
                    LanReelProtocol.write(client.getOutputStream(), LanReelProtocol.rangeRequest(
                        TOKEN, OWNER, reel, 1024, 16384));
                    byte[] received = LanReelProtocol.readRange(client.getInputStream(), TOKEN, 16384);
                    check(Arrays.equals(received, Arrays.copyOfRange(data, 1024, 17408)),
                        "actual TCP range transfer");
                }
                responder.get(3, TimeUnit.SECONDS);
            }
            Files.write(good, new byte[data.length + 1]);
            reject(() -> serve(root, LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, 0, 32),
                true), "changed reel");
            Files.delete(good);
            reject(() -> serve(root, LanReelProtocol.rangeRequest(TOKEN, OWNER, reel, 0, 32),
                true), "deleted reel");
            check(Files.exists(outside), "read-only source preserved");
        } finally {
            try (java.util.stream.Stream<Path> all = Files.walk(directory)) {
                for (Path item : (Iterable<Path>)all.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(item);
            }
        }
        System.out.println("PASS LanSocialHubTest: " + checks + " checks");
    }
}
