package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Reticulum TCPInterface HDLC byte stuffing (FLAG 0x7e, ESC 0x7d). */
final class HdlcCodec {
    static final int FLAG = 0x7e;
    static final int ESC = 0x7d;
    private final ByteArrayOutputStream current = new ByteArrayOutputStream();
    private boolean escaped;
    private boolean inside;

    static byte[] encode(byte[] frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(frame.length + 2);
        out.write(FLAG);
        for (byte value : frame) {
            int unsigned = value & 0xff;
            if (unsigned == FLAG || unsigned == ESC) {
                out.write(ESC);
                out.write(unsigned ^ 0x20);
            } else {
                out.write(unsigned);
            }
        }
        out.write(FLAG);
        return out.toByteArray();
    }

    synchronized List<byte[]> feed(byte[] bytes, int length) {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int value = bytes[i] & 0xff;
            if (value == FLAG) {
                if (inside && current.size() > 0) frames.add(current.toByteArray());
                current.reset();
                escaped = false;
                inside = true;
            } else if (!inside) {
                continue;
            } else if (escaped) {
                current.write(value ^ 0x20);
                escaped = false;
            } else if (value == ESC) {
                escaped = true;
            } else {
                current.write(value);
            }
        }
        return frames;
    }
}
