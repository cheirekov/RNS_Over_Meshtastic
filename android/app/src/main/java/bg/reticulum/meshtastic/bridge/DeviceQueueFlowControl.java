package bg.reticulum.meshtastic.bridge;

/** Tracks the small outbound queue reported by Meshtastic PhoneAPI. */
final class DeviceQueueFlowControl {
    static final class Snapshot {
        final boolean known;
        final int free;
        final int max;
        final int result;

        Snapshot(boolean known, int free, int max, int result) {
            this.known = known;
            this.free = free;
            this.max = max;
            this.result = result;
        }
    }

    private boolean closed;
    private boolean known;
    private int free;
    private int max;
    private int result;
    private long generation;

    synchronized void update(ProtoCodec.QueueStatus status) {
        known = true;
        free = Math.max(0, status.free);
        max = Math.max(free, status.maxLength);
        result = status.result;
        notifyAll();
    }

    /**
     * Reserves a device queue slot. Unknown capacity is allowed because older
     * firmware may not report QueueStatus; global pacing remains the fallback.
     */
    synchronized boolean acquire(long timeoutMillis) throws InterruptedException {
        if (!known) return !closed;
        long observedGeneration = generation;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!closed && free == 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            long millis = remaining / 1_000_000L;
            int nanos = (int) (remaining % 1_000_000L);
            wait(millis, nanos);
            if (generation != observedGeneration) return false;
        }
        if (closed) return false;
        free--;
        return true;
    }

    synchronized void releaseAfterLocalFailure() {
        if (known && free < max) free++;
        notifyAll();
    }

    synchronized Snapshot snapshot() { return new Snapshot(known, free, max, result); }

    /**
     * Soft pacing before the firmware queue is exhausted. This never changes
     * Meshtastic duty-cycle policy; it only stretches the bridge's next send.
     */
    synchronized long recommendedExtraDelayMillis(int baseIntervalMillis) {
        if (!known || max < 4) return 0;
        int used = Math.max(0, max - free);
        long unit = Math.max(1_000, baseIntervalMillis);
        if (used * 4 >= max * 3) return Math.min(8_000, unit * 3);
        if (used * 2 >= max) return Math.min(8_000, unit * 2);
        if (used * 4 >= max) return Math.min(8_000, unit);
        return 0;
    }

    synchronized void reset() {
        generation++;
        known = false;
        free = 0;
        max = 0;
        result = 0;
        notifyAll();
    }

    synchronized void close() {
        closed = true;
        notifyAll();
    }
}
