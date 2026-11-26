package com.halo.decomp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure scheduling policy: immediately sync newly seen peers; retry
 * reachable peers at most once per 30 seconds. Bounded, no disk writes. */
final class LanBoardAutoSync {
    static final long INTERVAL_MS = 30000;
    static final int BATCH = 4;
    private final Map<String, Long> attempted = new LinkedHashMap<>();
    private final Map<String, String> addresses = new LinkedHashMap<>();
    List<LanBoardPresence.Peer> due(LanBoardPresence.Snapshot snapshot, long now) {
        List<LanBoardPresence.Peer> result = new ArrayList<>();
        if (!snapshot.fresh(now)) return result;
        Map<String, String> current = new LinkedHashMap<>();
        for (LanBoardPresence.Peer peer : snapshot.peers) {
            if (!peer.named()) continue;
            current.put(peer.id, peer.ip);
            Long last = attempted.get(peer.id);
            if (result.size() < BATCH && (last == null || now < last ||
                now - last >= INTERVAL_MS || !peer.ip.equals(addresses.get(peer.id)))) {
                attempted.put(peer.id, now);
                addresses.put(peer.id, peer.ip);
                result.add(peer);
            }
        }
        attempted.keySet().retainAll(current.keySet());
        addresses.keySet().retainAll(current.keySet());
        return result;
    }
    void reset() { attempted.clear(); addresses.clear(); }
}
