package bg.reticulum.meshtastic.bridge;

import java.util.UUID;

/** Monotonic, secret-free lifecycle telemetry for one in-process bridge session. */
final class SessionTelemetry {
    private final long startedAtMillis;
    private final String sessionId;
    private boolean radioKnown;
    private boolean radioConnected;
    private boolean clientKnown;
    private boolean clientConnected;
    private long radioUp;
    private long radioDown;
    private long clientUp;
    private long clientDown;

    SessionTelemetry(long startedAtMillis) {
        this(startedAtMillis, UUID.randomUUID().toString().substring(0, 8));
    }

    SessionTelemetry(long startedAtMillis, String sessionId) {
        this.startedAtMillis = startedAtMillis;
        this.sessionId = sessionId;
    }

    synchronized void recordRadio(boolean connected) {
        if (!radioKnown) {
            radioKnown = true;
            radioConnected = connected;
            if (connected) radioUp++;
            return;
        }
        if (radioConnected == connected) return;
        radioConnected = connected;
        if (connected) radioUp++;
        else radioDown++;
    }

    synchronized void recordClient(boolean connected) {
        if (!clientKnown) {
            clientKnown = true;
            clientConnected = connected;
            if (connected) clientUp++;
            return;
        }
        if (clientConnected == connected) return;
        clientConnected = connected;
        if (connected) clientUp++;
        else clientDown++;
    }

    synchronized String describe(long nowMillis) {
        return "bridge session: " + sessionId + ", uptime "
                + duration(Math.max(0, nowMillis - startedAtMillis))
                + "; radio up/down " + radioUp + "/" + radioDown
                + "; RNS client up/down " + clientUp + "/" + clientDown;
    }

    private static String duration(long millis) {
        long seconds = millis / 1_000;
        long hours = seconds / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + remainder + "s";
        if (minutes > 0) return minutes + "m " + remainder + "s";
        return remainder + "s";
    }
}
