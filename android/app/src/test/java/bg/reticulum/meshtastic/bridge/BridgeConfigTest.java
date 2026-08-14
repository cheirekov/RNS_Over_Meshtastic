package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BridgeConfigTest {
    private static BridgeConfig broadcast(String allowedSources) {
        return new BridgeConfig(
                "tcp", "192.0.2.1", 4403, "", 7822,
                0, 3, "broadcast", "", 200, 2000, "off", allowedSources,
                "inherit", "constrained_auto");
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

    @Test public void mqttForwardingCanOnlyBeInheritedOrReduced() {
        BridgeConfig inherited = broadcast("");
        assertTrue(inherited.allowsMqttForwarding(true));
        assertFalse(inherited.allowsMqttForwarding(false));

        BridgeConfig forcedOff = new BridgeConfig(
                "tcp", "192.0.2.1", 4403, "", 7822,
                0, 3, "broadcast", "", 200, 2000, "off", "",
                "force_off", "constrained_auto");
        assertFalse(forcedOff.allowsMqttForwarding(true));
    }

    @Test public void criticalAckProtectsFinalAndRepairFragmentsOnly() {
        FragmentProtocol.Transmission middle = new FragmentProtocol.Transmission(
                "!aabbccdd", new byte[] {7, 1, 42}, "data");
        FragmentProtocol.Transmission last = new FragmentProtocol.Transmission(
                "!aabbccdd", new byte[] {7, -2, 42}, "data");
        FragmentProtocol.Transmission only = new FragmentProtocol.Transmission(
                "!aabbccdd", new byte[] {7, -1, 42}, "data");
        FragmentProtocol.Transmission request = new FragmentProtocol.Transmission(
                "!aabbccdd", new byte[] {'R', 'E', 'Q', 7, 1}, "request");
        FragmentProtocol.Transmission retransmit = new FragmentProtocol.Transmission(
                "!aabbccdd", new byte[] {7, 1, 42}, "retransmit");

        assertFalse(BridgeEngine.requestsRadioAck("critical", middle, false));
        assertTrue(BridgeEngine.requestsRadioAck("critical", last, false));
        assertFalse(BridgeEngine.requestsRadioAck("critical", only, false));
        assertTrue(BridgeEngine.requestsRadioAck("critical", request, false));
        assertTrue(BridgeEngine.requestsRadioAck("critical", retransmit, false));
        assertTrue(BridgeEngine.requestsRadioAck("adaptive", retransmit, false));
        assertTrue(BridgeEngine.requestsRadioAck("adaptive", last, false));
        assertTrue(BridgeEngine.requestsRadioAck("all", middle, false));
        assertFalse(BridgeEngine.requestsRadioAck("off", last, false));
    }

    @Test public void broadcastNeverRequestsRadioAck() {
        FragmentProtocol.Transmission last = new FragmentProtocol.Transmission(
                "^all", new byte[] {7, -1, 42}, "data");
        assertFalse(BridgeEngine.requestsRadioAck("critical", last, true));
        assertFalse(BridgeEngine.requestsRadioAck("all", last, true));
        FragmentProtocol.Transmission request = new FragmentProtocol.Transmission(
                "!aabbccdd", new byte[] {'R', 'E', 'Q', 7, 1}, "request");
        assertFalse(BridgeEngine.requestsRadioAck("critical", request, true));
    }
}
