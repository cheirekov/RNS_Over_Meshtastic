package bg.reticulum.meshtastic.bridge;

/** Small capped exponential backoff shared by BLE and TCP reconnect loops. */
final class ReconnectBackoff {
    private final long initialMillis;
    private final long maximumMillis;
    private long currentMillis;

    ReconnectBackoff(long initialMillis, long maximumMillis) {
        if (initialMillis <= 0 || maximumMillis < initialMillis) {
            throw new IllegalArgumentException("Invalid reconnect backoff range");
        }
        this.initialMillis = initialMillis;
        this.maximumMillis = maximumMillis;
        this.currentMillis = initialMillis;
    }

    synchronized long nextDelayMillis() {
        long result = currentMillis;
        currentMillis = Math.min(maximumMillis, currentMillis * 2);
        return result;
    }

    synchronized void reset() { currentMillis = initialMillis; }
}
