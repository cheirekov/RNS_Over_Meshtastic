package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class AdaptiveAckPolicyTest {
    @Test public void suppressesAckAfterUnconfirmedPendingBurstAndReprobes() {
        AtomicLong now = new AtomicLong(100_000);
        AdaptiveAckPolicy policy = new AdaptiveAckPolicy(now::get, 60_000);

        assertTrue(policy.permits(snapshot(0, 0, 0, 11)));
        assertFalse(policy.permits(snapshot(0, 0, 0, 12)));
        assertFalse(policy.permits(snapshot(3, 0, 0, 0)));
        now.addAndGet(60_001);
        assertTrue(policy.permits(snapshot(3, 0, 0, 0)));
    }

    @Test public void keepsAckWhenConfirmationRateIsHealthy() {
        AdaptiveAckPolicy policy = new AdaptiveAckPolicy(() -> 100_000, 60_000);
        assertTrue(policy.permits(snapshot(6, 0, 2, 0)));
    }

    @Test public void suppressesAckWhenResolvedConfirmationRateIsPoor() {
        AdaptiveAckPolicy policy = new AdaptiveAckPolicy(() -> 100_000, 60_000);
        assertFalse(policy.permits(snapshot(1, 0, 7, 0)));
    }

    private static AckTracker.Snapshot snapshot(
            long confirmed, long failed, long unknown, int pending) {
        return new AckTracker.Snapshot(pending, confirmed, failed, unknown, "test");
    }
}
