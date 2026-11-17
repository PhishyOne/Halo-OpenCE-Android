package com.halo.decomp;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** UDP LAN discovery. No subnet sweep or cloud service is required.
 * A response only suggests a local IP; the existing signed-APK updater
 * still verifies packages, and Halo does its normal multiplayer handshake. */
final class LanDiscoveryClient {
    interface Callback {
        void onResult(List<String> hosts);
    }

    private static final class LocalLink {
        final byte[] ip;
        final int prefix;
        LocalLink(byte[] ip, int prefix) { this.ip = ip; this.prefix = prefix; }
    }

    private LanDiscoveryClient() { }

    static void search(Activity activity, Callback result) {
        new Thread(() -> {
            List<String> found = scan(activity.getApplicationContext());
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed())
                    result.onResult(found);
            });
        }, "Halo-LAN-host-search").start();
    }

    private static List<LocalLink> localLinks() {
        List<LocalLink> links = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() ||
                    !LanUpdateProtocol.lanInterface(nic.getName())) continue;
                for (InterfaceAddress item : nic.getInterfaceAddresses()) {
                    InetAddress address = item.getAddress();
                    if (!(address instanceof Inet4Address) ||
                        LanUpdateProtocol.privateAddress(address.getHostAddress()) == null)
                        continue;
                    int prefix = LanUpdateProtocol.usableHostPrefix(
                        nic.getName(), item.getNetworkPrefixLength());
                    if (prefix >= 0) links.add(new LocalLink(address.getAddress(), prefix));
                }
            }
        } catch (Exception ignored) {
            // Manual IP remains available on restricted devices.
        }
        return links;
    }

    private static boolean isLocalPeer(byte[] peer, List<LocalLink> links) {
        for (LocalLink link : links) {
            if (LanDiscoveryProtocol.sameLan(link.ip, peer, link.prefix))
                return true;
        }
        return false;
    }

    private static List<String> targets(Context context, List<LocalLink> links) {
        Set<String> addresses = new LinkedHashSet<>();
        addresses.add("255.255.255.255"); // limited broadcast
        for (LocalLink link : links) {
            byte[] broadcast = LanDiscoveryProtocol.broadcast(link.ip, link.prefix);
            if (broadcast != null)
                addresses.add(LanUpdateProtocol.asAddress(broadcast));
        }
        // Some Android hotspots drop broadcast requests. The Wi-Fi default
        // gateway is normally the host phone: probe it directly as fallback.
        try {
            ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) {
                for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    if (caps == null ||
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    LinkProperties props = manager.getLinkProperties(network);
                    if (props == null) continue;
                    for (RouteInfo route : props.getRoutes()) {
                        InetAddress gateway = route.getGateway();
                        if (gateway instanceof Inet4Address &&
                            LanUpdateProtocol.privateAddress(gateway.getHostAddress()) != null &&
                            isLocalPeer(gateway.getAddress(), links))
                            addresses.add(gateway.getHostAddress());
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return new ArrayList<>(addresses);
    }

    static List<String> scan(Context context) {
        List<LocalLink> links = localLinks();
        if (links.isEmpty()) return Collections.emptyList();
        Set<String> results = new LinkedHashSet<>();
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("halo-lan-find");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException ignored) {
            lock = null;
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(160);
            byte[] nonceBytes = new byte[8];
            new SecureRandom().nextBytes(nonceBytes);
            StringBuilder nonce = new StringBuilder(16);
            for (byte b : nonceBytes)
                nonce.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            String challenge = nonce.toString();
            byte[] probe = LanDiscoveryProtocol.probe(challenge);
            List<String> destinations = targets(context, links);
            byte[] answer = new byte[LanDiscoveryProtocol.MAX_PACKET + 1];
            for (int round = 0; round < 3 && results.size() < 8; round++) {
                for (String address : destinations) {
                    try {
                        InetAddress target = InetAddress.getByName(address);
                        socket.send(new DatagramPacket(probe, probe.length,
                            target, LanDiscoveryProtocol.PORT));
                    } catch (Exception unavailable) { }
                }
                long deadline = android.os.SystemClock.elapsedRealtime() + 440;
                while (android.os.SystemClock.elapsedRealtime() < deadline &&
                       results.size() < 8) {
                    DatagramPacket packet = new DatagramPacket(answer, answer.length);
                    try {
                        socket.receive(packet);
                        InetAddress from = packet.getAddress();
                        if (!(from instanceof Inet4Address) ||
                            LanUpdateProtocol.privateAddress(from.getHostAddress()) == null ||
                            !isLocalPeer(from.getAddress(), links) ||
                            !LanDiscoveryProtocol.validReply(
                                packet.getData(), packet.getLength(), challenge))
                            continue;
                        results.add(from.getHostAddress());
                    } catch (SocketTimeoutException idle) {
                        // Wait for another response until the short round ends.
                    } catch (Exception unavailable) {
                        break;
                    }
                }
            }
        } catch (Exception unavailable) {
            // Fallback is explicit IP entry.
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
        List<String> sorted = new ArrayList<>(results);
        Collections.sort(sorted);
        return sorted;
    }
}
