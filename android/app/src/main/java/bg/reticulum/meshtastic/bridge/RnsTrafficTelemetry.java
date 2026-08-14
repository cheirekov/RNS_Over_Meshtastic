package bg.reticulum.meshtastic.bridge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Secret-free Reticulum packet mix and rolling radio activity telemetry. */
final class RnsTrafficTelemetry {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int TRUNCATED_HASH_BYTES = 16;

    static final class Snapshot {
        final String frameMix;
        final String radioWindow;

        Snapshot(String frameMix, String radioWindow) {
            this.frameMix = frameMix;
            this.radioWindow = radioWindow;
        }
    }

    private static final class FrameCounters {
        final long[] types = new long[4];
        long malformed;
        long bytes;
        String last = "none";
    }

    private static final class RadioEvent {
        final long at;
        final int bytes;
        final String reason;

        RadioEvent(long at, int bytes, String reason) {
            this.at = at;
            this.bytes = bytes;
            this.reason = reason;
        }
    }

    private final FrameCounters txFrames = new FrameCounters();
    private final FrameCounters rxFrames = new FrameCounters();
    private final Deque<RadioEvent> radioTx = new ArrayDeque<>();
    private final Deque<RadioEvent> radioRx = new ArrayDeque<>();

    synchronized void recordFrame(boolean outbound, byte[] frame) {
        FrameCounters counters = outbound ? txFrames : rxFrames;
        counters.bytes += frame == null ? 0 : frame.length;
        String description = describe(frame);
        if (description.startsWith("malformed")) {
            counters.malformed++;
        } else {
            counters.types[packetType(frame)]++;
        }
        counters.last = description;
    }

    synchronized void recordRadioTx(String reason, int bytes, long now) {
        radioTx.addLast(new RadioEvent(now, Math.max(0, bytes), reason));
        prune(now);
    }

    synchronized void recordRadioRx(int bytes, long now) {
        radioRx.addLast(new RadioEvent(now, Math.max(0, bytes), "rx"));
        prune(now);
    }

    synchronized Snapshot snapshot(long now) {
        prune(now);
        return new Snapshot(
                "RNS frame mix: TX " + formatFrames(txFrames)
                        + "; RX " + formatFrames(rxFrames),
                "radio activity (last 60s): TX " + formatRadio(radioTx, true)
                        + "; RX " + formatRadio(radioRx, false));
    }

    private void prune(long now) {
        long cutoff = now - WINDOW_MILLIS;
        while (!radioTx.isEmpty() && radioTx.peekFirst().at < cutoff) radioTx.removeFirst();
        while (!radioRx.isEmpty() && radioRx.peekFirst().at < cutoff) radioRx.removeFirst();
    }

    private static String formatFrames(FrameCounters counters) {
        return "data " + counters.types[0]
                + ", announce " + counters.types[1]
                + ", link " + counters.types[2]
                + ", proof " + counters.types[3]
                + ", malformed " + counters.malformed
                + ", " + counters.bytes + " B, last " + counters.last;
    }

    private static String formatRadio(Deque<RadioEvent> events, boolean includeReasons) {
        long bytes = 0;
        int data = 0;
        int repair = 0;
        for (RadioEvent event : events) {
            bytes += event.bytes;
            if (includeReasons) {
                if ("request".equals(event.reason) || "retransmit".equals(event.reason)) repair++;
                else data++;
            }
        }
        String result = events.size() + " fragments/" + bytes + " B";
        if (includeReasons) result += " (data " + data + ", repair " + repair + ")";
        return result;
    }

    static String describe(byte[] frame) {
        if (frame == null || frame.length < 2) return "malformed (" + (frame == null ? 0 : frame.length) + " B)";
        int type = packetType(frame);
        int headerType = (frame[0] & 0x40) >>> 6;
        int contextIndex = headerType == 0
                ? 2 + TRUNCATED_HASH_BYTES
                : 2 + 2 * TRUNCATED_HASH_BYTES;
        if (frame.length <= contextIndex) return "malformed (" + frame.length + " B)";
        int context = frame[contextIndex] & 0xff;
        return typeName(type) + "/" + contextName(context) + " (" + frame.length + " B)";
    }

    static int schedulerPriority(byte[] frame) {
        if (frame == null || frame.length < 1) return TransmitScheduler.PRIORITY_NORMAL;
        return switch (packetType(frame)) {
            case 1 -> TransmitScheduler.PRIORITY_ANNOUNCE;
            case 2, 3 -> TransmitScheduler.PRIORITY_HIGH;
            default -> TransmitScheduler.PRIORITY_NORMAL;
        };
    }

    private static int packetType(byte[] frame) { return frame[0] & 0x03; }

    private static String typeName(int type) {
        return switch (type) {
            case 0 -> "data";
            case 1 -> "announce";
            case 2 -> "link";
            case 3 -> "proof";
            default -> "unknown";
        };
    }

    private static String contextName(int context) {
        return switch (context) {
            case 0x00 -> "none";
            case 0x01 -> "resource";
            case 0x02 -> "resource-adv";
            case 0x03 -> "resource-req";
            case 0x05 -> "resource-proof";
            case 0x08 -> "cache-request";
            case 0x09 -> "request";
            case 0x0a -> "response";
            case 0x0b -> "path-response";
            case 0x0e -> "channel";
            case 0xfa -> "keepalive";
            case 0xfd -> "link-proof";
            case 0xff -> "link-request-proof";
            default -> String.format(Locale.ROOT, "context-0x%02x", context);
        };
    }
}
