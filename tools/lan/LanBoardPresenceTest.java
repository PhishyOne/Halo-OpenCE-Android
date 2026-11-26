package com.halo.decomp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class LanBoardPresenceTest {
    private static int checks;
    static final String TOKEN = "0123456789abcdef";
    static final String ID = "0123456789abcdef0123456789abcdef";
    static final String OTHER = "fedcba9876543210fedcba9876543210";
    static final String IP = "192.168.43.2";

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    interface Attempt { void run() throws IOException; }
    private static void reject(Attempt task, String label) {
        checks++;
        try {
            task.run();
            throw new AssertionError("Accepted invalid " + label);
        } catch (IOException | IllegalArgumentException expected) { }
    }

    public static void main(String[] args) throws Exception {
        check(LanBoardPresence.readQuery(LanBoardPresence.query(TOKEN).trim()).equals(TOKEN),
            "presence query round-trip");
        check(LanBoardPresence.readQuery("HBP1|" + TOKEN + "|extra") == null, "extra query field");
        check(LanBoardPresence.readQuery("HBC2|" + TOKEN) == null, "old stats frame untouched");
        check(LanBoardPresence.readQuery("HBP1|invalid") == null, "invalid challenge");
        String wire = LanBoardPresence.answer(TOKEN, ID, "Spartan One", false).trim();
        LanBoardPresence.Peer one = LanBoardPresence.readAnswer(wire, TOKEN, IP);
        check(one.id.equals(ID) && one.name.equals("Spartan One") && one.ip.equals(IP),
            "exact name and identity");
        check(!one.gameOpen && one.location().equals("Launcher"), "launcher status");
        LanBoardPresence.Peer two = LanBoardPresence.readAnswer(
            LanBoardPresence.answer(TOKEN, OTHER, "Spartan One", true).trim(),
            TOKEN, "192.168.43.3");
        check(two.gameOpen && two.location().equals("Game open"), "game process status");
        reject(() -> LanBoardPresence.readAnswer(wire, "1111111111111111", IP), "wrong nonce");
        reject(() -> LanBoardPresence.readAnswer(wire, TOKEN, "8.8.8.8"), "public IP");
        reject(() -> LanBoardPresence.readAnswer(wire + "|stats", TOKEN, IP), "extra field");
        reject(() -> LanBoardPresence.readAnswer(wire.replace(ID, "../bad"), TOKEN, IP),
            "path-like ID");
        reject(() -> LanBoardPresence.readAnswer(wire.substring(0, wire.length()-1) + "X",
            TOKEN, IP), "unrecognized status");
        reject(() -> LanBoardPresence.answer(TOKEN, ID, "bad|name", true), "unsafe name");
        LanBoardPresence.Peer duplicate = LanBoardPresence.readAnswer(wire, TOKEN, "192.168.43.4");
        LanBoardPresence.Peer unnamed = LanBoardPresence.Peer.legacy("192.168.43.5");
        LanBoardPresence.Snapshot state = new LanBoardPresence.Snapshot(
            List.of(one, two, duplicate, unnamed), 10000, true);
        check(state.peers.size() == 3, "dedup by persistent ID, not same display name");
        check(state.ready() == 2 && !unnamed.named(), "anonymous discovery isn't sync-ready");
        check(state.fresh(10000) && state.fresh(25000), "fresh monotonic snapshot");
        check(!state.fresh(25001) && !state.fresh(9999), "stale/future snapshot rejected");
        check(state.targets(25001).isEmpty() && state.targets(10100).size() == 3,
            "sync cannot use expired cached presence");
        check(!LanBoardPresence.Snapshot.empty().fresh(0), "before first scan not online");
        java.util.List<LanBoardPresence.Peer> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++)
            many.add(LanBoardPresence.Peer.legacy("192.168.43." + i));
        check(new LanBoardPresence.Snapshot(many, 1, true).peers.size() == 16, "peer count cap");

        // Use real loopback TCP for the framing exchange. Android's
        // production scan separately requires a same-private-LAN binding.
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            FutureTask<Boolean> responder = new FutureTask<>(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(2000);
                    String received = LanBoardProtocol.boundedLine(peer.getInputStream());
                    String nonce = LanBoardPresence.readQuery(received);
                    if (!TOKEN.equals(nonce)) return false;
                    peer.getOutputStream().write(LanBoardPresence.answer(nonce, ID,
                        "Named Spartan", true).getBytes(StandardCharsets.US_ASCII));
                    peer.getOutputStream().flush();
                    return true;
                }
            });
            Thread thread = new Thread(responder);
            thread.setDaemon(true);
            thread.start();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                LanBoardPresence.Peer remote = LanBoardPresence.exchange(client, TOKEN, IP);
                check(remote.name.equals("Named Spartan") && remote.gameOpen,
                    "actual socket exchange uses production framing");
            }
            check(responder.get(3, TimeUnit.SECONDS), "no stats or identity in request");
        }
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            FutureTask<Void> responder = new FutureTask<>(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(2000);
                    LanBoardProtocol.boundedLine(peer.getInputStream());
                    peer.getOutputStream().write("HPA1|".getBytes(StandardCharsets.US_ASCII));
                    peer.getOutputStream().flush();
                    Thread.sleep(1200);
                    return null;
                }
            });
            Thread thread = new Thread(responder);
            thread.setDaemon(true);
            thread.start();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                reject(() -> LanBoardPresence.exchange(client, TOKEN, IP), "incomplete slow reply");
            }
            responder.get(3, TimeUnit.SECONDS);
        }
        System.out.println("PASS LanBoardPresenceTest: " + checks + " checks");
    }
}
