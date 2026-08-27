package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Minimal, dependency-free Meshtastic protobuf codec for PhoneAPI port 76 traffic. */
final class ProtoCodec {
    static final int RETICULUM_PORT = 76;
    static final int ROUTING_PORT = 5;
    static final int PRIORITY_RELIABLE = 70;
    // Data.bitfield bit 0 explicitly carries the radio owner's MQTT-uplink
    // policy. Mirror config_ok_to_mqtt instead of granting it unconditionally.
    static final int OK_TO_MQTT = 1;

    static final class RadioPacket {
        final long source;
        final long destination;
        final int channel;
        final int port;
        final byte[] payload;
        final boolean pkiEncrypted;
        final long packetId;
        final long requestId;
        final Integer routingError;
        final Float rxSnr;
        final Integer rxRssi;
        final int hopLimit;
        final int hopStart;
        final boolean viaMqtt;
        final int transportMechanism;

        RadioPacket(long source, long destination, int channel, int port, byte[] payload, boolean pkiEncrypted) {
            this(source, destination, channel, port, payload, pkiEncrypted,
                    0, 0, null, null, null, 0, 0, false, 0);
        }

        RadioPacket(
                long source, long destination, int channel, int port, byte[] payload, boolean pkiEncrypted,
                long packetId, long requestId, Integer routingError, Float rxSnr, Integer rxRssi,
                int hopLimit, int hopStart, boolean viaMqtt, int transportMechanism) {
            this.source = source;
            this.destination = destination;
            this.channel = channel;
            this.port = port;
            this.payload = payload;
            this.pkiEncrypted = pkiEncrypted;
            this.packetId = packetId;
            this.requestId = requestId;
            this.routingError = routingError;
            this.rxSnr = rxSnr;
            this.rxRssi = rxRssi;
            this.hopLimit = hopLimit;
            this.hopStart = hopStart;
            this.viaMqtt = viaMqtt;
            this.transportMechanism = transportMechanism;
        }

        int hopsAway() { return hopStart >= hopLimit ? hopStart - hopLimit : -1; }
    }

    static final class FromRadio {
        final RadioPacket packet;
        final Long myNodeNumber;
        final Long configCompleteId;
        final Boolean configOkToMqtt;
        final QueueStatus queueStatus;

        FromRadio(
                RadioPacket packet, Long myNodeNumber, Long configCompleteId,
                Boolean configOkToMqtt, QueueStatus queueStatus) {
            this.packet = packet;
            this.myNodeNumber = myNodeNumber;
            this.configCompleteId = configCompleteId;
            this.configOkToMqtt = configOkToMqtt;
            this.queueStatus = queueStatus;
        }
    }

    static final class QueueStatus {
        final int result;
        final int free;
        final int maxLength;
        final long meshPacketId;

        QueueStatus(int result, int free, int maxLength, long meshPacketId) {
            this.result = result;
            this.free = free;
            this.maxLength = maxLength;
            this.meshPacketId = meshPacketId;
        }
    }

    static byte[] wantConfig(int nonce) {
        Writer out = new Writer();
        out.varintField(3, nonce & 0xffffffffL);
        return out.bytes();
    }

    static byte[] heartbeat(int nonce) {
        Writer nested = new Writer();
        nested.varintField(1, nonce & 0xffffffffL);
        Writer out = new Writer();
        out.bytesField(7, nested.bytes());
        return out.bytes();
    }

    static boolean isBridgePort(int port) { return port == RETICULUM_PORT || port == ROUTING_PORT; }

    static String routingErrorName(int error) {
        return switch (error) {
            case 0 -> "NONE";
            case 1 -> "NO_ROUTE";
            case 2 -> "GOT_NAK";
            case 3 -> "TIMEOUT";
            case 4 -> "NO_INTERFACE";
            case 5 -> "MAX_RETRANSMIT";
            case 6 -> "NO_CHANNEL";
            case 7 -> "TOO_LARGE";
            case 8 -> "NO_RESPONSE";
            case 9 -> "DUTY_CYCLE_LIMIT";
            case 32 -> "BAD_REQUEST";
            case 33 -> "NOT_AUTHORIZED";
            case 34 -> "PKI_FAILED";
            case 35 -> "PKI_UNKNOWN_PUBKEY";
            case 36 -> "ADMIN_BAD_SESSION_KEY";
            case 37 -> "ADMIN_PUBLIC_KEY_UNAUTHORIZED";
            case 38 -> "RATE_LIMIT_EXCEEDED";
            case 39 -> "PKI_SEND_FAIL_PUBLIC_KEY";
            default -> "ERROR_" + error;
        };
    }

    /**
     * QueueStatus.res uses the firmware ErrorCode/ERRNO namespace, not the
     * Routing.Error enum. In particular, 35 is the successful
     * ERRNO_SHOULD_RELEASE result, not Routing.Error.PKI_UNKNOWN_PUBKEY.
     */
    static boolean queueStatusSucceeded(int result) {
        return result == 0 || result == 35;
    }

    static String queueStatusResultName(int result) {
        return switch (result) {
            case 0 -> "NONE";
            // Firmware 2.7.26 aliases these ERRNO values with Routing.Error.
            case 32 -> "BAD_REQUEST_OR_ERRNO_UNKNOWN";
            case 33 -> "NOT_AUTHORIZED_OR_ERRNO_NO_INTERFACES";
            case 34 -> "PKI_FAILED_OR_ERRNO_DISABLED";
            case 35 -> "ERRNO_SHOULD_RELEASE";
            default -> routingErrorName(result);
        };
    }

    static byte[] toRadioPacket(
            long source,
            long destination,
            int packetId,
            int channel,
            int hops,
            boolean wantAck,
            boolean okToMqtt,
            byte[] payload) {
        Writer data = new Writer();
        data.varintField(1, RETICULUM_PORT);
        data.bytesField(2, payload);
        if (okToMqtt) data.varintField(9, OK_TO_MQTT);

        Writer packet = new Writer();
        packet.fixed32Field(1, source);
        packet.fixed32Field(2, destination);
        if (channel != 0) packet.varintField(3, channel);
        packet.bytesField(4, data.bytes());
        packet.fixed32Field(6, packetId & 0xffffffffL);
        if (hops != 0) packet.varintField(9, hops);
        if (wantAck) packet.varintField(10, 1);
        packet.varintField(11, PRIORITY_RELIABLE);
        if (hops != 0) packet.varintField(15, hops);

        Writer out = new Writer();
        out.bytesField(1, packet.bytes());
        return out.bytes();
    }

    static FromRadio parseFromRadio(byte[] encoded) {
        Reader in = new Reader(encoded);
        RadioPacket packet = null;
        Long myNode = null;
        Long configComplete = null;
        Boolean configOkToMqtt = null;
        QueueStatus queueStatus = null;
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 2 && wire == 2) packet = parseMeshPacket(in.readBytes());
            else if (field == 3 && wire == 2) myNode = parseMyNodeInfo(in.readBytes());
            else if (field == 5 && wire == 2) configOkToMqtt = parseConfigOkToMqtt(in.readBytes());
            else if (field == 7 && wire == 0) configComplete = in.readVarint() & 0xffffffffL;
            else if (field == 11 && wire == 2) queueStatus = parseQueueStatus(in.readBytes());
            else in.skip(wire);
        }
        return new FromRadio(packet, myNode, configComplete, configOkToMqtt, queueStatus);
    }

    private static QueueStatus parseQueueStatus(byte[] encoded) {
        Reader in = new Reader(encoded);
        int result = 0;
        int free = 0;
        int maxLength = 0;
        long meshPacketId = 0;
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) result = (int) in.readVarint();
            else if (field == 2 && wire == 0) free = (int) in.readVarint();
            else if (field == 3 && wire == 0) maxLength = (int) in.readVarint();
            else if (field == 4 && wire == 0) meshPacketId = in.readVarint() & 0xffffffffL;
            else in.skip(wire);
        }
        return new QueueStatus(result, free, maxLength, meshPacketId);
    }

    private static Boolean parseConfigOkToMqtt(byte[] encoded) {
        Reader config = new Reader(encoded);
        while (config.hasRemaining()) {
            int tag = config.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 6 && wire == 2) {
                Reader lora = new Reader(config.readBytes());
                while (lora.hasRemaining()) {
                    int loraTag = lora.readTag();
                    int loraField = loraTag >>> 3;
                    int loraWire = loraTag & 7;
                    if (loraField == 105 && loraWire == 0) return lora.readVarint() != 0;
                    lora.skip(loraWire);
                }
                return false;
            }
            config.skip(wire);
        }
        return null;
    }

    private static Long parseMyNodeInfo(byte[] encoded) {
        Reader in = new Reader(encoded);
        while (in.hasRemaining()) {
            int tag = in.readTag();
            if ((tag >>> 3) == 1 && (tag & 7) == 0) return in.readVarint() & 0xffffffffL;
            in.skip(tag & 7);
        }
        return null;
    }

    private static RadioPacket parseMeshPacket(byte[] encoded) {
        Reader in = new Reader(encoded);
        long source = 0;
        long destination = 0xffffffffL;
        int channel = 0;
        int port = 0;
        byte[] payload = null;
        boolean pkiEncrypted = false;
        long packetId = 0;
        long requestId = 0;
        Integer routingError = null;
        Float rxSnr = null;
        Integer rxRssi = null;
        int hopLimit = 0;
        int hopStart = 0;
        boolean viaMqtt = false;
        int transportMechanism = 0;
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 5) source = in.readFixed32();
            else if (field == 2 && wire == 5) destination = in.readFixed32();
            else if (field == 3 && wire == 0) channel = (int) in.readVarint();
            else if (field == 4 && wire == 2) {
                Data decoded = parseData(in.readBytes());
                port = decoded.port;
                payload = decoded.payload;
                requestId = decoded.requestId;
                if (port == ROUTING_PORT && payload != null) routingError = parseRoutingError(payload);
            } else if (field == 6 && wire == 5) packetId = in.readFixed32();
            else if (field == 8 && wire == 5) rxSnr = Float.intBitsToFloat((int) in.readFixed32());
            else if (field == 9 && wire == 0) hopLimit = (int) in.readVarint();
            else if (field == 12 && wire == 0) rxRssi = (int) in.readVarint();
            else if (field == 14 && wire == 0) viaMqtt = in.readVarint() != 0;
            else if (field == 15 && wire == 0) hopStart = (int) in.readVarint();
            else if (field == 17 && wire == 0) pkiEncrypted = in.readVarint() != 0;
            else if (field == 21 && wire == 0) transportMechanism = (int) in.readVarint();
            else in.skip(wire);
        }
        if (payload == null) return null;
        return new RadioPacket(source, destination, channel, port, payload, pkiEncrypted,
                packetId, requestId, routingError, rxSnr, rxRssi,
                hopLimit, hopStart, viaMqtt, transportMechanism);
    }

    private static Data parseData(byte[] encoded) {
        Reader in = new Reader(encoded);
        int port = 0;
        byte[] payload = null;
        long requestId = 0;
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) port = (int) in.readVarint();
            else if (field == 2 && wire == 2) payload = in.readBytes();
            else if (field == 6 && wire == 5) requestId = in.readFixed32();
            else in.skip(wire);
        }
        return new Data(port, payload, requestId);
    }

    private static Integer parseRoutingError(byte[] encoded) {
        Reader in = new Reader(encoded);
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 3 && wire == 0) return (int) in.readVarint();
            in.skip(wire);
        }
        return null;
    }

    private static final class Data {
        final int port;
        final byte[] payload;
        final long requestId;
        Data(int port, byte[] payload, long requestId) {
            this.port = port;
            this.payload = payload;
            this.requestId = requestId;
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void varintField(int field, long value) {
            varint(((long) field << 3));
            varint(value);
        }

        void fixed32Field(int field, long value) {
            varint(((long) field << 3) | 5);
            out.write((int) value & 0xff);
            out.write((int) (value >>> 8) & 0xff);
            out.write((int) (value >>> 16) & 0xff);
            out.write((int) (value >>> 24) & 0xff);
        }

        void bytesField(int field, byte[] value) {
            varint(((long) field << 3) | 2);
            varint(value.length);
            out.write(value, 0, value.length);
        }

        void varint(long value) {
            while ((value & ~0x7fL) != 0) {
                out.write(((int) value & 0x7f) | 0x80);
                value >>>= 7;
            }
            out.write((int) value);
        }

        byte[] bytes() { return out.toByteArray(); }
    }

    private static final class Reader {
        private final byte[] input;
        private int position;

        Reader(byte[] input) { this.input = input; }
        boolean hasRemaining() { return position < input.length; }

        int readTag() {
            long tag = readVarint();
            if (tag == 0 || tag > Integer.MAX_VALUE) throw malformed("invalid tag");
            return (int) tag;
        }

        long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                require(1);
                int value = input[position++] & 0xff;
                result |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) return result;
            }
            throw malformed("varint is too long");
        }

        long readFixed32() {
            require(4);
            long value = (input[position] & 0xffL)
                    | ((input[position + 1] & 0xffL) << 8)
                    | ((input[position + 2] & 0xffL) << 16)
                    | ((input[position + 3] & 0xffL) << 24);
            position += 4;
            return value;
        }

        byte[] readBytes() {
            long rawLength = readVarint();
            if (rawLength > Integer.MAX_VALUE) throw malformed("length is too large");
            int length = (int) rawLength;
            require(length);
            byte[] value = Arrays.copyOfRange(input, position, position + length);
            position += length;
            return value;
        }

        void skip(int wire) {
            switch (wire) {
                case 0 -> readVarint();
                case 1 -> { require(8); position += 8; }
                case 2 -> { int size = Math.toIntExact(readVarint()); require(size); position += size; }
                case 5 -> { require(4); position += 4; }
                default -> throw malformed("unsupported wire type " + wire);
            }
        }

        private void require(int count) {
            if (count < 0 || position + count > input.length) throw malformed("truncated protobuf");
        }

        private IllegalArgumentException malformed(String message) {
            return new IllegalArgumentException(message + " at byte " + position);
        }
    }
}
