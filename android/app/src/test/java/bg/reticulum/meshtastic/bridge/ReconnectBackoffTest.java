package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReconnectBackoffTest {
    @Test public void doublesCapsAndResets() {
        ReconnectBackoff backoff = new ReconnectBackoff(3_000, 60_000);
        assertEquals(3_000, backoff.nextDelayMillis());
        assertEquals(6_000, backoff.nextDelayMillis());
        assertEquals(12_000, backoff.nextDelayMillis());
        assertEquals(24_000, backoff.nextDelayMillis());
        assertEquals(48_000, backoff.nextDelayMillis());
        assertEquals(60_000, backoff.nextDelayMillis());
        assertEquals(60_000, backoff.nextDelayMillis());
        backoff.reset();
        assertEquals(3_000, backoff.nextDelayMillis());
    }
}
