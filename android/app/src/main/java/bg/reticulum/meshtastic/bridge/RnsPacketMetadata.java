package bg.reticulum.meshtastic.bridge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Minimal, content-agnostic Reticulum header metadata used for link-layer routing. */
final class RnsPacketMetadata {
    static final int DATA = 0;
    static final int ANNOUNCE = 1;
    static final int LINK_REQUEST = 2;
    static final int PROOF = 3;

    // Reticulum Destination wire values. Keep these explicit: swapping SINGLE
    // and PLAIN silently turns normal LXMF traffic into Meshtastic broadcast.
    static final int SINGLE = 0;
    static final int GROUP = 1;
    static final int PLAIN = 2;
    static final int LINK = 3;

    private static final int HASH_BYTES = 16;
    private static final int LINK_PUBLIC_BYTES = 64;

    final int packetType;
    final int destinationType;
    final int context;
    final String destinationHash;
    final String packetHash;
    final String linkId;

    private RnsPacketMetadata(
            int packetType, int destinationType, int context,
            String destinationHash, String packetHash, String linkId) {
        this.packetType = packetType;
        this.destinationType = destinationType;
        this.context = context;
        this.destinationHash = destinationHash;
        this.packetHash = packetHash;
        this.linkId = linkId;
    }

    static boolean isOpaqueIfac(byte[] frame) {
        return frame != null && frame.length > 0 && (frame[0] & 0x80) != 0;
    }

    static RnsPacketMetadata parse(byte[] frame) {
        if (frame == null || frame.length < 19 || isOpaqueIfac(frame)) return null;
        int flags = frame[0] & 0xff;
        int headerType = (flags & 0x40) >>> 6;
        int destinationOffset = headerType == 0 ? 2 : 2 + HASH_BYTES;
        int contextOffset = destinationOffset + HASH_BYTES;
        if (frame.length <= contextOffset) return null;

        int packetType = flags & 0x03;
        int destinationType = (flags & 0x0c) >>> 2;
        String destinationHash = hex(frame, destinationOffset, HASH_BYTES);
        byte[] hashable = hashablePart(frame, headerType);
        String packetHash = truncatedSha256(hashable);
        String linkId = null;
        if (packetType == LINK_REQUEST) {
            int dataLength = frame.length - contextOffset - 1;
            int signallingBytes = Math.max(0, dataLength - LINK_PUBLIC_BYTES);
            byte[] linkHashable = signallingBytes == 0
                    ? hashable : Arrays.copyOf(hashable, hashable.length - signallingBytes);
            linkId = truncatedSha256(linkHashable);
        }
        return new RnsPacketMetadata(
                packetType, destinationType, frame[contextOffset] & 0xff,
                destinationHash, packetHash, linkId);
    }

    private static byte[] hashablePart(byte[] frame, int headerType) {
        int payloadOffset = headerType == 0 ? 2 : 2 + HASH_BYTES;
        byte[] result = new byte[1 + frame.length - payloadOffset];
        result[0] = (byte) (frame[0] & 0x0f);
        System.arraycopy(frame, payloadOffset, result, 1, frame.length - payloadOffset);
        return result;
    }

    private static String truncatedSha256(byte[] value) {
        try {
            return hex(Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(value), HASH_BYTES), 0, HASH_BYTES);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] value, int offset, int length) {
        char[] result = new char[length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < length; index++) {
            int item = value[offset + index] & 0xff;
            result[index * 2] = digits[item >>> 4];
            result[index * 2 + 1] = digits[item & 0x0f];
        }
        return new String(result);
    }
}
