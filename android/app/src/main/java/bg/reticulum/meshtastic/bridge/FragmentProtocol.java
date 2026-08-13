package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final class Assembly {
        long updated;
        Integer last;
        final Map<Integer, byte[]> fragments = new HashMap<>();
        final Map<Integer, Long> requested = new HashMap<>();
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
    private int nextIndex;
    private final Map<String, Assembly> assemblies = new HashMap<>();
    private final LinkedHashMap<String, CachedTx> txCache = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> completed = new LinkedHashMap<>();

    FragmentProtocol(int bodySize) { this(bodySize, 180_000, 5_000); }

    FragmentProtocol(int bodySize, long ttlMillis, long requestCooldownMillis) {
        if (bodySize < 1 || bodySize > 230) throw new IllegalArgumentException("fragment body must be 1..230 bytes");
        this.bodySize = bodySize;
        this.ttlMillis = ttlMillis;
        this.requestCooldownMillis = requestCooldownMillis;
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
        Assembly assembly = assemblies.computeIfAbsent(key, ignored -> new Assembly());
        assembly.updated = now;
        assembly.fragments.put(position, Arrays.copyOfRange(payload, 2, payload.length));
        if (wirePosition < 0) assembly.last = position;

        Result result = new Result();
        if (assembly.last == null) return result;
        for (int p = 1; p <= assembly.last; p++) {
            if (!assembly.fragments.containsKey(p)) {
                long lastRequest = assembly.requested.getOrDefault(p, 0L);
                if (now - lastRequest >= requestCooldownMillis) {
                    result.transmissions.add(new Transmission(source, new byte[] {'R', 'E', 'Q', (byte) index, (byte) p}, "request"));
                    assembly.requested.put(p, now);
                }
            }
        }
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
        if (cached != null && cached.fragments.containsKey(position)) {
            result.transmissions.add(new Transmission(source, cached.fragments.get(position), "retransmit"));
        }
        return result;
    }

    private void cleanup() {
        long cutoff = now() - ttlMillis;
        assemblies.entrySet().removeIf(entry -> entry.getValue().updated < cutoff);
        txCache.entrySet().removeIf(entry -> entry.getValue().created < cutoff);
        Iterator<Map.Entry<String, Long>> iterator = completed.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue() < cutoff) iterator.remove();
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static String cacheKey(int index, String destination) { return index + ":" + destination; }
    private static long now() { return System.currentTimeMillis(); }

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
