package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class StreamFrameCodecTest {
    @Test public void roundTripsPhoneApiStreamWithWakePadding() {
        byte[] payload = new byte[] {1, 2, 3, 4};
        byte[] framed = StreamFrameCodec.encode(payload);
        byte[] input = new byte[framed.length + 3];
        input[0] = (byte) 0x94;
        input[1] = (byte) 0x94;
        input[2] = (byte) 0x94;
        System.arraycopy(framed, 0, input, 3, framed.length);
        StreamFrameCodec codec = new StreamFrameCodec();
        List<byte[]> decoded = codec.feed(input, input.length);
        assertEquals(1, decoded.size());
        assertArrayEquals(payload, decoded.get(0));
    }
}
