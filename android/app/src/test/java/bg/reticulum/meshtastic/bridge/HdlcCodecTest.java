package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class HdlcCodecTest {
    @Test public void escapesAndDecodesReticulumFramesAcrossReads() {
        byte[] frame = new byte[] {1, 0x7e, 2, 0x7d, 3};
        byte[] encoded = HdlcCodec.encode(frame);
        assertArrayEquals(new byte[] {0x7e, 1, 0x7d, 0x5e, 2, 0x7d, 0x5d, 3, 0x7e}, encoded);

        HdlcCodec decoder = new HdlcCodec();
        List<byte[]> first = decoder.feed(encoded, 4);
        byte[] remainder = java.util.Arrays.copyOfRange(encoded, 4, encoded.length);
        List<byte[]> second = decoder.feed(remainder, remainder.length);
        assertEquals(0, first.size());
        assertEquals(1, second.size());
        assertArrayEquals(frame, second.get(0));
    }
}
