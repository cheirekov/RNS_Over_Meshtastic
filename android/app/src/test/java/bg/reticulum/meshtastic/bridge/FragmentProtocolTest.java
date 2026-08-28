package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class FragmentProtocolTest {
    @Test public void matchesFrozenPort76BinaryVector() {
        FragmentProtocol protocol = new FragmentProtocol(8);
        byte[] frame = new byte[20];
        for (int index = 0; index < frame.length; index++) frame[index] = (byte) index;
        List<FragmentProtocol.Transmission> fragments = protocol.encode(frame, "!aabbcc11");

        assertEquals(3, fragments.size());
        assertArrayEquals(hex("00010001020304050607"), fragments.get(0).payload);
        assertArrayEquals(hex("000208090a0b0c0d0e0f"), fragments.get(1).payload);
        assertArrayEquals(hex("00fd10111213"), fragments.get(2).payload);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    @Test public void reassemblesOutOfOrderAndRequestsMissingFragment() {
        FragmentProtocol sender = new FragmentProtocol(3);
        FragmentProtocol receiver = new FragmentProtocol(3);
        byte[] frame = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};
        List<FragmentProtocol.Transmission> tx = sender.encode(frame, "!12345678");
        assertEquals(3, tx.size());

        receiver.receive("!abcdef01", tx.get(0).payload);
        FragmentProtocol.Result afterLast = receiver.receive("!abcdef01", tx.get(2).payload);
        assertEquals(1, afterLast.transmissions.size());
        assertArrayEquals(new byte[] {'R', 'E', 'Q', tx.get(1).payload[0], 2}, afterLast.transmissions.get(0).payload);
        FragmentProtocol.Snapshot incomplete = receiver.snapshot();
        assertEquals(1, incomplete.activeAssemblies);
        assertEquals(0, incomplete.awaitingFinal);
        assertEquals(1, incomplete.missingFragments);
        assertEquals(1, incomplete.repairRequests);

        FragmentProtocol.Result complete = receiver.receive("!abcdef01", tx.get(1).payload);
        assertEquals(1, complete.frames.size());
        assertArrayEquals(frame, complete.frames.get(0));
        FragmentProtocol.Snapshot finished = receiver.snapshot();
        assertEquals(0, finished.activeAssemblies);
        assertEquals(1, finished.completedFrames);
    }

    @Test public void broadcastCacheAnswersUnicastRetransmissionRequest() {
        FragmentProtocol sender = new FragmentProtocol(3);
        List<FragmentProtocol.Transmission> tx = sender.encode(new byte[] {1, 2, 3, 4}, "^all");
        byte index = tx.get(0).payload[0];
        FragmentProtocol.Result result = sender.receive("!abcdef01", new byte[] {'R', 'E', 'Q', index, 1});
        assertEquals(1, result.transmissions.size());
        assertTrue(result.transmissions.get(0).reason.equals("retransmit"));
        assertArrayEquals(tx.get(0).payload, result.transmissions.get(0).payload);
        assertEquals(1, sender.snapshot().retransmissions);
    }

    @Test public void reportsAssemblyThatHasNotSeenItsFinalFragment() {
        FragmentProtocol sender = new FragmentProtocol(3);
        FragmentProtocol receiver = new FragmentProtocol(3);
        List<FragmentProtocol.Transmission> tx = sender.encode(
                new byte[] {0, 1, 2, 3, 4, 5, 6}, "!12345678");

        receiver.receive("!abcdef01", tx.get(0).payload);

        FragmentProtocol.Snapshot snapshot = receiver.snapshot();
        assertEquals(1, snapshot.activeAssemblies);
        assertEquals(1, snapshot.awaitingFinal);
        assertEquals(0, snapshot.missingFragments);
    }

    @Test public void periodicRepairRecoversMissingFinalFragment() {
        AtomicLong now = new AtomicLong(100_000);
        FragmentProtocol sender = new FragmentProtocol(10, 60_000, 5_000, now::get);
        FragmentProtocol receiver = new FragmentProtocol(10, 60_000, 5_000, now::get);
        byte[] frame = "final-fragment!".getBytes();
        List<FragmentProtocol.Transmission> tx = sender.encode(frame, "^all");

        receiver.receive("!abcdef01", tx.get(0).payload);
        now.addAndGet(4_000);
        receiver.receive("!abcdef01", tx.get(0).payload); // duplicate is not progress
        now.addAndGet(1_001);
        FragmentProtocol.Result repair = receiver.pollRepairs(1);

        assertEquals(1, repair.transmissions.size());
        assertArrayEquals(
                new byte[] {'R', 'E', 'Q', tx.get(0).payload[0], 0},
                repair.transmissions.get(0).payload);
        FragmentProtocol.Result retransmit = sender.receive(
                "!abcdef01", repair.transmissions.get(0).payload);
        assertEquals(1, retransmit.transmissions.size());
        assertArrayEquals(tx.get(tx.size() - 1).payload, retransmit.transmissions.get(0).payload);
        FragmentProtocol.Result recovered = receiver.receive(
                "!abcdef01", retransmit.transmissions.get(0).payload);
        assertEquals(1, recovered.frames.size());
        assertArrayEquals(frame, recovered.frames.get(0));
        receiver.receive("!abcdef01", retransmit.transmissions.get(0).payload);
        assertEquals(0, receiver.snapshot().activeAssemblies);
        assertEquals(1, receiver.snapshot().finalRepairRequests);
    }

    @Test public void periodicRepairsAreBoundedAndBackOff() {
        AtomicLong now = new AtomicLong(100_000);
        FragmentProtocol sender = new FragmentProtocol(10, 300_000, 5_000, now::get);
        FragmentProtocol receiver = new FragmentProtocol(10, 300_000, 5_000, now::get);
        FragmentProtocol.Transmission first = sender.encode(
                "final-fragment!".getBytes(), "^all").get(0);
        receiver.receive("!abcdef01", first.payload);

        now.addAndGet(5_001);
        assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        now.addAndGet(9_999);
        assertEquals(0, receiver.pollRepairs(1).transmissions.size());
        now.addAndGet(2);
        assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        now.addAndGet(19_999);
        assertEquals(0, receiver.pollRepairs(1).transmissions.size());
        now.addAndGet(2);
        assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        assertEquals(1, receiver.snapshot().cappedRepairs);
        now.addAndGet(100_000);
        assertEquals(0, receiver.pollRepairs(1).transmissions.size());
    }

    @Test public void newFragmentProgressCanContinueAfterRepairCap() {
        AtomicLong now = new AtomicLong(100_000);
        FragmentProtocol sender = new FragmentProtocol(10, 300_000, 5_000, now::get);
        FragmentProtocol receiver = new FragmentProtocol(10, 300_000, 5_000, now::get);
        List<FragmentProtocol.Transmission> tx = sender.encode(
                "0123456789abcdefghijKLMNO".getBytes(), "^all");
        receiver.receive("!abcdef01", tx.get(0).payload);

        for (long delay : new long[] {5_001, 10_001, 20_001}) {
            now.addAndGet(delay);
            assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        }

        FragmentProtocol.Result progressed = receiver.receive(
                "!abcdef01", tx.get(tx.size() - 1).payload);
        assertEquals(1, progressed.transmissions.size());
        assertArrayEquals(
                new byte[] {'R', 'E', 'Q', tx.get(0).payload[0], 2},
                progressed.transmissions.get(0).payload);
    }

    @Test public void globalRepairBudgetThrottlesAStormAndRecoversAfterWindow() {
        AtomicLong now = new AtomicLong(100_000);
        FragmentProtocol sender = new FragmentProtocol(10, 300_000, 1_000, now::get);
        FragmentProtocol receiver = new FragmentProtocol(
                10, 300_000, 1_000, now::get, 2, 60_000);

        for (int i = 0; i < 3; i++) {
            FragmentProtocol.Transmission first = sender.encode(
                    ("incomplete-frame-" + i).getBytes(), "^all").get(0);
            receiver.receive("!abcdef01", first.payload);
        }
        now.addAndGet(1_001);

        assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        assertEquals(0, receiver.pollRepairs(1).transmissions.size());
        FragmentProtocol.Snapshot limited = receiver.snapshot();
        assertEquals(2, limited.repairBudgetUsed);
        assertEquals(2, limited.repairBudgetLimit);
        assertTrue(limited.throttledRepairs > 0);

        now.addAndGet(60_001);
        assertEquals(1, receiver.pollRepairs(1).transmissions.size());
        assertEquals(1, receiver.snapshot().repairBudgetUsed);
    }

    @Test public void cappedMissingAssemblyStaysIncompleteInsteadOfThrowing() {
        AtomicLong now = new AtomicLong(100_000);
        FragmentProtocol sender = new FragmentProtocol(10, 300_000, 1_000, now::get);
        FragmentProtocol receiver = new FragmentProtocol(10, 300_000, 1_000, now::get);
        List<FragmentProtocol.Transmission> tx = sender.encode(
                "0123456789abcdefghijKLMNO".getBytes(), "^all");
        receiver.receive("!abcdef01", tx.get(0).payload);
        receiver.receive("!abcdef01", tx.get(tx.size() - 1).payload);

        for (long delay : new long[] {2_001, 4_001}) {
            now.addAndGet(delay);
            receiver.pollRepairs(1);
        }
        now.addAndGet(8_001);
        receiver.pollRepairs(1);

        FragmentProtocol.Result duplicateFinal = receiver.receive(
                "!abcdef01", tx.get(tx.size() - 1).payload);
        assertEquals(0, duplicateFinal.frames.size());
        assertEquals(1, receiver.snapshot().activeAssemblies);
    }
}
