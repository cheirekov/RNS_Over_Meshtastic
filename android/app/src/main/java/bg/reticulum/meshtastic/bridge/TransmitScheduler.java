package bg.reticulum.meshtastic.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, globally-paced radio scheduler.
 *
 * <p>Capacity is reserved for fragment repair traffic so a bulk transfer cannot
 * prevent its own missing-fragment requests and retransmissions from being
 * sent. Blocking admission is used by the loopback TCP reader to turn a full
 * radio queue into TCP backpressure instead of silent frame loss.</p>
 */
final class TransmitScheduler implements AutoCloseable {
    interface Sender { void send(FragmentProtocol.Transmission transmission) throws Exception; }
    interface Listener {
        void onChanged(Snapshot snapshot);
        void onFailure(FragmentProtocol.Transmission transmission, Exception error);
    }

    interface TimeSource {
        long nanoTime();
        void sleepNanos(long nanos) throws InterruptedException;
    }

    static final class Snapshot {
        final int frames;
        final int fragments;
        final int bytes;
        final long estimatedDrainMillis;
        final long backpressureEvents;
        final long rejectedFrames;
        final long failedFrames;

        Snapshot(
                int frames, int fragments, int bytes, long estimatedDrainMillis,
                long backpressureEvents, long rejectedFrames, long failedFrames) {
            this.frames = frames;
            this.fragments = fragments;
            this.bytes = bytes;
            this.estimatedDrainMillis = estimatedDrainMillis;
            this.backpressureEvents = backpressureEvents;
            this.rejectedFrames = rejectedFrames;
            this.failedFrames = failedFrames;
        }
    }

    private static final class Batch {
        final Deque<FragmentProtocol.Transmission> transmissions;
        final boolean control;

        Batch(List<FragmentProtocol.Transmission> transmissions, boolean control) {
            this.transmissions = new ArrayDeque<>(transmissions);
            this.control = control;
        }
    }

    private static final TimeSource SYSTEM_TIME = new TimeSource() {
        @Override public long nanoTime() { return System.nanoTime(); }

        @Override public void sleepNanos(long nanos) throws InterruptedException {
            if (nanos <= 0) return;
            long millis = nanos / 1_000_000L;
            int remainder = (int) (nanos % 1_000_000L);
            Thread.sleep(millis, remainder);
        }
    };

    private final Object lock = new Object();
    private final Deque<Batch> controlQueue = new ArrayDeque<>();
    private final Deque<Batch> dataQueue = new ArrayDeque<>();
    private final int maxFrames;
    private final int maxFragments;
    private final int maxBytes;
    private final int reservedControlFrames;
    private final int reservedControlFragments;
    private final int reservedControlBytes;
    private final long intervalNanos;
    private final Sender sender;
    private final Listener listener;
    private final TimeSource time;
    private final Thread worker;

    private boolean closed;
    private int pendingFrames;
    private int pendingFragments;
    private int pendingBytes;
    private long nextSendNanos;
    private long backpressureEvents;
    private long rejectedFrames;
    private long failedFrames;

    TransmitScheduler(
            int intervalMillis,
            int maxFrames,
            int maxFragments,
            int maxBytes,
            int reservedControlFrames,
            int reservedControlFragments,
            int reservedControlBytes,
            Sender sender,
            Listener listener) {
        this(intervalMillis, maxFrames, maxFragments, maxBytes,
                reservedControlFrames, reservedControlFragments, reservedControlBytes,
                sender, listener, SYSTEM_TIME);
    }

    TransmitScheduler(
            int intervalMillis,
            int maxFrames,
            int maxFragments,
            int maxBytes,
            int reservedControlFrames,
            int reservedControlFragments,
            int reservedControlBytes,
            Sender sender,
            Listener listener,
            TimeSource time) {
        if (intervalMillis < 0) throw new IllegalArgumentException("interval cannot be negative");
        if (maxFrames < 1 || maxFragments < 1 || maxBytes < 1) throw new IllegalArgumentException("queue limits must be positive");
        if (reservedControlFrames < 0 || reservedControlFrames >= maxFrames) throw new IllegalArgumentException("invalid frame reserve");
        if (reservedControlFragments < 0 || reservedControlFragments >= maxFragments) throw new IllegalArgumentException("invalid fragment reserve");
        if (reservedControlBytes < 0 || reservedControlBytes >= maxBytes) throw new IllegalArgumentException("invalid byte reserve");
        this.intervalNanos = intervalMillis * 1_000_000L;
        this.maxFrames = maxFrames;
        this.maxFragments = maxFragments;
        this.maxBytes = maxBytes;
        this.reservedControlFrames = reservedControlFrames;
        this.reservedControlFragments = reservedControlFragments;
        this.reservedControlBytes = reservedControlBytes;
        this.sender = sender;
        this.listener = listener;
        this.time = time;
        worker = new Thread(this::run, "meshtastic-radio-scheduler");
        worker.start();
    }

    boolean enqueue(List<FragmentProtocol.Transmission> transmissions, boolean waitForCapacity)
            throws InterruptedException {
        if (transmissions.isEmpty()) return true;
        List<FragmentProtocol.Transmission> immutable = new ArrayList<>(transmissions);
        boolean control = immutable.stream().allMatch(TransmitScheduler::isControl);
        int fragments = immutable.size();
        int bytes = immutable.stream().mapToInt(item -> item.payload.length).sum();
        boolean waited = false;
        Snapshot changed;
        synchronized (lock) {
            if (!canEverFit(fragments, bytes, control)) {
                rejectedFrames++;
                changed = snapshotLocked();
                notifyChanged(changed);
                return false;
            }
            while (!closed && !hasCapacity(fragments, bytes, control)) {
                if (!waitForCapacity) {
                    rejectedFrames++;
                    changed = snapshotLocked();
                    notifyChanged(changed);
                    return false;
                }
                if (!waited) {
                    backpressureEvents++;
                    waited = true;
                }
                lock.wait();
            }
            if (closed) return false;
            Batch batch = new Batch(immutable, control);
            (control ? controlQueue : dataQueue).addLast(batch);
            pendingFrames++;
            pendingFragments += fragments;
            pendingBytes += bytes;
            changed = snapshotLocked();
            lock.notifyAll();
        }
        notifyChanged(changed);
        return true;
    }

    Snapshot snapshot() {
        synchronized (lock) { return snapshotLocked(); }
    }

    private void run() {
        while (true) {
            try {
                Batch batch;
                FragmentProtocol.Transmission transmission;
                synchronized (lock) {
                    while (!closed && controlQueue.isEmpty() && dataQueue.isEmpty()) lock.wait();
                    if (closed) return;
                    batch = !controlQueue.isEmpty() ? controlQueue.removeFirst() : dataQueue.removeFirst();
                    transmission = batch.transmissions.peekFirst();
                }

                try {
                    awaitGlobalPacing();
                    sender.send(transmission);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception error) {
                    Snapshot changed;
                    synchronized (lock) {
                        int abandonedFragments = batch.transmissions.size();
                        int abandonedBytes = batch.transmissions.stream().mapToInt(item -> item.payload.length).sum();
                        pendingFrames--;
                        pendingFragments -= abandonedFragments;
                        pendingBytes -= abandonedBytes;
                        failedFrames++;
                        changed = snapshotLocked();
                        lock.notifyAll();
                    }
                    listener.onFailure(transmission, error);
                    notifyChanged(changed);
                    continue;
                }

                Snapshot changed;
                synchronized (lock) {
                    nextSendNanos = time.nanoTime() + intervalNanos;
                    batch.transmissions.removeFirst();
                    pendingFragments--;
                    pendingBytes -= transmission.payload.length;
                    if (batch.transmissions.isEmpty()) pendingFrames--;
                    else (batch.control ? controlQueue : dataQueue).addFirst(batch);
                    changed = snapshotLocked();
                    lock.notifyAll();
                }
                notifyChanged(changed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void awaitGlobalPacing() throws InterruptedException {
        while (true) {
            long wait;
            synchronized (lock) {
                if (closed) throw new InterruptedException("scheduler closed");
                wait = nextSendNanos - time.nanoTime();
            }
            if (wait <= 0) return;
            time.sleepNanos(wait);
        }
    }

    private boolean hasCapacity(int fragments, int bytes, boolean control) {
        int frameLimit = control ? maxFrames : maxFrames - reservedControlFrames;
        int fragmentLimit = control ? maxFragments : maxFragments - reservedControlFragments;
        int byteLimit = control ? maxBytes : maxBytes - reservedControlBytes;
        return pendingFrames + 1 <= frameLimit
                && pendingFragments + fragments <= fragmentLimit
                && pendingBytes + bytes <= byteLimit;
    }

    private boolean canEverFit(int fragments, int bytes, boolean control) {
        int fragmentLimit = control ? maxFragments : maxFragments - reservedControlFragments;
        int byteLimit = control ? maxBytes : maxBytes - reservedControlBytes;
        return fragments <= fragmentLimit && bytes <= byteLimit;
    }

    private Snapshot snapshotLocked() {
        long untilNext = Math.max(0, nextSendNanos - time.nanoTime());
        long afterFirst = Math.max(0, pendingFragments - 1L) * intervalNanos;
        long estimate = pendingFragments == 0 ? 0 : (untilNext + afterFirst) / 1_000_000L;
        return new Snapshot(
                pendingFrames, pendingFragments, pendingBytes, estimate,
                backpressureEvents, rejectedFrames, failedFrames);
    }

    private static boolean isControl(FragmentProtocol.Transmission transmission) {
        return "request".equals(transmission.reason) || "retransmit".equals(transmission.reason);
    }

    private void notifyChanged(Snapshot snapshot) {
        try { listener.onChanged(snapshot); } catch (Exception ignored) {}
    }

    @Override public void close() {
        Snapshot changed;
        synchronized (lock) {
            closed = true;
            controlQueue.clear();
            dataQueue.clear();
            pendingFrames = 0;
            pendingFragments = 0;
            pendingBytes = 0;
            changed = snapshotLocked();
            lock.notifyAll();
        }
        worker.interrupt();
        notifyChanged(changed);
    }
}
