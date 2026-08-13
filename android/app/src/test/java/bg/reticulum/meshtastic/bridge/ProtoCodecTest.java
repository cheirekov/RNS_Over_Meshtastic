package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtoCodecTest {
    @Test public void encodingMatchesOfficialMeshtasticPythonProtobuf() {
        byte[] encoded = ProtoCodec.toRadioPacket(
                0x8fd13c64L, 0xffffffffL, 0x12345678, 0, 3, false,
                true, new byte[] {'a', 'b', 'c'});
        assertArrayEquals(hex("0a200d643cd18f15ffffffff2209084c120361626348013578563412480358467803"), encoded);
        assertArrayEquals(hex("18ac9e04"), ProtoCodec.wantConfig(69420));
        assertArrayEquals(hex("18ad9e04"), ProtoCodec.wantConfig(69421));
        assertArrayEquals(hex("3a020802"), ProtoCodec.heartbeat(2));
    }

    @Test public void omitsMqttPermissionWhenRadioPolicyDisallowsIt() {
        byte[] encoded = ProtoCodec.toRadioPacket(
                0x8fd13c64L, 0xffffffffL, 0x12345678, 0, 3, false,
                false, new byte[] {'a', 'b', 'c'});
        assertArrayEquals(hex("0a1e0d643cd18f15ffffffff2207084c12036162633578563412480358467803"), encoded);
    }

    @Test public void decodesRadioMqttUplinkPolicy() {
        // FromRadio.config -> Config.lora -> LoRaConfig.config_ok_to_mqtt.
        ProtoCodec.FromRadio allowed = ProtoCodec.parseFromRadio(hex("2a053203c80601"));
        ProtoCodec.FromRadio denied = ProtoCodec.parseFromRadio(hex("2a053203c80600"));
        assertEquals(Boolean.TRUE, allowed.configOkToMqtt);
        assertEquals(Boolean.FALSE, denied.configOkToMqtt);
    }

    @Test public void decodesPort76PacketAndMyNodeInfo() {
        ProtoCodec.FromRadio packetMessage = ProtoCodec.parseFromRadio(
                hex("121e0d643cd18f15ffffffff2207084c12036162633578563412480358467803"));
        assertNotNull(packetMessage.packet);
        assertEquals(0x8fd13c64L, packetMessage.packet.source);
        assertEquals(0xffffffffL, packetMessage.packet.destination);
        assertEquals(76, packetMessage.packet.port);
        assertArrayEquals(new byte[] {'a', 'b', 'c'}, packetMessage.packet.payload);

        ProtoCodec.FromRadio identity = ProtoCodec.parseFromRadio(hex("1a0608e4f8c4fe08"));
        assertEquals(Long.valueOf(0x8fd13c64L), identity.myNodeNumber);

        ProtoCodec.FromRadio complete = ProtoCodec.parseFromRadio(hex("38ac9e04"));
        assertEquals(Long.valueOf(69420), complete.configCompleteId);
    }

    @Test public void acceptsPkiUnicastWithoutChannelContext() {
        ProtoCodec.FromRadio message = ProtoCodec.parseFromRadio(
                hex("121b0d6c33d18f15b8b3b3a12207084c12036162633578563412880101"));
        assertNotNull(message.packet);
        assertTrue(message.packet.pkiEncrypted);
        assertEquals(0, message.packet.channel);
        assertTrue(BridgeEngine.acceptsInbound(message.packet, 1, 0xa1b3b3b8L, true));
        assertFalse(BridgeEngine.acceptsInbound(message.packet, 1, 0x01020304L, true));
    }

    @Test public void decodesDeviceTransmitQueueStatus() {
        ProtoCodec.FromRadio message = ProtoCodec.parseFromRadio(hex("5a0a100f181020eefb96da05"));
        assertNotNull(message.queueStatus);
        assertEquals(15, message.queueStatus.free);
        assertEquals(16, message.queueStatus.maxLength);
        assertEquals(0x5b45bdeeL, message.queueStatus.meshPacketId);
        assertEquals(0, message.queueStatus.result);
    }

    @Test public void keepsChannelFilterForNonPkiPackets() {
        ProtoCodec.RadioPacket packet = new ProtoCodec.RadioPacket(
                0x8fd1336cL, 0xa1b3b3b8L, 0, ProtoCodec.RETICULUM_PORT, new byte[] {1}, false);
        assertTrue(BridgeEngine.acceptsInbound(packet, 0, 0xa1b3b3b8L, true));
        assertFalse(BridgeEngine.acceptsInbound(packet, 1, 0xa1b3b3b8L, true));
    }

    @Test public void rejectsSameChannelPacketAddressedElsewhereInUnicastMode() {
        ProtoCodec.RadioPacket packet = new ProtoCodec.RadioPacket(
                0x8fd1336cL, 0x01020304L, 0, ProtoCodec.RETICULUM_PORT, new byte[] {1}, false);
        assertFalse(BridgeEngine.acceptsInbound(packet, 0, 0xa1b3b3b8L, true));
        assertTrue(BridgeEngine.acceptsInbound(packet, 0, 0xa1b3b3b8L, false));
    }

    @Test public void decodesRoutingAckAndInboundRadioTelemetry() {
        ProtoCodec.FromRadio ack = ProtoCodec.parseFromRadio(hex(
                "12380d6c33d18f15b8b3b3a1220b08051202180035785634123504030201"
                        + "450000e8c048016089ffffffffffffffff0170017803880101a80105"));
        assertNotNull(ack.packet);
        assertEquals(ProtoCodec.ROUTING_PORT, ack.packet.port);
        assertEquals(0x12345678L, ack.packet.requestId);
        assertEquals(Integer.valueOf(0), ack.packet.routingError);
        assertEquals(0x01020304L, ack.packet.packetId);
        assertEquals(Float.valueOf(-7.25f), ack.packet.rxSnr);
        assertEquals(Integer.valueOf(-119), ack.packet.rxRssi);
        assertEquals(2, ack.packet.hopsAway());
        assertTrue(ack.packet.viaMqtt);
        assertEquals(5, ack.packet.transportMechanism);

        ProtoCodec.FromRadio data = ProtoCodec.parseFromRadio(hex(
                "12320d6c33d18f15b8b3b3a12207084c120361626335111111114500009040"
                        + "480260a8ffffffffffffffff017803880101a80101"));
        assertNotNull(data.packet);
        assertEquals(Float.valueOf(4.5f), data.packet.rxSnr);
        assertEquals(Integer.valueOf(-88), data.packet.rxRssi);
        assertEquals(1, data.packet.hopsAway());
        assertFalse(data.packet.viaMqtt);
        assertEquals(1, data.packet.transportMechanism);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
