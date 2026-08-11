package bg.reticulum.meshtastic.bridge;

import java.util.Locale;

final class NodeId {
    static final long BROADCAST = 0xffffffffL;

    static long parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("^all") || normalized.equals("!ffffffff")) return BROADCAST;
        if (normalized.startsWith("!")) normalized = normalized.substring(1);
        if (!normalized.matches("[0-9a-f]{1,8}")) throw new IllegalArgumentException("Node ID must look like !aabbcc11");
        return Long.parseUnsignedLong(normalized, 16) & 0xffffffffL;
    }

    static String format(long value) {
        return String.format(Locale.ROOT, "!%08x", value & 0xffffffffL);
    }

    private NodeId() {}
}
