package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class RnsPeerRouterTest {
    @Test public void learnsAnnounceDestinationAndRoutesKnownSinglePackets() {
        RnsPeerRouter router = new RnsPeerRouter();
        byte[] announce = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.ANNOUNCE, 0);
        String destination = RnsPacketMetadata.parse(announce).destinationHash;
        router.observeInbound("!aabbcc11", announce);

        byte[] data = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.DATA, 0);
        overwriteDestination(data, destination);
        RnsPeerRouter.Decision decision = router.destinationFor(data);
        assertEquals("!aabbcc11", decision.destination);
        assertFalse(decision.isBroadcast());
    }

    @Test public void routesExplicitProofBackToOriginalPacketSource() {
        RnsPeerRouter router = new RnsPeerRouter();
        byte[] inbound = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.DATA, 0);
        String packetHash = RnsPacketMetadata.parse(inbound).packetHash;
        router.observeInbound("!11223344", inbound);

        byte[] proof = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.PROOF, 0);
        overwriteDestination(proof, packetHash);
        assertEquals("!11223344", router.destinationFor(proof).destination);
    }

    @Test public void learnsLinkIdAndLaterLinkDestination() {
        RnsPeerRouter router = new RnsPeerRouter();
        byte[] request = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.LINK_REQUEST, 0);
        String linkId = RnsPacketMetadata.parse(request).linkId;
        router.observeInbound("!55667788", request);

        byte[] linkPacket = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.LINK, RnsPacketMetadata.PROOF, 0xfd);
        overwriteDestination(linkPacket, linkId);
        assertEquals("!55667788", router.destinationFor(linkPacket).destination);
    }

    @Test public void broadcastsAnnouncesUnknownGroupsAndOpaqueIfac() {
        RnsPeerRouter router = new RnsPeerRouter();
        assertTrue(router.destinationFor(RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.ANNOUNCE, 0)).isBroadcast());
        assertTrue(router.destinationFor(RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.GROUP, RnsPacketMetadata.DATA, 0)).isBroadcast());
        assertTrue(router.destinationFor(RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.DATA, 0)).isBroadcast());
        byte[] opaque = RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.DATA, 0);
        opaque[0] |= (byte) 0x80;
        assertTrue(router.destinationFor(opaque).isBroadcast());
        assertEquals(1, router.snapshot().opaqueIfacBroadcasts);
    }

    @Test public void expiresRoutesAndPeersAfterTwentyFourHours() {
        AtomicLong clock = new AtomicLong(1_000);
        RnsPeerRouter router = new RnsPeerRouter(clock::get);
        router.observeInbound("!aabbcc11", RnsPacketMetadataTest.packet(
                0, RnsPacketMetadata.SINGLE, RnsPacketMetadata.ANNOUNCE, 0));
        assertEquals(1, router.snapshot().peers);
        clock.addAndGet(24L * 60 * 60 * 1000 + 1);
        assertEquals(0, router.snapshot().peers);
        assertEquals(0, router.snapshot().routes);
    }

    private static void overwriteDestination(byte[] frame, String hex) {
        int offset = (frame[0] & 0x40) == 0 ? 2 : 18;
        for (int index = 0; index < 16; index++) {
            frame[offset + index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
    }
}
