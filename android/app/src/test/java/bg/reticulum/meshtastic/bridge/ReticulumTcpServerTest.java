package bg.reticulum.meshtastic.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class ReticulumTcpServerTest {
    @Test public void replaysFrameQueuedBeforeClientConnects() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        ReticulumTcpServer server = new ReticulumTcpServer(port, new ReticulumTcpServer.Listener() {
            @Override public void onClientState(boolean isConnected, String detail) {
                if (detail.contains("listening")) listening.countDown();
                if (isConnected) connected.countDown();
            }

            @Override public void onFrame(byte[] frame) {}
        });
        byte[] expected = new byte[] {1, 0x7e, 2, 0x7d, 3};

        try {
            server.start();
            assertTrue(listening.await(2, TimeUnit.SECONDS));
            assertEquals(ReticulumTcpServer.Delivery.SPOOLED, server.sendFrame(expected));

            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(2_000);
                assertTrue(connected.await(2, TimeUnit.SECONDS));
                HdlcCodec codec = new HdlcCodec();
                byte[] buffer = new byte[64];
                List<byte[]> decoded = List.of();
                InputStream input = client.getInputStream();
                while (decoded.isEmpty()) {
                    int count = input.read(buffer);
                    if (count < 0) fail("Reticulum TCP connection closed before spool replay");
                    decoded = codec.feed(buffer, count);
                }
                assertArrayEquals(expected, decoded.get(0));
            }

            ReticulumTcpServer.Snapshot snapshot = server.snapshot();
            assertEquals(0, snapshot.spool.frames);
            assertEquals(1, snapshot.spool.replayedFrames);
            assertEquals(1, snapshot.deliveredTotal());
        } finally {
            server.close();
        }
    }
}
