package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Minimal, dependency-free Meshtastic protobuf codec for PhoneAPI port 76 traffic. */
final class ProtoCodec {
    static final int RETICULUM_PORT = 76;
    static final int PRIORITY_RELIABLE = 70;

    static final class RadioPacket {
        final long source;
        final long destination;
        final int channel;
        final int port;
        final byte[] payload;

        RadioPacket(long source, long destination, int channel, int port, byte[] payload) {
            this.source = source;
            this.destination = destination;
            this.channel = channel;
            this.port = port;
            this.payload = payload;
        }
    }

    static final class FromRadio {
        final RadioPacket packet;
        final Long myNodeNumber;
        final Long configCompleteId;

        FromRadio(RadioPacket packet, Long myNodeNumber, Long configCompleteId) {
            this.packet = packet;
            this.myNodeNumber = myNodeNumber;
            this.configCompleteId = configCompleteId;
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

    static byte[] toRadioPacket(
            long source,
            long destination,
            int packetId,
            int channel,
            int hops,
            boolean wantAck,
            byte[] payload) {
        Writer data = new Writer();
        data.varintField(1, RETICULUM_PORT);
        data.bytesField(2, payload);

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
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 2 && wire == 2) packet = parseMeshPacket(in.readBytes());
            else if (field == 3 && wire == 2) myNode = parseMyNodeInfo(in.readBytes());
            else if (field == 7 && wire == 0) configComplete = in.readVarint() & 0xffffffffL;
            else in.skip(wire);
        }
        return new FromRadio(packet, myNode, configComplete);
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
            } else in.skip(wire);
        }
        if (payload == null) return null;
        return new RadioPacket(source, destination, channel, port, payload);
    }

    private static Data parseData(byte[] encoded) {
        Reader in = new Reader(encoded);
        int port = 0;
        byte[] payload = null;
        while (in.hasRemaining()) {
            int tag = in.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) port = (int) in.readVarint();
            else if (field == 2 && wire == 2) payload = in.readBytes();
            else in.skip(wire);
        }
        return new Data(port, payload);
    }

    private static final class Data {
        final int port;
        final byte[] payload;
        Data(int port, byte[] payload) { this.port = port; this.payload = payload; }
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
            out.writeBytes(value);
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
