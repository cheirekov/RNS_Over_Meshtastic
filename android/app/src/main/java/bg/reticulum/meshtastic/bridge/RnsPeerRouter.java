package bg.reticulum.meshtastic.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded MAC-learning-style router between RNS hashes and Meshtastic Node IDs. */
final class RnsPeerRouter {
    static final String BROADCAST = "^all";
    private static final int MAX_PEERS = 32;
    private static final int MAX_ROUTES = 512;
    private static final long ROUTE_TTL_MILLIS = 24L * 60 * 60 * 1000;

    static final class Decision {
        final String destination;
        final String reason;

        Decision(String destination, String reason) {
            this.destination = destination;
            this.reason = reason;
        }

        boolean isBroadcast() { return BROADCAST.equals(destination); }
    }

    static final class Snapshot {
        final int peers;
        final int routes;
        final long learnedRoutes;
        final long routeConflicts;
        final long unknownBroadcasts;
        final long opaqueIfacBroadcasts;
        final long peerLimitRejects;
        final String peerSummary;

        Snapshot(
                int peers, int routes, long learnedRoutes, long routeConflicts,
                long unknownBroadcasts, long opaqueIfacBroadcasts,
                long peerLimitRejects, String peerSummary) {
            this.peers = peers;
            this.routes = routes;
            this.learnedRoutes = learnedRoutes;
            this.routeConflicts = routeConflicts;
            this.unknownBroadcasts = unknownBroadcasts;
            this.opaqueIfacBroadcasts = opaqueIfacBroadcasts;
            this.peerLimitRejects = peerLimitRejects;
            this.peerSummary = peerSummary;
        }
    }

    private static final class Route {
        final String node;
        long seenAt;

        Route(String node, long seenAt) {
            this.node = node;
            this.seenAt = seenAt;
        }
    }

    private static final class Peer {
        long seenAt;
        long frames;

        Peer(long seenAt) { this.seenAt = seenAt; }
    }

    private final LongSupplier clock;
    private final LinkedHashMap<String, Route> routes = new LinkedHashMap<>(32, 0.75f, true);
    private final LinkedHashMap<String, Peer> peers = new LinkedHashMap<>(8, 0.75f, true);
    private long learnedRoutes;
    private long routeConflicts;
    private long unknownBroadcasts;
    private long opaqueIfacBroadcasts;
    private long peerLimitRejects;

    RnsPeerRouter() { this(System::currentTimeMillis); }

    RnsPeerRouter(LongSupplier clock) { this.clock = clock; }

    synchronized void observeInbound(String source, byte[] frame) {
        long now = clock.getAsLong();
        prune(now);
        String node = NodeId.format(NodeId.parse(source));
        Peer peer = peers.get(node);
        if (peer == null) {
            if (peers.size() >= MAX_PEERS) {
                peerLimitRejects++;
                return;
            }
            peer = new Peer(now);
            peers.put(node, peer);
        }
        peer.seenAt = now;
        peer.frames++;

        RnsPacketMetadata metadata = RnsPacketMetadata.parse(frame);
        if (metadata == null) return;

        // Every received packet can generate an explicit proof addressed to
        // the truncated packet hash. Remembering this reverse key is what
        // keeps delivery proofs peer-specific without inspecting payloads.
        learn(metadata.packetHash, node, now);
        if (metadata.packetType == RnsPacketMetadata.ANNOUNCE) {
            learn(metadata.destinationHash, node, now);
        }
        if (metadata.packetType == RnsPacketMetadata.LINK_REQUEST && metadata.linkId != null) {
            learn(metadata.linkId, node, now);
        }
        if (metadata.destinationType == RnsPacketMetadata.LINK) {
            learn(metadata.destinationHash, node, now);
        }
    }

    synchronized Decision destinationFor(byte[] frame) {
        long now = clock.getAsLong();
        prune(now);
        RnsPacketMetadata metadata = RnsPacketMetadata.parse(frame);
        if (metadata == null) {
            if (RnsPacketMetadata.isOpaqueIfac(frame)) opaqueIfacBroadcasts++;
            else unknownBroadcasts++;
            return new Decision(BROADCAST,
                    RnsPacketMetadata.isOpaqueIfac(frame) ? "opaque IFAC" : "malformed/unknown RNS");
        }
        if (metadata.packetType == RnsPacketMetadata.ANNOUNCE) {
            return new Decision(BROADCAST, "announce discovery");
        }
        if (metadata.destinationType == RnsPacketMetadata.PLAIN
                || metadata.destinationType == RnsPacketMetadata.GROUP) {
            return new Decision(BROADCAST, "RNS plain/group");
        }
        Route route = routes.get(metadata.destinationHash);
        if (route != null) {
            route.seenAt = now;
            return new Decision(route.node, "learned RNS route");
        }
        unknownBroadcasts++;
        return new Decision(BROADCAST, "unknown destination");
    }

    synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        prune(now);
        Map<String, Integer> routeCounts = new LinkedHashMap<>();
        for (Route route : routes.values()) {
            routeCounts.put(route.node, routeCounts.getOrDefault(route.node, 0) + 1);
        }
        List<Map.Entry<String, Peer>> entries = new ArrayList<>(peers.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Peer> entry) -> entry.getValue().seenAt).reversed());
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Peer> entry : entries) {
            long ageSeconds = Math.max(0, now - entry.getValue().seenAt) / 1000;
            labels.add(entry.getKey() + " (" + routeCounts.getOrDefault(entry.getKey(), 0)
                    + " routes, seen " + ageSeconds + "s ago)");
        }
        return new Snapshot(
                peers.size(), routes.size(), learnedRoutes, routeConflicts,
                unknownBroadcasts, opaqueIfacBroadcasts, peerLimitRejects,
                labels.isEmpty() ? "none" : String.join(", ", labels));
    }

    private void learn(String key, String node, long now) {
        Route previous = routes.get(key);
        if (previous != null) {
            if (!previous.node.equals(node)) routeConflicts++;
            if (previous.node.equals(node)) {
                previous.seenAt = now;
                return;
            }
        }
        routes.put(key, new Route(node, now));
        learnedRoutes++;
        while (routes.size() > MAX_ROUTES) {
            Iterator<String> oldest = routes.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    private void prune(long now) {
        routes.entrySet().removeIf(entry -> now - entry.getValue().seenAt > ROUTE_TTL_MILLIS);
        peers.entrySet().removeIf(entry -> now - entry.getValue().seenAt > ROUTE_TTL_MILLIS);
    }
}
