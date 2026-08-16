package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class RnsPacketMetadataTest {
    @Test public void parsesHeaderOneAndHeaderTwoDestinations() {
        byte[] headerOne = packet(0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.ANNOUNCE, 0x44);
        RnsPacketMetadata one = RnsPacketMetadata.parse(headerOne);
        assertNotNull(one);
        assertEquals(RnsPacketMetadata.ANNOUNCE, one.packetType);
        assertEquals("11111111111111111111111111111111", one.destinationHash);
        assertEquals(0x44, one.context);

        byte[] headerTwo = packet(1, RnsPacketMetadata.LINK, RnsPacketMetadata.DATA, 0xfa);
        RnsPacketMetadata two = RnsPacketMetadata.parse(headerTwo);
        assertNotNull(two);
        assertEquals(RnsPacketMetadata.LINK, two.destinationType);
        assertEquals("11111111111111111111111111111111", two.destinationHash);
        assertEquals(0xfa, two.context);
        assertFalse(one.packetHash.equals(two.packetHash));
    }

    @Test public void refusesToParseIfacCiphertextAsAnRnsHeader() {
        byte[] opaque = packet(0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.ANNOUNCE, 0);
        opaque[0] |= (byte) 0x80;
        assertTrue(RnsPacketMetadata.isOpaqueIfac(opaque));
        assertNull(RnsPacketMetadata.parse(opaque));
    }

    @Test public void derivesStableLinkIdFromLinkRequestPublicMaterial() {
        byte[] request = packet(0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.LINK_REQUEST, 0);
        request = Arrays.copyOf(request, 19 + 64 + 3);
        Arrays.fill(request, 19, 19 + 64, (byte) 0x5a);
        request[83] = 1;
        request[84] = 2;
        request[85] = 3;
        RnsPacketMetadata metadata = RnsPacketMetadata.parse(request);
        assertNotNull(metadata.linkId);

        request[83] = 9;
        request[84] = 8;
        request[85] = 7;
        assertEquals(metadata.linkId, RnsPacketMetadata.parse(request).linkId);
    }

    static byte[] packet(int headerType, int destinationType, int packetType, int context) {
        int destinationOffset = headerType == 0 ? 2 : 18;
        byte[] frame = new byte[destinationOffset + 16 + 1 + 8];
        frame[0] = (byte) ((headerType << 6) | (destinationType << 2) | packetType);
        frame[1] = 0;
        if (headerType == 1) Arrays.fill(frame, 2, 18, (byte) 0x22);
        Arrays.fill(frame, destinationOffset, destinationOffset + 16, (byte) 0x11);
        frame[destinationOffset + 16] = (byte) context;
        Arrays.fill(frame, destinationOffset + 17, frame.length, (byte) 0x33);
        return frame;
    }
}
