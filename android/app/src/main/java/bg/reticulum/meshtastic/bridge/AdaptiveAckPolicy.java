package bg.reticulum.meshtastic.bridge;

import java.util.function.LongSupplier;

/** Backs off optional Meshtastic routing ACKs when their return path is unhealthy. */
final class AdaptiveAckPolicy {
    private static final int MAX_PENDING_WITHOUT_CONFIRMATION = 12;
    private static final int MIN_RESOLVED_SAMPLE = 8;
    private static final long DEFAULT_SUPPRESSION_MILLIS = 300_000;

    private final LongSupplier clock;
    private final long suppressionMillis;
    private long baselineConfirmed;
    private long baselineFailed;
    private long baselineUnknown;
    private long suppressedUntil;
    private String reason = "learning";

    AdaptiveAckPolicy() { this(System::currentTimeMillis, DEFAULT_SUPPRESSION_MILLIS); }

    AdaptiveAckPolicy(LongSupplier clock, long suppressionMillis) {
        if (suppressionMillis <= 0) throw new IllegalArgumentException("suppression must be positive");
        this.clock = clock;
        this.suppressionMillis = suppressionMillis;
    }

    synchronized boolean permits(AckTracker.Snapshot snapshot) {
        long now = clock.getAsLong();
        if (suppressedUntil > now) return false;
        if (suppressedUntil != 0) {
            baselineConfirmed = snapshot.confirmed;
            baselineFailed = snapshot.failed;
            baselineUnknown = snapshot.unknown;
            suppressedUntil = 0;
            reason = "probing after cooldown";
        }

        long confirmed = snapshot.confirmed - baselineConfirmed;
        long negative = snapshot.failed - baselineFailed + snapshot.unknown - baselineUnknown;
        long resolved = confirmed + negative;
        if (confirmed == 0 && snapshot.pending >= MAX_PENDING_WITHOUT_CONFIRMATION) {
            suppress(now, "no confirmations with " + snapshot.pending + " pending");
            return false;
        }
        if (resolved >= MIN_RESOLVED_SAMPLE && confirmed * 4 < resolved) {
            suppress(now, "confirmation rate below 25% (" + confirmed + "/" + resolved + ")");
            return false;
        }
        return true;
    }

    synchronized String describe(AckTracker.Snapshot snapshot) {
        long remaining = suppressedUntil - clock.getAsLong();
        if (remaining > 0) {
            long seconds = (remaining + 999) / 1_000;
            return "suppressed for " + seconds + "s: " + reason;
        }
        long confirmed = snapshot.confirmed - baselineConfirmed;
        long negative = snapshot.failed - baselineFailed + snapshot.unknown - baselineUnknown;
        return reason + "; sample " + confirmed + " confirmed/" + negative
                + " negative, " + snapshot.pending + " pending";
    }

    private void suppress(long now, String why) {
        suppressedUntil = now + suppressionMillis;
        reason = why;
    }
}
