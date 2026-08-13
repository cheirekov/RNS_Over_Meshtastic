package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class AckTrackerTest {
    @Test public void distinguishesAckNakAndUnknownTimeout() {
        AtomicLong now = new AtomicLong(1_000);
        AckTracker tracker = new AckTracker(10_000, now::get);

        tracker.sent(1, "!11111111");
        tracker.sent(2, "!22222222");
        tracker.sent(3, "!33333333");
        assertTrue(tracker.response(1, 0));
        assertTrue(tracker.response(2, 5));
        assertFalse(tracker.response(99, 0));

        AckTracker.Snapshot beforeTimeout = tracker.snapshot();
        assertEquals(1, beforeTimeout.pending);
        assertEquals(10_000, tracker.millisUntilNextExpiry());
        assertEquals(1, beforeTimeout.confirmed);
        assertEquals(1, beforeTimeout.failed);
        assertEquals(0, beforeTimeout.unknown);

        now.addAndGet(10_000);
        AckTracker.Snapshot afterTimeout = tracker.snapshot();
        assertEquals(0, afterTimeout.pending);
        assertEquals(1, afterTimeout.confirmed);
        assertEquals(1, afterTimeout.failed);
        assertEquals(1, afterTimeout.unknown);
        assertEquals(-1, tracker.millisUntilNextExpiry());
        assertTrue(afterTimeout.lastResult.contains("delivery unknown"));
    }

    @Test public void disconnectMakesPendingConfirmationsUnknown() {
        AckTracker tracker = new AckTracker();
        tracker.sent(0xffffffffL, "!aabbccdd");
        tracker.clearPending("radio disconnected");
        AckTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(0, snapshot.pending);
        assertEquals(1, snapshot.unknown);
        assertTrue(snapshot.lastResult.contains("radio disconnected"));
    }
}
