package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CompanionApiServerTest {
    private static BridgeConfig config() {
        return new BridgeConfig(
                "tcp", "192.0.2.1", 4403, "", 7822,
                1, 3, "auto_multi_peer", "", 200, 2000, "adaptive", "",
                "force_off", "constrained_auto");
    }

    @Test public void capabilitiesDescribeTheConstrainedTransport() {
        String value = CompanionApiServer.capabilitiesJson(7822, 7823);
        assertTrue(value.contains("\"schema\":1"));
        assertTrue(value.contains("\"constrained_transport\":true"));
        assertTrue(value.contains("\"realtime_supported\":false"));
        assertTrue(value.contains("\"meshtastic_portnum\":76"));
        assertFalse(value.toLowerCase().contains("password"));
    }

    @Test public void trafficSnapshotParsesDiagnosticsCounters() {
        String diagnostics = "radio up\nReticulum client connected\n"
                + "TX RNS→mesh: 12 frames / 27 fragments\n"
                + "RX mesh→bridge: 9 frames / 17 fragments\n"
                + "RNS frame mix: TX data 1, announce 0, link 0, proof 0, opaque-ifac 0, malformed 0, 120 B, last data/none (20 B); RX data 1, announce 0, link 0, proof 0, opaque-ifac 0, malformed 0, 90 B, last data/none (20 B)\n"
                + "radio queue: 2 frames, 3/4 fragments, 404/808 bytes";
        String value = CompanionApiServer.trafficJson(diagnostics);
        assertTrue(value.contains("\"tx_rns_frames\":12"));
        assertTrue(value.contains("\"rx_meshtastic_fragments\":17"));
        assertTrue(value.contains("\"queue_fragments\":3"));
        assertTrue(value.contains("\"queue_byte_limit\":808"));
        assertTrue(value.contains("\"lora\":{\"rx_bytes\":90,\"tx_bytes\":120"));
    }

    @Test public void statusContainsNoRadioCredentials() {
        String value = CompanionApiServer.statusJson(config(), "radio ready\nRNS ready");
        assertTrue(value.contains("\"transport\":\"tcp\""));
        assertTrue(value.contains("\"topology\":\"auto_multi_peer\""));
        assertFalse(value.contains("192.0.2.1"));
    }

    @Test public void peersAreReturnedAsVersionedRouteObjects() {
        String value = CompanionApiServer.peersJson(
                "peer routing: 2/32 peers, 9/512 routes\n"
                        + "peer table: !A1B3B3B8 (6 routes, seen 12s ago), "
                        + "!8fd13c64 (3 routes, seen 4s ago)");
        assertTrue(value.contains("\"peer_count\":2"));
        assertTrue(value.contains("\"route_count\":9"));
        assertTrue(value.contains("\"peer\":\"!a1b3b3b8\""));
        assertTrue(value.contains("\"routes\":6"));
        assertTrue(value.contains("\"last_seen_seconds\":4"));
    }
}
