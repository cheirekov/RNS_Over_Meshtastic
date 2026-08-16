package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RnsTrafficTelemetryTest {
    private static byte[] frame(int type, int context, int bytes) {
        byte[] frame = new byte[Math.max(bytes, 19)];
        frame[0] = (byte) type;
        frame[18] = (byte) context;
        return frame;
    }

    @Test public void describesReticulumPacketTypeAndContext() {
        assertEquals("announce/none (64 B)", RnsTrafficTelemetry.describe(frame(1, 0, 64)));
        assertEquals("data/keepalive (19 B)", RnsTrafficTelemetry.describe(frame(0, 0xfa, 19)));

        byte[] headerTwo = new byte[35];
        headerTwo[0] = 0x43;
        headerTwo[34] = 0x05;
        assertEquals("proof/resource-proof (35 B)", RnsTrafficTelemetry.describe(headerTwo));
        byte[] opaqueIfac = frame(1, 0, 64);
        opaqueIfac[0] |= (byte) 0x80;
        assertEquals("opaque-ifac (64 B)", RnsTrafficTelemetry.describe(opaqueIfac));
        assertEquals("malformed (1 B)", RnsTrafficTelemetry.describe(new byte[1]));
    }

    @Test public void reportsFrameMixAndRollingRadioActivity() {
        RnsTrafficTelemetry telemetry = new RnsTrafficTelemetry();
        telemetry.recordFrame(true, frame(1, 0, 64));
        telemetry.recordFrame(true, frame(0, 0, 32));
        telemetry.recordFrame(false, frame(3, 0, 48));
        telemetry.recordRadioTx("data", 202, 1_000);
        telemetry.recordRadioTx("request", 5, 2_000);
        telemetry.recordRadioRx(202, 2_000);

        RnsTrafficTelemetry.Snapshot snapshot = telemetry.snapshot(2_500);
        assertTrue(snapshot.frameMix.contains("TX data 1, announce 1, link 0, proof 0"));
        assertTrue(snapshot.frameMix.contains("RX data 0, announce 0, link 0, proof 1"));
        assertTrue(snapshot.radioWindow.contains("TX 2 fragments/207 B (data 1, repair 1)"));
        assertTrue(snapshot.radioWindow.contains("RX 1 fragments/202 B"));

        snapshot = telemetry.snapshot(62_001);
        assertTrue(snapshot.radioWindow.contains("TX 0 fragments/0 B"));
        assertTrue(snapshot.radioWindow.contains("RX 0 fragments/0 B"));
    }

}
