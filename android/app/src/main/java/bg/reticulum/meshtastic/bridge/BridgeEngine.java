package bg.reticulum.meshtastic.bridge;

import android.content.Context;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeEngine implements AutoCloseable, RadioTransport.Listener, ReticulumTcpServer.Listener {
    private static final int MAX_QUEUED_FRAMES = 64;
    private static final int MAX_QUEUE_HORIZON_MILLIS = 120_000;
    interface StatusListener { void onStatus(String status); }

    private final BridgeConfig config;
    private final FragmentProtocol fragments;
    private final RadioTransport radio;
    private final ReticulumTcpServer reticulum;
    private final StatusListener status;
    private final TransmitScheduler transmit;
    private final int maxQueuedFragments;
    private final int maxQueuedBytes;
    private final AtomicLong rnsToMesh = new AtomicLong();
    private final AtomicLong meshToRns = new AtomicLong();
    private volatile String radioState = "radio starting";
    private volatile String clientState = "Reticulum starting";
    private volatile String deviceQueueState = "device TX queue: unknown";
    private volatile String lastError = "";
    private volatile long localNode;

    BridgeEngine(Context context, BridgeConfig config, StatusListener status) {
        this.config = config;
        this.status = status;
        this.fragments = new FragmentProtocol(config.fragmentBody);
        this.radio = config.transport.equals("ble")
                ? new BlePhoneApiTransport(context, config)
                : new TcpPhoneApiTransport(config);
        this.reticulum = new ReticulumTcpServer(config.localPort, this);
        this.maxQueuedFragments = fragmentQueueLimit(config.txIntervalMillis);
        this.maxQueuedBytes = maxQueuedFragments * (config.fragmentBody + 2);
        int reservedControlFragments = Math.max(1, Math.min(8, maxQueuedFragments / 4));
        this.transmit = new TransmitScheduler(
                config.txIntervalMillis,
                MAX_QUEUED_FRAMES, maxQueuedFragments, maxQueuedBytes,
                8, reservedControlFragments, reservedControlFragments * (config.fragmentBody + 2),
                this::sendTransmission,
                new TransmitScheduler.Listener() {
                    @Override public void onChanged(TransmitScheduler.Snapshot ignored) { publish(); }

                    @Override public void onFailure(FragmentProtocol.Transmission tx, Exception error) {
                        reportError("Meshtastic TX failed (" + tx.reason + "): " + useful(error));
                    }
                });
    }

    void start() {
        reticulum.start();
        radio.start(this);
        publish();
    }

    @Override public void onRadioState(boolean connected, String detail) {
        radioState = detail;
        publish();
    }

    @Override public void onLocalNode(long nodeNumber) {
        localNode = nodeNumber;
        String transportName = config.transport.equals("ble") ? "BLE" : "TCP";
        radioState = transportName + " Meshtastic ready as " + NodeId.format(nodeNumber);
        publish();
    }

    @Override public void onPacket(ProtoCodec.RadioPacket packet) {
        if (!acceptsInbound(packet, config.channel, localNode)) return;
        String source = NodeId.format(packet.source);
        if (!config.acceptsSource(source)) return;
        try {
            FragmentProtocol.Result result = fragments.receive(source, packet.payload);
            for (byte[] frame : result.frames) {
                reticulum.sendFrame(frame);
                meshToRns.incrementAndGet();
            }
            if (!queueTransmissions(result.transmissions, false)) {
                reportError("Meshtastic repair queue full; dropped fragment control traffic");
            }
            publish();
        } catch (Exception error) {
            reportError("Dropped port 76 packet from " + source + ": " + useful(error));
        }
    }

    static boolean acceptsInbound(ProtoCodec.RadioPacket packet, int configuredChannel, long localNode) {
        if (packet.port != ProtoCodec.RETICULUM_PORT) return false;
        if (packet.channel == configuredChannel) return true;

        // Meshtastic 2.7 automatically upgrades unicast DMs to PKI. PKI packets
        // have no channel context and are reported by PhoneAPI with channel 0,
        // even if the sender selected a non-zero local channel slot. Accept only
        // authenticated PKI unicast addressed to this radio; do not weaken the
        // configured-channel filter for ordinary channel-encrypted packets.
        return packet.pkiEncrypted && localNode != 0 && packet.destination == localNode;
    }

    @Override public void onClientState(boolean connected, String detail) {
        clientState = detail;
        publish();
    }

    @Override public void onFrame(byte[] frame) {
        try {
            List<FragmentProtocol.Transmission> transmissions = fragments.encode(frame, config.outboundDestination());
            if (queueTransmissions(transmissions, true)) rnsToMesh.incrementAndGet();
            publish();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            reportError("Could not queue Reticulum frame: " + useful(error));
        }
    }

    private boolean queueTransmissions(
            List<FragmentProtocol.Transmission> transmissions, boolean waitForCapacity)
            throws InterruptedException {
        return transmit.enqueue(transmissions, waitForCapacity);
    }

    private void sendTransmission(FragmentProtocol.Transmission transmission) throws Exception {
        if (!awaitRadioIdentity()) {
            throw new IllegalStateException("radio identity was not received within 45 seconds");
        }
        radio.send(transmission.payload, NodeId.parse(transmission.destination));
    }

    private boolean awaitRadioIdentity() throws InterruptedException {
        if (radio.isReady()) return true;
        status.onStatus("Reticulum frame queued; waiting for "
                + config.transport.toUpperCase(Locale.ROOT) + " radio identity…");
        for (int attempt = 0; attempt < 90; attempt++) {
            if (radio.isReady()) return true;
            Thread.sleep(500);
        }
        return false;
    }

    @Override public void onQueueStatus(int free, int max, int result) {
        deviceQueueState = "device TX queue: " + free + "/" + max + " free"
                + (result == 0 ? "" : ", result=" + result);
        publish();
    }

    private void publish() {
        TransmitScheduler.Snapshot queue = transmit.snapshot();
        long dropped = queue.rejectedFrames + queue.failedFrames;
        String summary =
                radioState + "\n" + clientState
                        + "\nmode: " + config.mode + ", local channel slot: " + config.channel
                        + "\nframes: RNS→mesh " + rnsToMesh.get() + ", mesh→RNS " + meshToRns.get()
                        + "\nradio queue: " + queue.frames + " frames, " + queue.fragments
                        + "/" + maxQueuedFragments + " fragments, " + queue.bytes
                        + "/" + maxQueuedBytes + " bytes"
                        + ", drain ≈" + formatDuration(queue.estimatedDrainMillis)
                        + "\n" + deviceQueueState
                        + "; backpressure: " + queue.backpressureEvents + ", dropped: " + dropped;
        if (!lastError.isEmpty()) summary += "\nlast error: " + lastError;
        status.onStatus(summary);
    }

    private void reportError(String detail) {
        lastError = detail;
        publish();
    }

    @Override public void close() {
        reticulum.close();
        transmit.close();
        radio.close();
    }

    private static String formatDuration(long millis) {
        long seconds = (millis + 999) / 1_000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    private static int fragmentQueueLimit(int intervalMillis) {
        if (intervalMillis <= 0) return 256;
        return Math.max(4, Math.min(256, MAX_QUEUE_HORIZON_MILLIS / intervalMillis));
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
