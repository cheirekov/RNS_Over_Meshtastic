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
    static final int PRIORITY_HIGH = 0;
    static final int PRIORITY_NORMAL = 1;
    static final int PRIORITY_ANNOUNCE = 2;

    interface Sender { void send(FragmentProtocol.Transmission transmission) throws Exception; }
    interface DelayAdvisor { long extraDelayMillis(); }
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
        final int peakFrames;
        final int peakFragments;
        final int peakBytes;
        final long estimatedDrainMillis;
        final long backpressureEvents;
        final long retryAttempts;
        final long rejectedFrames;
        final long failedFrames;
        final long dataRejectedFrames;
        final long controlRejectedFrames;
        final long dataFailedFrames;
        final long controlFailedFrames;
        final long highPriorityFrames;
        final long normalPriorityFrames;
        final long announcePriorityFrames;
        final long announcePacingWaits;
        final long adaptivePacingEvents;
        final long currentExtraDelayMillis;
        final String lastRejection;

        Snapshot(
                int frames, int fragments, int bytes,
                int peakFrames, int peakFragments, int peakBytes, long estimatedDrainMillis,
                long backpressureEvents, long retryAttempts, long rejectedFrames,
                long failedFrames, long dataRejectedFrames, long controlRejectedFrames,
                long dataFailedFrames, long controlFailedFrames,
                long highPriorityFrames, long normalPriorityFrames,
                long announcePriorityFrames, long announcePacingWaits,
                long adaptivePacingEvents, long currentExtraDelayMillis,
                String lastRejection) {
            this.frames = frames;
            this.fragments = fragments;
            this.bytes = bytes;
            this.peakFrames = peakFrames;
            this.peakFragments = peakFragments;
            this.peakBytes = peakBytes;
            this.estimatedDrainMillis = estimatedDrainMillis;
            this.backpressureEvents = backpressureEvents;
            this.retryAttempts = retryAttempts;
            this.rejectedFrames = rejectedFrames;
            this.failedFrames = failedFrames;
            this.dataRejectedFrames = dataRejectedFrames;
            this.controlRejectedFrames = controlRejectedFrames;
            this.dataFailedFrames = dataFailedFrames;
            this.controlFailedFrames = controlFailedFrames;
            this.highPriorityFrames = highPriorityFrames;
            this.normalPriorityFrames = normalPriorityFrames;
            this.announcePriorityFrames = announcePriorityFrames;
            this.announcePacingWaits = announcePacingWaits;
            this.adaptivePacingEvents = adaptivePacingEvents;
            this.currentExtraDelayMillis = currentExtraDelayMillis;
            this.lastRejection = lastRejection;
        }
    }

    private static final class Batch {
        final Deque<FragmentProtocol.Transmission> transmissions;
        final boolean control;
        final int priority;
        int localFailures;
        boolean started;
        boolean pacingWaitCounted;

        Batch(List<FragmentProtocol.Transmission> transmissions, boolean control, int priority) {
            this.transmissions = new ArrayDeque<>(transmissions);
            this.control = control;
            this.priority = priority;
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
    private final Deque<Batch> highQueue = new ArrayDeque<>();
    private final Deque<Batch> normalQueue = new ArrayDeque<>();
    private final Deque<Batch> announceQueue = new ArrayDeque<>();
    private final int maxFrames;
    private final int maxFragments;
    private final int maxBytes;
    private final int reservedControlFrames;
    private final int reservedControlFragments;
    private final int reservedControlBytes;
    private final long intervalNanos;
    private final long announceIntervalNanos;
    private final DelayAdvisor delayAdvisor;
    private final Sender sender;
    private final Listener listener;
    private final TimeSource time;
    private final Thread worker;

    private boolean closed;
    private int pendingFrames;
    private int pendingFragments;
    private int pendingBytes;
    private int peakFrames;
    private int peakFragments;
    private int peakBytes;
    private long nextSendNanos;
    private long backpressureEvents;
    private long retryAttempts;
    private long rejectedFrames;
    private long failedFrames;
    private long dataRejectedFrames;
    private long controlRejectedFrames;
    private long dataFailedFrames;
    private long controlFailedFrames;
    private long highPriorityFrames;
    private long normalPriorityFrames;
    private long announcePriorityFrames;
    private long announcePacingWaits;
    private long adaptivePacingEvents;
    private long currentExtraDelayMillis;
    private int consecutiveControlSends;
    private int consecutiveNonAnnounceFrames;
    private long nextAnnounceStartNanos;
    private String lastRejection = "none";

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
                0, () -> 0, sender, listener, SYSTEM_TIME);
    }

    TransmitScheduler(
            int intervalMillis,
            int maxFrames,
            int maxFragments,
            int maxBytes,
            int reservedControlFrames,
            int reservedControlFragments,
            int reservedControlBytes,
            int announceIntervalMillis,
            DelayAdvisor delayAdvisor,
            Sender sender,
            Listener listener) {
        this(intervalMillis, maxFrames, maxFragments, maxBytes,
                reservedControlFrames, reservedControlFragments, reservedControlBytes,
                announceIntervalMillis, delayAdvisor, sender, listener, SYSTEM_TIME);
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
        this(intervalMillis, maxFrames, maxFragments, maxBytes,
                reservedControlFrames, reservedControlFragments, reservedControlBytes,
                0, () -> 0, sender, listener, time);
    }

    TransmitScheduler(
            int intervalMillis,
            int maxFrames,
            int maxFragments,
            int maxBytes,
            int reservedControlFrames,
            int reservedControlFragments,
            int reservedControlBytes,
            int announceIntervalMillis,
            DelayAdvisor delayAdvisor,
            Sender sender,
            Listener listener,
            TimeSource time) {
        if (intervalMillis < 0) throw new IllegalArgumentException("interval cannot be negative");
        if (announceIntervalMillis < 0) throw new IllegalArgumentException("announce interval cannot be negative");
        if (maxFrames < 1 || maxFragments < 1 || maxBytes < 1) throw new IllegalArgumentException("queue limits must be positive");
        if (reservedControlFrames < 0 || reservedControlFrames >= maxFrames) throw new IllegalArgumentException("invalid frame reserve");
        if (reservedControlFragments < 0 || reservedControlFragments >= maxFragments) throw new IllegalArgumentException("invalid fragment reserve");
        if (reservedControlBytes < 0 || reservedControlBytes >= maxBytes) throw new IllegalArgumentException("invalid byte reserve");
        this.intervalNanos = intervalMillis * 1_000_000L;
        this.announceIntervalNanos = announceIntervalMillis * 1_000_000L;
        this.maxFrames = maxFrames;
        this.maxFragments = maxFragments;
        this.maxBytes = maxBytes;
        this.reservedControlFrames = reservedControlFrames;
        this.reservedControlFragments = reservedControlFragments;
        this.reservedControlBytes = reservedControlBytes;
        this.sender = sender;
        this.listener = listener;
        this.delayAdvisor = delayAdvisor;
        this.time = time;
        worker = new Thread(this::run, "meshtastic-radio-scheduler");
        worker.start();
    }

    boolean enqueue(List<FragmentProtocol.Transmission> transmissions, boolean waitForCapacity)
            throws InterruptedException {
        return enqueue(transmissions, waitForCapacity, PRIORITY_NORMAL);
    }

    boolean enqueue(
            List<FragmentProtocol.Transmission> transmissions,
            boolean waitForCapacity,
            int priority) throws InterruptedException {
        if (transmissions.isEmpty()) return true;
        if (priority < PRIORITY_HIGH || priority > PRIORITY_ANNOUNCE) {
            throw new IllegalArgumentException("invalid scheduler priority " + priority);
        }
        List<FragmentProtocol.Transmission> immutable = new ArrayList<>(transmissions);
        boolean control = immutable.stream().allMatch(TransmitScheduler::isControl);
        if (control) priority = PRIORITY_HIGH;
        int fragments = immutable.size();
        int bytes = immutable.stream().mapToInt(item -> item.payload.length).sum();
        boolean waited = false;
        Snapshot changed;
        synchronized (lock) {
            if (!canEverFit(fragments, bytes, control)) {
                countRejected(control);
                lastRejection = rejectionDescription(
                        control ? "control frame" : "data frame", fragments, bytes,
                        control ? maxFragments : maxFragments - reservedControlFragments,
                        control ? maxBytes : maxBytes - reservedControlBytes,
                        "exceeds admission limit");
                changed = snapshotLocked();
                notifyChanged(changed);
                return false;
            }
            while (!closed && !hasCapacity(fragments, bytes, control)) {
                if (!waitForCapacity) {
                    countRejected(control);
                    lastRejection = rejectionDescription(
                            control ? "control frame" : "data frame", fragments, bytes,
                            control ? maxFragments : maxFragments - reservedControlFragments,
                            control ? maxBytes : maxBytes - reservedControlBytes,
                            "queue full");
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
            Batch batch = new Batch(immutable, control, priority);
            queueFor(batch).addLast(batch);
            if (!control) {
                if (priority == PRIORITY_HIGH) highPriorityFrames++;
                else if (priority == PRIORITY_ANNOUNCE) announcePriorityFrames++;
                else normalPriorityFrames++;
            }
            pendingFrames++;
            pendingFragments += fragments;
            pendingBytes += bytes;
            peakFrames = Math.max(peakFrames, pendingFrames);
            peakFragments = Math.max(peakFragments, pendingFragments);
            peakBytes = Math.max(peakBytes, pendingBytes);
            changed = snapshotLocked();
            lock.notifyAll();
        }
        notifyChanged(changed);
        return true;
    }

    boolean canAcceptControl(int fragments, int bytes) {
        synchronized (lock) {
            return !closed && canEverFit(fragments, bytes, true)
                    && hasCapacity(fragments, bytes, true);
        }
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
                    while (true) {
                        while (!closed && allQueuesEmpty()) lock.wait();
                        if (closed) return;
                        boolean chooseControl = !controlQueue.isEmpty()
                                && (allDataQueuesEmpty() || consecutiveControlSends < 1);
                        if (chooseControl) {
                            batch = controlQueue.removeFirst();
                            break;
                        }
                        batch = takeReadyDataLocked();
                        if (batch != null) break;
                        long waitNanos = Math.max(1, nextAnnounceStartNanos - time.nanoTime());
                        long millis = waitNanos / 1_000_000L;
                        int nanos = (int) (waitNanos % 1_000_000L);
                        lock.wait(millis, nanos);
                    }
                    transmission = batch.transmissions.peekFirst();
                }

                try {
                    awaitGlobalPacing();
                    sender.send(transmission);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception error) {
                    Snapshot changed;
                    boolean retry;
                    synchronized (lock) {
                        batch.localFailures++;
                        retry = !closed && batch.localFailures < 3;
                        if (retry) {
                            retryAttempts++;
                            queueFor(batch).addFirst(batch);
                        } else {
                            int abandonedFragments = batch.transmissions.size();
                            int abandonedBytes = batch.transmissions.stream()
                                    .mapToInt(item -> item.payload.length).sum();
                            pendingFrames--;
                            pendingFragments -= abandonedFragments;
                            pendingBytes -= abandonedBytes;
                            countFailed(batch.control);
                        }
                        changed = snapshotLocked();
                        lock.notifyAll();
                    }
                    if (!retry) listener.onFailure(transmission, error);
                    notifyChanged(changed);
                    if (retry) time.sleepNanos(1_000_000_000L);
                    continue;
                }

                long advisedDelay = Math.max(0, delayAdvisor.extraDelayMillis());
                Snapshot changed;
                synchronized (lock) {
                    currentExtraDelayMillis = advisedDelay;
                    if (advisedDelay > 0) adaptivePacingEvents++;
                    nextSendNanos = time.nanoTime() + intervalNanos
                            + advisedDelay * 1_000_000L;
                    batch.started = true;
                    batch.transmissions.removeFirst();
                    pendingFragments--;
                    pendingBytes -= transmission.payload.length;
                    if (batch.transmissions.isEmpty()) {
                        pendingFrames--;
                        if (!batch.control && batch.priority == PRIORITY_ANNOUNCE) {
                            nextAnnounceStartNanos = time.nanoTime() + announceIntervalNanos;
                            consecutiveNonAnnounceFrames = 0;
                        } else if (!batch.control) consecutiveNonAnnounceFrames++;
                    } else queueFor(batch).addFirst(batch);
                    consecutiveControlSends = batch.control ? consecutiveControlSends + 1 : 0;
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

    private Batch takeReadyDataLocked() {
        long now = time.nanoTime();
        Batch announce = announceQueue.peekFirst();
        boolean announceReady = announce != null
                && (announce.started || announceIntervalNanos == 0 || now >= nextAnnounceStartNanos);
        if (announce != null && !announceReady && !announce.pacingWaitCounted) {
            announce.pacingWaitCounted = true;
            announcePacingWaits++;
        }
        if (announceReady && consecutiveNonAnnounceFrames >= 4) {
            return announceQueue.removeFirst();
        }
        if (!highQueue.isEmpty()) return highQueue.removeFirst();
        if (!normalQueue.isEmpty()) return normalQueue.removeFirst();
        if (announceReady) return announceQueue.removeFirst();
        return null;
    }

    private boolean allQueuesEmpty() {
        return controlQueue.isEmpty() && allDataQueuesEmpty();
    }

    private boolean allDataQueuesEmpty() {
        return highQueue.isEmpty() && normalQueue.isEmpty() && announceQueue.isEmpty();
    }

    private Deque<Batch> queueFor(Batch batch) {
        if (batch.control) return controlQueue;
        if (batch.priority == PRIORITY_HIGH) return highQueue;
        if (batch.priority == PRIORITY_ANNOUNCE) return announceQueue;
        return normalQueue;
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
        long effectiveInterval = intervalNanos + currentExtraDelayMillis * 1_000_000L;
        long afterFirst = Math.max(0, pendingFragments - 1L) * effectiveInterval;
        long estimate = pendingFragments == 0 ? 0 : (untilNext + afterFirst) / 1_000_000L;
        return new Snapshot(
                pendingFrames, pendingFragments, pendingBytes,
                peakFrames, peakFragments, peakBytes, estimate,
                backpressureEvents, retryAttempts, rejectedFrames, failedFrames,
                dataRejectedFrames, controlRejectedFrames, dataFailedFrames, controlFailedFrames,
                highPriorityFrames, normalPriorityFrames, announcePriorityFrames,
                announcePacingWaits, adaptivePacingEvents, currentExtraDelayMillis,
                lastRejection);
    }

    private void countRejected(boolean control) {
        rejectedFrames++;
        if (control) controlRejectedFrames++;
        else dataRejectedFrames++;
    }

    private void countFailed(boolean control) {
        failedFrames++;
        if (control) controlFailedFrames++;
        else dataFailedFrames++;
    }

    private static String rejectionDescription(
            String kind, int fragments, int bytes, int fragmentLimit, int byteLimit,
            String reason) {
        return kind + " " + fragments + " fragments/" + bytes + " bytes " + reason
                + " " + fragmentLimit + " fragments/" + byteLimit + " bytes";
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
            highQueue.clear();
            normalQueue.clear();
            announceQueue.clear();
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
