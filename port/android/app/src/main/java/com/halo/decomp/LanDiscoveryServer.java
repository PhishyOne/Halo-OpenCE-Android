package com.halo.decomp;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

/** Answers a tiny private-subnet UDP probe while the host is in Halo.
 * The game host or an explicit launcher map-sharing session starts it; it serves no files.
 * Wildcard receive is needed for broadcasts, but outgoing replies are
 * restricted to the IP and prefix of an active Wi-Fi/hotspot interface. */
final class LanDiscoveryServer {
    private static final String LOG = "HaloLanDiscovery";
    private volatile boolean closed;
    private volatile DatagramSocket listener;

    static LanDiscoveryServer start() { return start(LanDiscoveryProtocol.PORT); }
    static LanDiscoveryServer start(int port) {
        if(port!=LanDiscoveryProtocol.PORT&&port!=LanLobbyTransport.PORT)throw new IllegalArgumentException("Invalid discovery port");
        LanDiscoveryServer server = new LanDiscoveryServer();
        Thread thread = new Thread(() -> server.serve(port), "Halo-LAN-host-discovery");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    void stop() {
        closed = true;
        DatagramSocket current = listener;
        if (current != null) current.close();
    }

    private void serve(int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(1000);
            listener = socket;
            if (closed) return;
            byte[] buf = new byte[LanDiscoveryProtocol.MAX_PACKET + 1];
            while (!closed) {
                DatagramPacket incoming = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(incoming);
                } catch (SocketTimeoutException idle) {
                    continue;
                }
                if (!(incoming.getAddress() instanceof Inet4Address)) continue;
                String nonce = LanDiscoveryProtocol.readProbe(
                    incoming.getData(), incoming.getLength());
                if (nonce == null) continue;
                Inet4Address peer = (Inet4Address)incoming.getAddress();
                if (LanUpdateProtocol.privateAddress(peer.getHostAddress()) == null)
                    continue;
                Inet4Address local = replyInterface(peer.getAddress());
                if (local == null) continue;
                byte[] response = LanDiscoveryProtocol.reply(nonce);
                // Bind the reply to the eligible private local interface, not
                // a carrier address. Do not reflect arbitrary payload bytes.
                try (DatagramSocket outgoing = new DatagramSocket(
                        new InetSocketAddress(local, 0))) {
                    outgoing.send(new DatagramPacket(response, response.length,
                        peer, incoming.getPort()));
                } catch (IOException unavailable) {
                    Log.i(LOG, "LAN probe reply unavailable: " +
                        unavailable.getClass().getSimpleName());
                }
            }
        } catch (IOException problem) {
            if (!closed) Log.w(LOG, "LAN discovery unavailable", problem);
        } finally {
            listener = null;
        }
    }

    private static Inet4Address replyInterface(byte[] peer) {
        try {
            Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() ||
                    !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                for (InterfaceAddress item : nic.getInterfaceAddresses()) {
                    if (!(item.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address local = (Inet4Address)item.getAddress();
                    int prefix = LanUpdateProtocol.usableHostPrefix(
                        nic.getName(), item.getNetworkPrefixLength());
                    if (prefix < 0 ||
                        LanUpdateProtocol.privateAddress(local.getHostAddress()) == null)
                        continue;
                    if (LanDiscoveryProtocol.sameLan(local.getAddress(), peer, prefix))
                        return local;
                }
            }
        } catch (SocketException unavailable) {
            Log.i(LOG, "Can't enumerate hotspot addresses");
        }
        return null;
    }
}
