package bg.reticulum.meshtastic.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct Meshtastic TCP PhoneAPI transport; no Meshtastic Android app is involved. */
final class TcpPhoneApiTransport implements RadioTransport {
    private static final int CONFIG_NONCE = 69420;
    private static final int NODE_INFO_NONCE = 69421;
    private final BridgeConfig config;
    private final AtomicInteger packetId = new AtomicInteger(new SecureRandom().nextInt());
    private final AtomicInteger heartbeat = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object writeLock = new Object();
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile long localNode;
    private volatile boolean mqttUplinkPermitted;
    private volatile boolean nodeInfoRequested;
    private Listener listener;
    private Thread worker;

    TcpPhoneApiTransport(BridgeConfig config) { this.config = config; }

    @Override public void start(Listener listener) {
        this.listener = listener;
        worker = new Thread(this::connectLoop, "meshtastic-tcp");
        worker.start();
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 20, 20, TimeUnit.SECONDS);
    }

    private void connectLoop() {
        while (!closed) {
            try (Socket connected = new Socket()) {
                listener.onRadioState(false, "Connecting to " + config.radioHost + ":" + config.radioPort);
                connected.connect(new InetSocketAddress(config.radioHost, config.radioPort), 15_000);
                connected.setKeepAlive(true);
                connected.setTcpNoDelay(true);
                socket = connected;
                output = connected.getOutputStream();
                nodeInfoRequested = false;
                synchronized (writeLock) {
                    output.write(new byte[] {(byte) 0x94, (byte) 0x94, (byte) 0x94, (byte) 0x94});
                    writePhoneApi(ProtoCodec.heartbeat(heartbeat.incrementAndGet()));
                }
                sleep(200);
                synchronized (writeLock) {
                    writePhoneApi(ProtoCodec.wantConfig(CONFIG_NONCE));
                }
                listener.onRadioState(true, "PhoneAPI TCP connected; reading node identity");
                readLoop(connected.getInputStream());
            } catch (Exception error) {
                if (!closed) listener.onRadioState(false, "TCP: " + useful(error));
            } finally {
                socket = null;
                output = null;
                localNode = 0;
                mqttUplinkPermitted = false;
                nodeInfoRequested = false;
            }
            if (!closed) sleep(3_000);
        }
    }

    private void readLoop(InputStream input) throws Exception {
        StreamFrameCodec codec = new StreamFrameCodec();
        byte[] buffer = new byte[1024];
        while (!closed) {
            int count = input.read(buffer);
            if (count < 0) throw new IllegalStateException("radio closed the TCP stream");
            for (byte[] protobuf : codec.feed(buffer, count)) handleFromRadio(protobuf);
        }
    }

    private void handleFromRadio(byte[] protobuf) {
        try {
            ProtoCodec.FromRadio message = ProtoCodec.parseFromRadio(protobuf);
            if (message.myNodeNumber != null) {
                localNode = message.myNodeNumber;
                listener.onLocalNode(localNode);
            }
            if (message.configOkToMqtt != null) {
                mqttUplinkPermitted = message.configOkToMqtt;
                listener.onRadioState(true, "Radio MQTT uplink permission: " + mqttUplinkPermitted);
            }
            if (message.configCompleteId != null
                    && message.configCompleteId == CONFIG_NONCE
                    && !nodeInfoRequested) {
                nodeInfoRequested = true;
                synchronized (writeLock) { writePhoneApi(ProtoCodec.wantConfig(NODE_INFO_NONCE)); }
                listener.onRadioState(true, "PhoneAPI config loaded; reading node database");
            } else if (message.configCompleteId != null && message.configCompleteId == NODE_INFO_NONCE) {
                listener.onRadioState(true, "PhoneAPI handshake complete as " + NodeId.format(localNode)
                        + "; MQTT uplink permission: " + mqttUplinkPermitted);
            }
            if (message.packet != null && message.packet.port == ProtoCodec.RETICULUM_PORT) {
                listener.onPacket(message.packet);
            }
        } catch (Exception ignored) {
            // Unknown or newer FromRadio variants are intentionally ignored.
        }
    }

    @Override public void send(byte[] payload, long destination) throws Exception {
        if (localNode == 0) throw new IllegalStateException("radio identity is not available yet");
        int id = packetId.updateAndGet(previous -> previous == -1 ? 1 : previous + 1);
        byte[] message = ProtoCodec.toRadioPacket(
                localNode, destination, id, config.channel, config.hops,
                config.wantAck && destination != NodeId.BROADCAST,
                mqttUplinkPermitted, payload);
        synchronized (writeLock) { writePhoneApi(message); }
    }

    @Override public boolean isReady() { return localNode != 0; }

    private void sendHeartbeat() {
        if (closed || output == null) return;
        try {
            synchronized (writeLock) { writePhoneApi(ProtoCodec.heartbeat(heartbeat.incrementAndGet())); }
        } catch (Exception error) {
            Socket current = socket;
            if (current != null) try { current.close(); } catch (Exception ignored) {}
        }
    }

    private void writePhoneApi(byte[] protobuf) throws Exception {
        OutputStream current = output;
        if (current == null) throw new IllegalStateException("PhoneAPI TCP is disconnected");
        current.write(StreamFrameCodec.encode(protobuf));
        current.flush();
    }

    @Override public void close() {
        closed = true;
        scheduler.shutdownNow();
        Socket current = socket;
        if (current != null) try { current.close(); } catch (Exception ignored) {}
        if (worker != null) worker.interrupt();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
