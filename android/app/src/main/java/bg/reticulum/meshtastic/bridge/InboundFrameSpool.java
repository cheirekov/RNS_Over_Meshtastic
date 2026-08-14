package bg.reticulum.meshtastic.bridge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Small in-memory queue for completed inbound RNS frames while the loopback
 * Reticulum client is restarting. This is deliberately not an LXMF mailbox or
 * persistent store-and-forward service.
 */
final class InboundFrameSpool {
    enum OfferResult { QUEUED, DUPLICATE, REJECTED }

    static final class Snapshot {
        final int frames;
        final int bytes;
        final long queuedFrames;
        final long replayedFrames;
        final long duplicateFrames;
        final long expiredFrames;
        final long rejectedFrames;

        Snapshot(
                int frames, int bytes, long queuedFrames, long replayedFrames,
                long duplicateFrames, long expiredFrames, long rejectedFrames) {
            this.frames = frames;
            this.bytes = bytes;
            this.queuedFrames = queuedFrames;
            this.replayedFrames = replayedFrames;
            this.duplicateFrames = duplicateFrames;
            this.expiredFrames = expiredFrames;
            this.rejectedFrames = rejectedFrames;
        }
    }

    private static final class Entry {
        final byte[] frame;
        final String digest;
        final long createdAt;

        Entry(byte[] frame, String digest, long createdAt) {
            this.frame = frame;
            this.digest = digest;
            this.createdAt = createdAt;
        }
    }

    private final int maxFrames;
    private final int maxBytes;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Set<String> digests = new HashSet<>();
    private int bytes;
    private long queuedFrames;
    private long replayedFrames;
    private long duplicateFrames;
    private long expiredFrames;
    private long rejectedFrames;

    InboundFrameSpool(int maxFrames, int maxBytes, long ttlMillis) {
        this(maxFrames, maxBytes, ttlMillis, System::currentTimeMillis);
    }

    InboundFrameSpool(int maxFrames, int maxBytes, long ttlMillis, LongSupplier clock) {
        if (maxFrames < 1) throw new IllegalArgumentException("maxFrames must be positive");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        if (ttlMillis < 1) throw new IllegalArgumentException("ttlMillis must be positive");
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    synchronized OfferResult offer(byte[] frame) {
        if (frame == null || frame.length == 0) throw new IllegalArgumentException("RNS frame is empty");
        cleanupExpired();
        String digest = digest(frame);
        if (digests.contains(digest)) {
            duplicateFrames++;
            return OfferResult.DUPLICATE;
        }
        if (entries.size() >= maxFrames || frame.length > maxBytes - bytes) {
            rejectedFrames++;
            return OfferResult.REJECTED;
        }
        byte[] copy = Arrays.copyOf(frame, frame.length);
        entries.addLast(new Entry(copy, digest, clock.getAsLong()));
        digests.add(digest);
        bytes += copy.length;
        queuedFrames++;
        return OfferResult.QUEUED;
    }

    synchronized byte[] peek() {
        cleanupExpired();
        Entry entry = entries.peekFirst();
        return entry == null ? null : Arrays.copyOf(entry.frame, entry.frame.length);
    }

    synchronized void removeReplayed() {
        cleanupExpired();
        Entry entry = entries.pollFirst();
        if (entry == null) return;
        digests.remove(entry.digest);
        bytes -= entry.frame.length;
        replayedFrames++;
    }

    synchronized Snapshot snapshot() {
        cleanupExpired();
        return new Snapshot(
                entries.size(), bytes, queuedFrames, replayedFrames,
                duplicateFrames, expiredFrames, rejectedFrames);
    }

    private void cleanupExpired() {
        long cutoff = clock.getAsLong() - ttlMillis;
        while (!entries.isEmpty() && entries.peekFirst().createdAt < cutoff) {
            Entry expired = entries.removeFirst();
            digests.remove(expired.digest);
            bytes -= expired.frame.length;
            expiredFrames++;
        }
    }

    private static String digest(byte[] frame) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(frame);
            StringBuilder out = new StringBuilder(value.length * 2);
            for (byte item : value) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
