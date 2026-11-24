package com.halo.decomp;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

/** Opt-in UDP responder for the local leaderboard, independent of Halo
 * multiplayer and host/guest roles. No stats are sent in discovery. */
final class LanBoardBeacon {
    private static final String TAG = "HaloLanBoard";
    private volatile DatagramSocket listener;
    private volatile boolean closed;

    static LanBoardBeacon start() {
        LanBoardBeacon responder = new LanBoardBeacon();
        Thread worker = new Thread(responder::serve, "Halo-LAN-board-discovery");
        worker.setDaemon(true);
        worker.start();
        return responder;
    }

    void stop() {
        closed = true;
        DatagramSocket socket = listener;
        if (socket != null) socket.close();
    }

    private void serve() {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(LanBoardDiscovery.PORT));
            socket.setSoTimeout(900);
            listener = socket;
            byte[] bytes = new byte[LanBoardDiscovery.MAX_PACKET+1];
            while (!closed) {
                DatagramPacket incoming = new DatagramPacket(bytes,bytes.length);
                try {
                    socket.receive(incoming);
                } catch (SocketTimeoutException idle) {
                    continue;
                }
                if (!(incoming.getAddress() instanceof Inet4Address)) continue;
                byte[] peer = incoming.getAddress().getAddress();
                String nonce = LanBoardDiscovery.readProbe(
                    incoming.getData(),incoming.getLength());
                if (nonce == null ||
                    LanUpdateProtocol.privateAddress(
                        incoming.getAddress().getHostAddress()) == null) continue;
                Inet4Address source = validReplyAddress(peer);
                if (source == null) continue;
                byte[] answer = LanBoardDiscovery.reply(nonce);
                try (DatagramSocket outgoing = new DatagramSocket(
                    new InetSocketAddress(source,0))) {
                    outgoing.send(new DatagramPacket(answer,answer.length,
                        incoming.getAddress(),incoming.getPort()));
                } catch (IOException error) {
                    if (!closed) Log.i(TAG,"UDP board reply unavailable");
                }
            }
        } catch (IOException error) {
            if (!closed) Log.i(TAG,"LAN board discovery disabled: " +
                error.getClass().getSimpleName());
        } finally {
            listener = null;
        }
    }

    private static Inet4Address validReplyAddress(byte[] peer) {
        try {
            Enumeration<NetworkInterface> nets =
                NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface nic = nets.nextElement();
                if (!nic.isUp() || nic.isLoopback() ||
                    !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address ip = (Inet4Address)ia.getAddress();
                    int prefix = LanUpdateProtocol.usableHostPrefix(
                        nic.getName(),ia.getNetworkPrefixLength());
                    if (prefix >= 0 &&
                        LanUpdateProtocol.sameSubnet(ip.getAddress(),peer,prefix))
                        return ip;
                }
            }
        } catch (SocketException ignored) { }
        return null;
    }
}
