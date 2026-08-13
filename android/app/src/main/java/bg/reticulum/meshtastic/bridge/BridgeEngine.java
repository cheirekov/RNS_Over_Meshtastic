package bg.reticulum.meshtastic.bridge;

import android.content.Context;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private final AtomicLong fragmentsToMesh = new AtomicLong();
    private final AtomicLong fragmentsFromMesh = new AtomicLong();
    private final AckTracker acknowledgements = new AckTracker();
    private final ScheduledExecutorService ackScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object ackScheduleLock = new Object();
    private ScheduledFuture<?> ackSweep;
    private volatile String radioState = "radio starting";
    private volatile String clientState = "Reticulum starting";
    private volatile String deviceQueueState = "device TX queue: unknown";
    private volatile String lastRxState = "none";
    private volatile String lastError = "";
    private volatile long localNode;
    private volatile boolean closed;

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
        if (!connected) acknowledgements.clearPending("radio disconnected");
        scheduleAckSweep();
        publish();
    }

    @Override public void onLocalNode(long nodeNumber) {
        localNode = nodeNumber;
        String transportName = config.transport.equals("ble") ? "BLE" : "TCP";
        radioState = transportName + " Meshtastic ready as " + NodeId.format(nodeNumber);
        publish();
    }

    @Override public void onPacket(ProtoCodec.RadioPacket packet) {
        String source = NodeId.format(packet.source);
        if (!config.acceptsSource(source)) return;
        if (packet.port == ProtoCodec.ROUTING_PORT) {
            handleRoutingResponse(packet, source);
            return;
        }
        boolean requireLocalDestination = config.mode.equals("gateway_unicast");
        if (!acceptsInbound(packet, config.channel, localNode, requireLocalDestination)) return;
        fragmentsFromMesh.incrementAndGet();
        lastRxState = describeInbound(packet, source);
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

    static boolean acceptsInbound(
            ProtoCodec.RadioPacket packet, int configuredChannel, long localNode,
            boolean requireLocalDestination) {
        if (packet.port != ProtoCodec.RETICULUM_PORT) return false;
        if (requireLocalDestination && (localNode == 0 || packet.destination != localNode)) return false;
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
        long packetId = radio.send(transmission.payload, NodeId.parse(transmission.destination));
        fragmentsToMesh.incrementAndGet();
        if (config.wantAck && !transmission.destination.equals("^all")) {
            acknowledgements.sent(packetId, transmission.destination);
            scheduleAckSweep();
        }
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
        AckTracker.Snapshot ack = acknowledgements.snapshot();
        long dropped = queue.rejectedFrames + queue.failedFrames;
        String summary =
                radioState + "\n" + clientState
                        + "\ntopology: " + topologySummary() + ", local channel slot: " + config.channel
                        + "\nTX RNS→mesh: " + rnsToMesh.get() + " frames / "
                        + fragmentsToMesh.get() + " fragments"
                        + "\nRX mesh→RNS: " + meshToRns.get() + " frames / "
                        + fragmentsFromMesh.get() + " fragments; last: " + lastRxState
                        + "\nradio queue: " + queue.frames + " frames, " + queue.fragments
                        + "/" + maxQueuedFragments + " fragments, " + queue.bytes
                        + "/" + maxQueuedBytes + " bytes"
                        + ", drain ≈" + formatDuration(queue.estimatedDrainMillis)
                        + "\n" + deviceQueueState
                        + "; backpressure: " + queue.backpressureEvents + ", dropped: " + dropped;
        if (config.wantAck && !config.mode.equals("broadcast")) {
            summary += "\nradio ACK: " + ack.confirmed + " confirmed, " + ack.failed
                    + " NAK, " + ack.unknown + " unknown, " + ack.pending
                    + " pending; last: " + ack.lastResult;
        } else {
            summary += "\nradio ACK: disabled (Reticulum/LXMF proofs remain authoritative)";
        }
        if (!lastError.isEmpty()) summary += "\nlast error: " + lastError;
        status.onStatus(summary);
    }

    private void handleRoutingResponse(ProtoCodec.RadioPacket packet, String source) {
        if (packet.routingError == null || packet.requestId == 0 || localNode == 0
                || packet.destination != localNode) return;
        if (acknowledgements.response(packet.requestId, packet.routingError)) {
            scheduleAckSweep();
            publish();
        }
    }

    private String topologySummary() {
        if (config.mode.equals("broadcast")) return "broadcast from " + localNodeLabel();
        return localNodeLabel() + " ↔ " + config.gateway + " fixed unicast";
    }

    private String localNodeLabel() { return localNode == 0 ? "local identity pending" : NodeId.format(localNode); }

    private static String describeInbound(ProtoCodec.RadioPacket packet, String source) {
        StringBuilder result = new StringBuilder(source)
                .append(" → ").append(NodeId.format(packet.destination))
                .append(", ").append(transportLabel(packet))
                .append(packet.pkiEncrypted ? ", PKI" : ", channel-encrypted")
                .append(", ch ").append(packet.channel);
        int hops = packet.hopsAway();
        if (hops >= 0) result.append(", ").append(hops).append(" hop").append(hops == 1 ? "" : "s");
        if (packet.rxSnr != null) result.append(String.format(Locale.ROOT, ", SNR %.1f dB", packet.rxSnr));
        if (packet.rxRssi != null) result.append(", RSSI ").append(packet.rxRssi).append(" dBm");
        return result.toString();
    }

    private static String transportLabel(ProtoCodec.RadioPacket packet) {
        if (packet.viaMqtt || packet.transportMechanism == 5) return "MQTT";
        return switch (packet.transportMechanism) {
            case 1, 2, 3, 4 -> "LoRa";
            case 6 -> "multicast UDP";
            case 7 -> "PhoneAPI";
            default -> "radio path unknown";
        };
    }

    private void scheduleAckSweep() {
        if (closed) return;
        long delay = acknowledgements.millisUntilNextExpiry();
        synchronized (ackScheduleLock) {
            if (closed) return;
            if (ackSweep != null) ackSweep.cancel(false);
            ackSweep = null;
            if (delay < 0) return;
            ackSweep = ackScheduler.schedule(() -> {
                publish();
                scheduleAckSweep();
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void reportError(String detail) {
        lastError = detail;
        publish();
    }

    @Override public void close() {
        closed = true;
        synchronized (ackScheduleLock) {
            if (ackSweep != null) ackSweep.cancel(false);
            ackSweep = null;
        }
        ackScheduler.shutdownNow();
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
