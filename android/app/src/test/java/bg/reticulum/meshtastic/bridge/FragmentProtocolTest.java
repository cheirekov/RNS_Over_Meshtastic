package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class FragmentProtocolTest {
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
}
