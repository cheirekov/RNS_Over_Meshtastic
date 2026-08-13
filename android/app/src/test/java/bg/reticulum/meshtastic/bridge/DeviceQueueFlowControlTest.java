package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DeviceQueueFlowControlTest {
    @Test public void unknownOlderFirmwareFallsBackToPacing() throws Exception {
        DeviceQueueFlowControl flow = new DeviceQueueFlowControl();
        assertTrue(flow.acquire(10));
        assertFalse(flow.snapshot().known);
    }

    @Test public void waitsUntilFirmwareReportsAFreeSlot() throws Exception {
        DeviceQueueFlowControl flow = new DeviceQueueFlowControl();
        flow.update(new ProtoCodec.QueueStatus(0, 0, 16, 1));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean acquired = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                started.countDown();
                acquired.set(flow.acquire(2_000));
            } catch (InterruptedException ignored) {}
        });
        waiter.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(25);
        assertFalse(acquired.get());
        flow.update(new ProtoCodec.QueueStatus(0, 1, 16, 2));
        waiter.join(1_000);
        assertTrue(acquired.get());
        assertEquals(0, flow.snapshot().free);
    }

    @Test public void reconnectResetReleasesAWaitingSender() throws Exception {
        DeviceQueueFlowControl flow = new DeviceQueueFlowControl();
        flow.update(new ProtoCodec.QueueStatus(0, 0, 3, 1));
        AtomicBoolean acquired = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            try { acquired.set(flow.acquire(2_000)); }
            catch (InterruptedException ignored) {}
        });
        waiter.start();
        Thread.sleep(25);
        flow.reset();
        waiter.join(1_000);
        assertFalse(waiter.isAlive());
        assertFalse(acquired.get());
    }
}
