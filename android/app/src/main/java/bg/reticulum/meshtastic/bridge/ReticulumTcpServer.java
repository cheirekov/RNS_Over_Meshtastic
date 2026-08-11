package bg.reticulum.meshtastic.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Loopback-only Reticulum TCPServerInterface peer for Sideband/Columba. */
final class ReticulumTcpServer implements AutoCloseable {
    interface Listener {
        void onClientState(boolean connected, String detail);
        void onFrame(byte[] frame);
    }

    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();
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
            listening.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            listener.onClientState(false, "Reticulum listening on 127.0.0.1:" + port);
            while (!closed) {
                Socket accepted = listening.accept();
                accepted.setTcpNoDelay(true);
                replaceClient(accepted);
                Thread reader = new Thread(() -> readClient(accepted), "reticulum-tcp-client");
                reader.start();
            }
        } catch (Exception error) {
            if (!closed) listener.onClientState(false, "Reticulum TCP: " + useful(error));
        }
    }

    private synchronized void replaceClient(Socket accepted) throws Exception {
        Socket previous = client;
        if (previous != null) try { previous.close(); } catch (Exception ignored) {}
        client = accepted;
        output = accepted.getOutputStream();
        listener.onClientState(true, "Reticulum client connected from loopback");
    }

    private void readClient(Socket connected) {
        HdlcCodec codec = new HdlcCodec();
        byte[] buffer = new byte[2048];
        try (InputStream input = connected.getInputStream()) {
            while (!closed && connected == client) {
                int count = input.read(buffer);
                if (count < 0) break;
                for (byte[] frame : codec.feed(buffer, count)) listener.onFrame(frame);
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) {
                if (client == connected) {
                    client = null;
                    output = null;
                    listener.onClientState(false, "Reticulum client disconnected; still listening");
                }
            }
            try { connected.close(); } catch (Exception ignored) {}
        }
    }

    void sendFrame(byte[] frame) throws Exception {
        synchronized (writeLock) {
            OutputStream current = output;
            if (current == null) throw new IllegalStateException("no Reticulum TCP client is connected");
            current.write(HdlcCodec.encode(frame));
            current.flush();
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
