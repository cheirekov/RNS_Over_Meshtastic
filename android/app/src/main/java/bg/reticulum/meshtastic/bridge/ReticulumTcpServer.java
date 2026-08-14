package bg.reticulum.meshtastic.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback-only Reticulum TCPServerInterface peer for Sideband/Columba. */
final class ReticulumTcpServer implements AutoCloseable {
    private static final int RECEIVE_BUFFER_BYTES = 1024;
    private static final int READ_CHUNK_BYTES = 600;
    static final int MAX_SPOOLED_FRAMES = 32;
    static final int MAX_SPOOLED_BYTES = 64 * 1024;
    static final long SPOOL_TTL_MILLIS = 5 * 60 * 1000L;

    enum Delivery { DELIVERED, SPOOLED, DUPLICATE, REJECTED }

    static final class Snapshot {
        final boolean connected;
        final long deliveredDirect;
        final InboundFrameSpool.Snapshot spool;

        Snapshot(boolean connected, long deliveredDirect, InboundFrameSpool.Snapshot spool) {
            this.connected = connected;
            this.deliveredDirect = deliveredDirect;
            this.spool = spool;
        }

        long deliveredTotal() { return deliveredDirect + spool.replayedFrames; }
    }
    interface Listener {
        void onClientState(boolean connected, String detail);
        void onFrame(byte[] frame);
    }

    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final InboundFrameSpool spool = new InboundFrameSpool(
            MAX_SPOOLED_FRAMES, MAX_SPOOLED_BYTES, SPOOL_TTL_MILLIS);
    private final AtomicLong deliveredDirect = new AtomicLong();
    private volatile boolean closed;
    private volatile ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream output;
    private Thread acceptThread;

    ReticulumTcpServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    void start() {
        acceptThread = new Thread(this::acceptLoop, "reticulum-tcp-server");
        acceptThread.start();
    }

    private void acceptLoop() {
        try (ServerSocket listening = new ServerSocket()) {
            server = listening;
            listening.setReuseAddress(true);
            // Set before bind so accepted connections advertise a small
            // receive window from the initial TCP handshake.
            listening.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
            listening.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            listener.onClientState(false, "Reticulum listening on 127.0.0.1:" + port);
            while (!closed) {
                Socket accepted = listening.accept();
                accepted.setTcpNoDelay(true);
                // Keep the TCP receive window close to the bounded radio
                // scheduler. A large kernel buffer would acknowledge many
                // RNS frames before application-level backpressure can act.
                accepted.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
                replaceClient(accepted);
                Thread reader = new Thread(() -> readClient(accepted), "reticulum-tcp-client");
                reader.start();
            }
        } catch (Exception error) {
            if (!closed) listener.onClientState(false, "Reticulum TCP: " + useful(error));
        }
    }

    private void replaceClient(Socket accepted) throws Exception {
        Socket previous;
        int replayed;
        synchronized (writeLock) {
            synchronized (this) {
                previous = client;
                client = accepted;
                output = accepted.getOutputStream();
            }
            if (previous != null) try { previous.close(); } catch (Exception ignored) {}
            replayed = flushSpool(accepted);
        }
        if (client == accepted) {
            listener.onClientState(true, "Reticulum client connected from loopback"
                    + (replayed == 0 ? "" : "; replayed " + replayed + " buffered frame(s)"));
        }
    }

    private void readClient(Socket connected) {
        HdlcCodec codec = new HdlcCodec();
        byte[] buffer = new byte[READ_CHUNK_BYTES];
        try (InputStream input = connected.getInputStream()) {
            while (!closed && connected == client) {
                int count = input.read(buffer);
                if (count < 0) break;
                for (byte[] frame : codec.feed(buffer, count)) listener.onFrame(frame);
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (writeLock) {
                synchronized (this) {
                    if (client == connected) {
                        client = null;
                        output = null;
                        listener.onClientState(false, "Reticulum client disconnected; still listening");
                    }
                }
            }
            try { connected.close(); } catch (Exception ignored) {}
        }
    }

    Delivery sendFrame(byte[] frame) {
        synchronized (writeLock) {
            OutputStream current = output;
            if (current == null) return spool(frame);
            try {
                current.write(HdlcCodec.encode(frame));
                current.flush();
                deliveredDirect.incrementAndGet();
                return Delivery.DELIVERED;
            } catch (Exception error) {
                disconnectBrokenClient(current, error);
                return spool(frame);
            }
        }
    }

    Snapshot snapshot() {
        return new Snapshot(output != null, deliveredDirect.get(), spool.snapshot());
    }

    private int flushSpool(Socket expectedClient) {
        int replayed = 0;
        synchronized (writeLock) {
            OutputStream current = output;
            if (current == null || client != expectedClient) return 0;
            try {
                byte[] frame;
                while ((frame = spool.peek()) != null) {
                    current.write(HdlcCodec.encode(frame));
                    current.flush();
                    spool.removeReplayed();
                    replayed++;
                }
            } catch (Exception error) {
                disconnectBrokenClient(current, error);
            }
        }
        return replayed;
    }

    private Delivery spool(byte[] frame) {
        return switch (spool.offer(frame)) {
            case QUEUED -> Delivery.SPOOLED;
            case DUPLICATE -> Delivery.DUPLICATE;
            case REJECTED -> Delivery.REJECTED;
        };
    }

    private void disconnectBrokenClient(OutputStream failedOutput, Exception error) {
        Socket failed = null;
        synchronized (this) {
            if (output == failedOutput) {
                failed = client;
                client = null;
                output = null;
            }
        }
        if (failed != null) {
            try { failed.close(); } catch (Exception ignored) {}
            listener.onClientState(false, "Reticulum client write failed; frame buffered: " + useful(error));
        }
    }

    @Override public void close() {
        closed = true;
        Socket currentClient = client;
        ServerSocket currentServer = server;
        if (currentClient != null) try { currentClient.close(); } catch (Exception ignored) {}
        if (currentServer != null) try { currentServer.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
