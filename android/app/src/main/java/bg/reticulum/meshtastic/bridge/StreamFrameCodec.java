package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Meshtastic serial/TCP framing: 0x94 0xc3, big-endian length, protobuf. */
final class StreamFrameCodec {
    static final int MAX_PAYLOAD = 512;
    private int state;
    private int length;
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

    static byte[] encode(byte[] protobuf) {
        if (protobuf.length > MAX_PAYLOAD) throw new IllegalArgumentException("PhoneAPI protobuf exceeds 512 bytes");
        byte[] framed = new byte[protobuf.length + 4];
        framed[0] = (byte) 0x94;
        framed[1] = (byte) 0xc3;
        framed[2] = (byte) (protobuf.length >>> 8);
        framed[3] = (byte) protobuf.length;
        System.arraycopy(protobuf, 0, framed, 4, protobuf.length);
        return framed;
    }

    synchronized List<byte[]> feed(byte[] bytes, int count) {
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xff;
            switch (state) {
                case 0 -> { if (value == 0x94) state = 1; }
                case 1 -> {
                    if (value == 0xc3) state = 2;
                    else if (value != 0x94) state = 0;
                }
                case 2 -> { length = value << 8; state = 3; }
                case 3 -> {
                    length |= value;
                    payload.reset();
                    if (length < 0 || length > MAX_PAYLOAD) state = 0;
                    else if (length == 0) { packets.add(new byte[0]); state = 0; }
                    else state = 4;
                }
                default -> {
                    payload.write(value);
                    if (payload.size() == length) {
                        packets.add(payload.toByteArray());
                        payload.reset();
                        state = 0;
                    }
                }
            }
        }
        return packets;
    }
}
