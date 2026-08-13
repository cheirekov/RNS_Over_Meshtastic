package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BridgeConfigTest {
    private static BridgeConfig broadcast(String allowedSources) {
        return new BridgeConfig(
                "tcp", "192.0.2.1", 4403, "", 7822,
                0, 3, "broadcast", "", 200, 2000, false, allowedSources);
    }

    @Test public void emptyBroadcastAllowlistAcceptsAnySource() {
        assertTrue(broadcast("").acceptsSource("!aabbcc11"));
    }

    @Test public void broadcastAllowlistNormalisesAndFiltersSources() {
        BridgeConfig config = broadcast(" !AABBCC11, !11223344 ");
        assertTrue(config.acceptsSource("!aabbcc11"));
        assertTrue(config.acceptsSource("!11223344"));
        assertFalse(config.acceptsSource("!55667788"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedBroadcastAllowlistIsRejected() {
        broadcast("not-a-node");
    }
}
