package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Two-byte legacy fragmentation compatible with landandair/RNS_Over_Meshtastic. */
final class FragmentProtocol {
    static final byte[] REQUEST_PREFIX = new byte[] {'R', 'E', 'Q'};

    static final class Transmission {
        final String destination;
        final byte[] payload;
        final String reason;
        Transmission(String destination, byte[] payload, String reason) {
            this.destination = destination;
            this.payload = payload;
            this.reason = reason;
        }
    }

    static final class Result {
        final List<byte[]> frames = new ArrayList<>();
        final List<Transmission> transmissions = new ArrayList<>();
    }

    static final class Snapshot {
        final int activeAssemblies;
        final int awaitingFinal;
        final int missingFragments;
        final long completedFrames;
        final long duplicateFrames;
        final long repairRequests;
        final long finalRepairRequests;
        final int cappedRepairs;
        final long retransmissions;
        final long expiredAssemblies;

        Snapshot(
                int activeAssemblies, int awaitingFinal, int missingFragments,
                long completedFrames, long duplicateFrames, long repairRequests, long finalRepairRequests,
                int cappedRepairs, long retransmissions, long expiredAssemblies) {
            this.activeAssemblies = activeAssemblies;
            this.awaitingFinal = awaitingFinal;
            this.missingFragments = missingFragments;
            this.completedFrames = completedFrames;
            this.duplicateFrames = duplicateFrames;
            this.repairRequests = repairRequests;
            this.finalRepairRequests = finalRepairRequests;
            this.cappedRepairs = cappedRepairs;
            this.retransmissions = retransmissions;
            this.expiredAssemblies = expiredAssemblies;
        }
    }

    private static final class Assembly {
        final String source;
        final int index;
        long updated;
        Integer last;
        final Map<Integer, byte[]> fragments = new HashMap<>();
        final Map<Integer, Long> requested = new HashMap<>();
        final Map<Integer, Integer> requestAttempts = new HashMap<>();

        Assembly(String source, int index) {
            this.source = source;
            this.index = index;
        }
    }

    private static final class CachedTx {
        final long created;
        final Map<Integer, byte[]> fragments;
        CachedTx(long created, Map<Integer, byte[]> fragments) {
            this.created = created;
            this.fragments = fragments;
        }
    }

    private final int bodySize;
    private final long ttlMillis;
    private final long requestCooldownMillis;
    private final LongSupplier clock;
    private static final int MAX_REPAIR_ATTEMPTS = 3;
    private int nextIndex;
    private final Map<String, Assembly> assemblies = new HashMap<>();
    private final LinkedHashMap<String, CachedTx> txCache = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> completed = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> completedIndices = new LinkedHashMap<>();
    private long completedFrames;
    private long duplicateFrames;
    private long repairRequests;
    private long finalRepairRequests;
    private long retransmissions;
    private long expiredAssemblies;

    FragmentProtocol(int bodySize) { this(bodySize, 180_000, 5_000, System::currentTimeMillis); }

    FragmentProtocol(int bodySize, long ttlMillis, long requestCooldownMillis) {
        this(bodySize, ttlMillis, requestCooldownMillis, System::currentTimeMillis);
    }

    FragmentProtocol(
            int bodySize, long ttlMillis, long requestCooldownMillis, LongSupplier clock) {
        if (bodySize < 1 || bodySize > 230) throw new IllegalArgumentException("fragment body must be 1..230 bytes");
        this.bodySize = bodySize;
        this.ttlMillis = ttlMillis;
        this.requestCooldownMillis = requestCooldownMillis;
        this.clock = clock;
    }

    synchronized List<Transmission> encode(byte[] frame, String destination) {
        if (frame.length == 0) throw new IllegalArgumentException("Reticulum frame is empty");
        int count = (frame.length + bodySize - 1) / bodySize;
        if (count > 127) throw new IllegalArgumentException("Reticulum frame needs more than 127 fragments");
        cleanup();
        int index = nextIndex++ & 0xff;
        Map<Integer, byte[]> cached = new HashMap<>();
        List<Transmission> result = new ArrayList<>();
        for (int position = 1; position <= count; position++) {
            int offset = (position - 1) * bodySize;
            int size = Math.min(bodySize, frame.length - offset);
            byte[] fragment = new byte[size + 2];
            fragment[0] = (byte) index;
            fragment[1] = (byte) (position == count ? -position : position);
            System.arraycopy(frame, offset, fragment, 2, size);
            cached.put(position, fragment);
            result.add(new Transmission(destination, fragment, "data"));
        }
        txCache.put(cacheKey(index, destination), new CachedTx(now(), cached));
        while (txCache.size() > 512) txCache.remove(txCache.keySet().iterator().next());
        return result;
    }

    synchronized Result receive(String source, byte[] payload) {
        cleanup();
        if (startsWith(payload, REQUEST_PREFIX)) return handleRequest(source, payload);
        if (payload.length < 3) throw new IllegalArgumentException("fragment is truncated or empty");
        int index = payload[0] & 0xff;
        int wirePosition = payload[1];
        if (wirePosition == 0 || wirePosition == -128) throw new IllegalArgumentException("invalid fragment position");
        int position = Math.abs(wirePosition);
        long now = now();
        String key = source + ":" + index;
        if (completedIndices.containsKey(key)) {
            duplicateFrames++;
            return new Result();
        }
        Assembly assembly = assemblies.computeIfAbsent(key, ignored -> new Assembly(source, index));
        byte[] body = Arrays.copyOfRange(payload, 2, payload.length);
        boolean madeProgress = !Arrays.equals(assembly.fragments.get(position), body)
                || (wirePosition < 0 && !Integer.valueOf(position).equals(assembly.last));
        if (madeProgress) {
            assembly.updated = now;
            assembly.requested.remove(position);
            assembly.requestAttempts.remove(position);
        }
        assembly.fragments.put(position, body);
        if (wirePosition < 0) {
            assembly.last = position;
            assembly.requested.remove(0);
            assembly.requestAttempts.remove(0);
        }

        Result result = new Result();
        if (assembly.last == null) return result;
        appendRepairRequests(result, assembly, missingPositions(assembly), now, 1, true);
        if (!result.transmissions.isEmpty()) return result;

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        for (int p = 1; p <= assembly.last; p++) {
            byte[] fragment = assembly.fragments.get(p);
            frame.write(fragment, 0, fragment.length);
        }
        byte[] complete = frame.toByteArray();
        String completeKey = key + ":" + digest(complete);
        assemblies.remove(key);
        if (!completed.containsKey(completeKey)) {
            result.frames.add(complete);
            completed.put(completeKey, now);
            completedIndices.put(key, now);
            completedFrames++;
        } else duplicateFrames++;
        return result;
    }

    synchronized Result pollRepairs(int maxRequests) {
        cleanup();
        long now = now();
        Result result = new Result();
        if (maxRequests <= 0) return result;
        for (Assembly assembly : assemblies.values()) {
            if (now - assembly.updated < requestCooldownMillis) continue;
            List<Integer> missing = assembly.last == null
                    ? Collections.singletonList(0) : missingPositions(assembly);
            appendRepairRequests(
                    result, assembly, missing, now,
                    maxRequests - result.transmissions.size(), false);
            if (result.transmissions.size() >= maxRequests) break;
        }
        return result;
    }

    private Result handleRequest(String source, byte[] payload) {
        Result result = new Result();
        if (payload.length < 5) return result;
        int index = payload[3] & 0xff;
        int position = Math.abs((int) payload[4]);
        CachedTx cached = txCache.get(cacheKey(index, source));
        if (cached == null) cached = txCache.get(cacheKey(index, "^all"));
        if (cached != null && position == 0 && !cached.fragments.isEmpty()) {
            position = cached.fragments.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
        if (cached != null && cached.fragments.containsKey(position)) {
            result.transmissions.add(new Transmission(source, cached.fragments.get(position), "retransmit"));
            retransmissions++;
        }
        return result;
    }

    private List<Integer> missingPositions(Assembly assembly) {
        List<Integer> missing = new ArrayList<>();
        if (assembly.last == null) return missing;
        for (int position = 1; position <= assembly.last; position++) {
            if (!assembly.fragments.containsKey(position)) missing.add(position);
        }
        return missing;
    }

    private void appendRepairRequests(
            Result result, Assembly assembly, List<Integer> missing, long now,
            int budget, boolean immediate) {
        for (int position : missing) {
            if (budget <= 0) break;
            int attempts = assembly.requestAttempts.getOrDefault(position, 0);
            if (attempts >= MAX_REPAIR_ATTEMPTS) continue;
            long lastRequest = assembly.requested.getOrDefault(position, 0L);
            long delay = requestCooldownMillis * (1L << attempts);
            boolean due = attempts == 0
                    ? immediate || now - assembly.updated >= requestCooldownMillis
                    : now - lastRequest >= delay;
            if (!due) continue;
            result.transmissions.add(new Transmission(
                    assembly.source,
                    new byte[] {'R', 'E', 'Q', (byte) assembly.index, (byte) position},
                    "request"));
            assembly.requested.put(position, now);
            assembly.requestAttempts.put(position, attempts + 1);
            repairRequests++;
            if (position == 0) finalRepairRequests++;
            budget--;
        }
    }

    synchronized Snapshot snapshot() {
        cleanup();
        int awaitingFinal = 0;
        int missingFragments = 0;
        int cappedRepairs = 0;
        for (Assembly assembly : assemblies.values()) {
            if (assembly.last == null) {
                awaitingFinal++;
                if (assembly.requestAttempts.getOrDefault(0, 0) >= MAX_REPAIR_ATTEMPTS) {
                    cappedRepairs++;
                }
                continue;
            }
            for (int position = 1; position <= assembly.last; position++) {
                if (!assembly.fragments.containsKey(position)) {
                    missingFragments++;
                    if (assembly.requestAttempts.getOrDefault(position, 0) >= MAX_REPAIR_ATTEMPTS) {
                        cappedRepairs++;
                    }
                }
            }
        }
        return new Snapshot(
                assemblies.size(), awaitingFinal, missingFragments,
                completedFrames, duplicateFrames, repairRequests, finalRepairRequests,
                cappedRepairs, retransmissions, expiredAssemblies);
    }

    private void cleanup() {
        long cutoff = now() - ttlMillis;
        Iterator<Map.Entry<String, Assembly>> assemblyIterator = assemblies.entrySet().iterator();
        while (assemblyIterator.hasNext()) {
            if (assemblyIterator.next().getValue().updated < cutoff) {
                assemblyIterator.remove();
                expiredAssemblies++;
            }
        }
        txCache.entrySet().removeIf(entry -> entry.getValue().created < cutoff);
        Iterator<Map.Entry<String, Long>> iterator = completed.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue() < cutoff) iterator.remove();
        Iterator<Map.Entry<String, Long>> indexIterator = completedIndices.entrySet().iterator();
        while (indexIterator.hasNext()) if (indexIterator.next().getValue() < cutoff) indexIterator.remove();
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static String cacheKey(int index, String destination) { return index + ":" + destination; }
    private long now() { return clock.getAsLong(); }

    private static String digest(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
