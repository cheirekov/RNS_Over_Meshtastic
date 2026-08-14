package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BridgeEngineQueueTest {
    @Test public void defaultPacingKeepsOnlyOneMtuFrameNearTheTcpBoundary() {
        // 4 total fragments, with one reserved for repair control traffic,
        // leaves room for one maximum-size three-fragment RNS frame.
        assertEquals(4, BridgeEngine.fragmentQueueLimit(2_000));
    }

    @Test public void queueHorizonScalesWithPacingAndStaysBounded() {
        assertEquals(8, BridgeEngine.fragmentQueueLimit(1_000));
        assertEquals(4, BridgeEngine.fragmentQueueLimit(4_000));
        assertEquals(256, BridgeEngine.fragmentQueueLimit(0));
    }

    @Test public void namesDutyCycleAndRateLimitDeviceRejections() {
        assertEquals("DUTY_CYCLE_LIMIT", BridgeEngine.routingErrorName(9));
        assertEquals("RATE_LIMIT_EXCEEDED", BridgeEngine.routingErrorName(38));
        assertEquals("UNKNOWN", BridgeEngine.routingErrorName(999));
    }

    @Test public void distinguishesMqttOriginFromFinalLoraArrival() {
        ProtoCodec.RadioPacket relayed = packet(true, 1);
        ProtoCodec.RadioPacket directMqtt = packet(true, 5);
        ProtoCodec.RadioPacket directLora = packet(false, 1);

        assertEquals("MQTT→LoRa", BridgeEngine.transportLabel(relayed));
        assertEquals("MQTT", BridgeEngine.transportLabel(directMqtt));
        assertEquals("LoRa", BridgeEngine.transportLabel(directLora));
        assertEquals("unicast UDP", BridgeEngine.transportLabel(packet(false, 8)));
    }

    private static ProtoCodec.RadioPacket packet(boolean viaMqtt, int transport) {
        return new ProtoCodec.RadioPacket(
                1, 2, 0, ProtoCodec.RETICULUM_PORT, new byte[] {1}, true,
                0, 0, null, null, null, 3, 2, viaMqtt, transport);
    }
}
