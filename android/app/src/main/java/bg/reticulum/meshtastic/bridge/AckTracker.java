package bg.reticulum.meshtastic.bridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Correlates optional Meshtastic routing ACK/NAK packets with bridge transmissions. */
final class AckTracker {
    static final long DEFAULT_TIMEOUT_MILLIS = 120_000;
    private static final int MAX_PENDING = 512;

    static final class Snapshot {
        final int pending;
        final long confirmed;
        final long failed;
        final long unknown;
        final String lastResult;

        Snapshot(int pending, long confirmed, long failed, long unknown, String lastResult) {
            this.pending = pending;
            this.confirmed = confirmed;
            this.failed = failed;
            this.unknown = unknown;
            this.lastResult = lastResult;
        }
    }

    private static final class Pending {
        final long sentAt;
        final String destination;

        Pending(long sentAt, String destination) {
            this.sentAt = sentAt;
            this.destination = destination;
        }
    }

    private final long timeoutMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<Long, Pending> pending = new LinkedHashMap<>();
    private long confirmed;
    private long failed;
    private long unknown;
    private String lastResult = "none";

    AckTracker() { this(DEFAULT_TIMEOUT_MILLIS, System::currentTimeMillis); }

    AckTracker(long timeoutMillis, LongSupplier clock) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("ACK timeout must be positive");
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
    }

    synchronized void sent(long packetId, String destination) {
        expireLocked();
        if (pending.size() >= MAX_PENDING) {
            Iterator<Map.Entry<Long, Pending>> iterator = pending.entrySet().iterator();
            if (iterator.hasNext()) {
                Map.Entry<Long, Pending> oldest = iterator.next();
                iterator.remove();
                unknown++;
                lastResult = "confirmation unknown for " + oldest.getValue().destination + " (tracker capacity)";
            }
        }
        pending.put(packetId & 0xffffffffL, new Pending(clock.getAsLong(), destination));
    }

    synchronized boolean response(long requestId, int error) {
        expireLocked();
        Pending matched = pending.remove(requestId & 0xffffffffL);
        if (matched == null) return false;
        if (error == 0) {
            confirmed++;
            lastResult = "radio ACK from " + matched.destination;
        } else {
            failed++;
            lastResult = "radio NAK from " + matched.destination + ": " + ProtoCodec.routingErrorName(error);
        }
        return true;
    }

    synchronized Snapshot snapshot() {
        expireLocked();
        return new Snapshot(pending.size(), confirmed, failed, unknown, lastResult);
    }

    synchronized long millisUntilNextExpiry() {
        expireLocked();
        if (pending.isEmpty()) return -1;
        Pending oldest = pending.entrySet().iterator().next().getValue();
        return Math.max(1, timeoutMillis - (clock.getAsLong() - oldest.sentAt));
    }

    synchronized void clearPending(String reason) {
        if (!pending.isEmpty()) {
            unknown += pending.size();
            pending.clear();
            lastResult = "confirmation unknown (" + reason + ")";
        }
    }

    private void expireLocked() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<Long, Pending>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Pending> item = iterator.next();
            if (now - item.getValue().sentAt < timeoutMillis) continue;
            iterator.remove();
            unknown++;
            lastResult = "confirmation timed out for " + item.getValue().destination + " (delivery unknown)";
        }
    }
}
