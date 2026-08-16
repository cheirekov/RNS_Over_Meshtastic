package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionTelemetryTest {
    @Test public void countsOnlyRealLifecycleTransitionsAndReportsMonotonicUptime() {
        SessionTelemetry telemetry = new SessionTelemetry(1_000, "deadbeef");
        telemetry.recordRadio(true);
        telemetry.recordRadio(true);
        telemetry.recordRadio(false);
        telemetry.recordRadio(true);
        telemetry.recordClient(false);
        telemetry.recordClient(false);
        telemetry.recordClient(true);

        assertEquals(
                "bridge session: deadbeef, uptime 1h 1m 1s; radio up/down 2/1; "
                        + "RNS client up/down 1/0",
                telemetry.describe(3_662_000));
    }

    @Test public void neverReportsNegativeUptime() {
        assertEquals(
                "bridge session: abc12345, uptime 0s; radio up/down 0/0; "
                        + "RNS client up/down 0/0",
                new SessionTelemetry(5_000, "abc12345").describe(1_000));
    }
}
