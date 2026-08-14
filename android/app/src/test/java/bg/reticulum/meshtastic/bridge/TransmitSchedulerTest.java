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
            assertEquals(1, snapshot.rejectedFrames);
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
