package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class InboundFrameSpoolTest {
    @Test public void queuesDeduplicatesAndReplaysInOrder() {
        AtomicLong now = new AtomicLong(1_000);
        InboundFrameSpool spool = new InboundFrameSpool(3, 16, 5_000, now::get);

        assertEquals(InboundFrameSpool.OfferResult.QUEUED, spool.offer(new byte[] {1, 2}));
        assertEquals(InboundFrameSpool.OfferResult.DUPLICATE, spool.offer(new byte[] {1, 2}));
        assertEquals(InboundFrameSpool.OfferResult.QUEUED, spool.offer(new byte[] {3, 4, 5}));
        assertArrayEquals(new byte[] {1, 2}, spool.peek());

        spool.removeReplayed();
        assertArrayEquals(new byte[] {3, 4, 5}, spool.peek());
        InboundFrameSpool.Snapshot snapshot = spool.snapshot();
        assertEquals(1, snapshot.frames);
        assertEquals(3, snapshot.bytes);
        assertEquals(2, snapshot.queuedFrames);
        assertEquals(1, snapshot.replayedFrames);
        assertEquals(1, snapshot.duplicateFrames);
    }

    @Test public void rejectsWithoutSilentlyEvictingEarlierFrames() {
        InboundFrameSpool spool = new InboundFrameSpool(1, 4, 5_000);
        assertEquals(InboundFrameSpool.OfferResult.QUEUED, spool.offer(new byte[] {1, 2, 3}));
        assertEquals(InboundFrameSpool.OfferResult.REJECTED, spool.offer(new byte[] {4}));
        assertArrayEquals(new byte[] {1, 2, 3}, spool.peek());
        assertEquals(1, spool.snapshot().rejectedFrames);
    }

    @Test public void expiresStaleFramesBeforeAcceptingNewOnes() {
        AtomicLong now = new AtomicLong(1_000);
        InboundFrameSpool spool = new InboundFrameSpool(1, 4, 100, now::get);
        assertEquals(InboundFrameSpool.OfferResult.QUEUED, spool.offer(new byte[] {1, 2}));
        now.set(1_101);
        assertEquals(InboundFrameSpool.OfferResult.QUEUED, spool.offer(new byte[] {3, 4}));
        assertArrayEquals(new byte[] {3, 4}, spool.peek());
        assertEquals(1, spool.snapshot().expiredFrames);
    }

    @Test public void rejectsSingleFrameLargerThanByteBudget() {
        InboundFrameSpool spool = new InboundFrameSpool(2, 2, 5_000);
        assertEquals(InboundFrameSpool.OfferResult.REJECTED, spool.offer(new byte[] {1, 2, 3}));
        assertEquals(0, spool.snapshot().frames);
    }
}
