package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TransmitSchedulerTest {
    private static final class FakeTime implements TransmitScheduler.TimeSource {
        private long now;
        final List<Long> sleeps = new ArrayList<>();

        @Override public synchronized long nanoTime() { return now; }

        @Override public synchronized void sleepNanos(long nanos) {
            sleeps.add(nanos);
            now += nanos;
        }
    }

    @Test public void appliesPacingAcrossSeparateSingleFragmentFrames() throws Exception {
        FakeTime time = new FakeTime();
        List<Long> sendTimes = new ArrayList<>();
        CountDownLatch sent = new CountDownLatch(3);
        TransmitScheduler scheduler = scheduler(time, transmission -> {
            synchronized (sendTimes) { sendTimes.add(time.nanoTime()); }
            sent.countDown();
        });
        try {
            assertTrue(scheduler.enqueue(one("data", 1), false));
            assertTrue(scheduler.enqueue(one("data", 2), false));
            assertTrue(scheduler.enqueue(one("data", 3), false));
            assertTrue(sent.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(0L, 2_000_000_000L, 4_000_000_000L), sendTimes);
            assertEquals(List.of(2_000_000_000L, 2_000_000_000L), time.sleeps);
        } finally {
            scheduler.close();
        }
    }

    @Test public void reservesCapacityAndCountsRejectedFrames() throws Exception {
        FakeTime time = new FakeTime();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransmitScheduler scheduler = new TransmitScheduler(
                0, 2, 4, 64, 0, 0, 0,
                transmission -> {
                    sending.countDown();
                    release.await();
                }, listener(), time);
        try {
            assertTrue(scheduler.enqueue(one("data", 1), false));
            assertTrue(sending.await(1, TimeUnit.SECONDS));
            assertTrue(scheduler.enqueue(one("data", 2), false));
            assertFalse(scheduler.enqueue(one("data", 3), false));
            TransmitScheduler.Snapshot snapshot = scheduler.snapshot();
            assertEquals(2, snapshot.frames);
            assertEquals(2, snapshot.fragments);
            assertEquals(2, snapshot.peakFrames);
            assertEquals(2, snapshot.peakFragments);
            assertEquals(1, snapshot.rejectedFrames);
            assertEquals(1, snapshot.dataRejectedFrames);
            assertEquals(0, snapshot.controlRejectedFrames);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test public void explainsFrameThatCanNeverFitAdmissionWindow() throws Exception {
        FakeTime time = new FakeTime();
        TransmitScheduler scheduler = new TransmitScheduler(
                2_000, 8, 4, 808, 2, 1, 202,
                transmission -> {}, listener(), time);
        try {
            List<FragmentProtocol.Transmission> oversized = List.of(
                    transmission(1, 202), transmission(2, 202),
                    transmission(3, 202), transmission(4, 202));
            assertFalse(scheduler.enqueue(oversized, true));
            TransmitScheduler.Snapshot snapshot = scheduler.snapshot();
            assertEquals(1, snapshot.rejectedFrames);
            assertEquals(
                    "data frame 4 fragments/808 bytes exceeds admission limit "
                            + "3 fragments/606 bytes",
                    snapshot.lastRejection);
        } finally {
            scheduler.close();
        }
    }

    @Test public void serializesOneBoundedBulkFrameAndKeepsRepairCapacity() throws Exception {
        FakeTime time = new FakeTime();
        List<Integer> sent = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(5);
        TransmitScheduler scheduler = new TransmitScheduler(
                0, 8, 4, 808, 2, 1, 202,
                transmission -> {
                    synchronized (sent) { sent.add((int) transmission.payload[0]); }
                    if (transmission.payload[0] == 1) {
                        firstStarted.countDown();
                        releaseFirst.await();
                    }
                    completed.countDown();
                }, listener(), time);
        try {
            List<FragmentProtocol.Transmission> bulk = List.of(
                    transmission(1, 202), transmission(2, 202),
                    transmission(3, 202), transmission(4, 202));
            assertTrue(scheduler.enqueueSerialized(bulk, true, 8, 1616));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, scheduler.snapshot().serializedFrames);
            assertTrue(scheduler.enqueue(one("request", 9), false));
            releaseFirst.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 9, 2, 3, 4), sent);
            assertEquals(0, scheduler.snapshot().serializedFrames);
            assertEquals(1, scheduler.snapshot().serializedAcceptedFrames);
            assertEquals(0, scheduler.snapshot().rejectedFrames);
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }

    @Test public void retriesTransientLocalSendFailureWithoutDroppingRemainingFragments() throws Exception {
        FakeTime time = new FakeTime();
        List<Integer> sent = new ArrayList<>();
        int[] attempts = {0};
        CountDownLatch completed = new CountDownLatch(2);
        TransmitScheduler scheduler = scheduler(time, transmission -> {
            attempts[0]++;
            if (attempts[0] == 1) throw new IllegalStateException("transient GATT failure");
            sent.add((int) transmission.payload[0]);
            completed.countDown();
        });
        try {
            List<FragmentProtocol.Transmission> frame = List.of(
                    new FragmentProtocol.Transmission("!12345678", new byte[] {1}, "data"),
                    new FragmentProtocol.Transmission("!12345678", new byte[] {2}, "data"));
            assertTrue(scheduler.enqueue(frame, false));
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), sent);
            assertEquals(1, scheduler.snapshot().retryAttempts);
            assertEquals(0, scheduler.snapshot().failedFrames);
        } finally {
            scheduler.close();
        }
    }

    @Test public void alternatesControlWithWaitingDataDuringRepairStorm() throws Exception {
        FakeTime time = new FakeTime();
        List<Integer> sent = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4);
        TransmitScheduler scheduler = scheduler(time, transmission -> {
            synchronized (sent) { sent.add((int) transmission.payload[0]); }
            if (transmission.payload[0] == 1) {
                firstStarted.countDown();
                releaseFirst.await();
            }
            completed.countDown();
        });
        try {
            assertTrue(scheduler.enqueue(one("data", 1), false));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(scheduler.enqueue(one("request", 2), false));
            assertTrue(scheduler.enqueue(one("request", 3), false));
            assertTrue(scheduler.enqueue(one("data", 4), false));
            releaseFirst.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 4, 3), sent);
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }

    @Test public void controlCapacityProbeDoesNotCountARejection() throws Exception {
        FakeTime time = new FakeTime();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransmitScheduler scheduler = new TransmitScheduler(
                0, 2, 2, 16, 0, 0, 0,
                transmission -> {
                    sending.countDown();
                    release.await();
                }, listener(), time);
        try {
            assertTrue(scheduler.enqueue(one("request", 1), false));
            assertTrue(sending.await(1, TimeUnit.SECONDS));
            assertTrue(scheduler.enqueue(one("request", 2), false));
            assertFalse(scheduler.canAcceptControl(1, 1));
            assertEquals(0, scheduler.snapshot().controlRejectedFrames);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test public void constrainedPrioritySendsProofBeforeDataBeforeAnnounce() throws Exception {
        FakeTime time = new FakeTime();
        List<Integer> sent = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4);
        TransmitScheduler scheduler = new TransmitScheduler(
                0, 16, 64, 4096, 2, 4, 256,
                0, () -> 0,
                transmission -> {
                    synchronized (sent) { sent.add((int) transmission.payload[0]); }
                    if (transmission.payload[0] == 1) {
                        firstStarted.countDown();
                        releaseFirst.await();
                    }
                    completed.countDown();
                }, listener(), time);
        try {
            assertTrue(scheduler.enqueue(one("data", 1), false, TransmitScheduler.PRIORITY_NORMAL));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(scheduler.enqueue(one("data", 2), false, TransmitScheduler.PRIORITY_ANNOUNCE));
            assertTrue(scheduler.enqueue(one("data", 3), false, TransmitScheduler.PRIORITY_NORMAL));
            assertTrue(scheduler.enqueue(one("data", 4), false, TransmitScheduler.PRIORITY_HIGH));
            releaseFirst.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 4, 3, 2), sent);
            TransmitScheduler.Snapshot snapshot = scheduler.snapshot();
            assertEquals(1, snapshot.highPriorityFrames);
            assertEquals(2, snapshot.normalPriorityFrames);
            assertEquals(1, snapshot.announcePriorityFrames);
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }

    @Test public void queueAdvisorExtendsGlobalPacing() throws Exception {
        FakeTime time = new FakeTime();
        List<Long> sendTimes = new ArrayList<>();
        CountDownLatch sent = new CountDownLatch(2);
        TransmitScheduler scheduler = new TransmitScheduler(
                2_000, 16, 64, 4096, 2, 4, 256,
                0, () -> 1_000,
                transmission -> {
                    synchronized (sendTimes) { sendTimes.add(time.nanoTime()); }
                    sent.countDown();
                }, listener(), time);
        try {
            assertTrue(scheduler.enqueue(one("data", 1), false));
            assertTrue(scheduler.enqueue(one("data", 2), false));
            assertTrue(sent.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(0L, 3_000_000_000L), sendTimes);
            assertEquals(1_000, scheduler.snapshot().currentExtraDelayMillis);
            assertTrue(scheduler.snapshot().adaptivePacingEvents >= 1);
        } finally {
            scheduler.close();
        }
    }

    @Test public void normalRnsFramesPreserveCausalArrivalOrder() throws Exception {
        FakeTime time = new FakeTime();
        List<Integer> sent = new ArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(4);
        TransmitScheduler scheduler = new TransmitScheduler(
                0, 16, 64, 4096, 2, 4, 256,
                0, () -> 0,
                transmission -> {
                    synchronized (sent) { sent.add((int) transmission.payload[0]); }
                    if (transmission.payload[0] == 1) {
                        firstStarted.countDown();
                        releaseFirst.await();
                    }
                    completed.countDown();
                }, listener(), time);
        try {
            // These values stand for announce, data, announce and proof. The
            // production bridge deliberately submits every RNS type as NORMAL.
            assertTrue(scheduler.enqueue(one("data", 1), false,
                    TransmitScheduler.PRIORITY_NORMAL));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(scheduler.enqueue(one("data", 2), false,
                    TransmitScheduler.PRIORITY_NORMAL));
            assertTrue(scheduler.enqueue(one("data", 3), false,
                    TransmitScheduler.PRIORITY_NORMAL));
            assertTrue(scheduler.enqueue(one("data", 4), false,
                    TransmitScheduler.PRIORITY_NORMAL));
            releaseFirst.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3, 4), sent);
            assertEquals(4, scheduler.snapshot().normalPriorityFrames);
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }

    @Test public void announceSpacingDoesNotBlockAProofThatArrivesLater() throws Exception {
        List<Integer> sent = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        TransmitScheduler scheduler = new TransmitScheduler(
                0, 16, 64, 4096, 2, 4, 256,
                120, () -> 0,
                transmission -> {
                    synchronized (sent) {
                        sent.add((int) transmission.payload[0]);
                        times.add(System.nanoTime());
                    }
                    if (transmission.payload[0] == 1) first.countDown();
                    completed.countDown();
                }, listener());
        try {
            assertTrue(scheduler.enqueue(one("data", 1), false, TransmitScheduler.PRIORITY_ANNOUNCE));
            assertTrue(first.await(1, TimeUnit.SECONDS));
            assertTrue(scheduler.enqueue(one("data", 2), false, TransmitScheduler.PRIORITY_ANNOUNCE));
            assertTrue(scheduler.enqueue(one("data", 3), false, TransmitScheduler.PRIORITY_HIGH));
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 3, 2), sent);
            assertTrue(times.get(2) - times.get(0) >= 100_000_000L);
            assertTrue(scheduler.snapshot().announcePacingWaits >= 1);
        } finally {
            scheduler.close();
        }
    }

    private static TransmitScheduler scheduler(
            FakeTime time, TransmitScheduler.Sender sender) {
        return new TransmitScheduler(
                2_000, 16, 64, 4096, 2, 4, 256,
                sender, listener(), time);
    }

    private static TransmitScheduler.Listener listener() {
        return new TransmitScheduler.Listener() {
            @Override public void onChanged(TransmitScheduler.Snapshot snapshot) {}
            @Override public void onFailure(FragmentProtocol.Transmission transmission, Exception error) {
                throw new AssertionError(error);
            }
        };
    }

    private static List<FragmentProtocol.Transmission> one(String reason, int value) {
        return List.of(new FragmentProtocol.Transmission("!12345678", new byte[] {(byte) value}, reason));
    }

    private static FragmentProtocol.Transmission transmission(int value, int bytes) {
        byte[] payload = new byte[bytes];
        payload[0] = (byte) value;
        return new FragmentProtocol.Transmission("!12345678", payload, "data");
    }
}
