package bg.reticulum.meshtastic.bridge;

import android.content.Context;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeEngine implements AutoCloseable, RadioTransport.Listener, ReticulumTcpServer.Listener {
    /*
     * A Reticulum TCPInterface reports 10 Mbps and starts delivery timers as
     * soon as its socket write completes. Keeping minutes of packets behind
     * that socket therefore makes Reticulum retry while the originals are
     * still waiting for LoRa. Keep only about one MTU-sized RNS frame queued
     * at the default 2 second pacing and let TCP backpressure reach the
     * producer early. A single explicitly bounded serialized frame may exceed
     * that horizon for resource tests; it blocks later data while repair
     * traffic retains reserved capacity.
     */
    private static final int MAX_QUEUED_FRAMES = 8;
    private static final int MAX_QUEUE_HORIZON_MILLIS = 8_000;
    private static final int MAX_SERIALIZED_DRAIN_MILLIS = 90_000;
    private static final int MAX_SERIALIZED_RNS_BYTES = 8 * 1024;
    interface StatusListener { void onStatus(String status); }

    private final BridgeConfig config;
    private final FragmentProtocol fragments;
    private final RadioTransport radio;
    private final ReticulumTcpServer reticulum;
    private final StatusListener status;
    private final TransmitScheduler transmit;
    private final int maxQueuedFragments;
    private final int maxQueuedBytes;
    private final int maxDataFragments;
    private final int maxDataBytes;
    private final int maxCompleteRnsFrameBytes;
    private final int maxSerializedFragments;
    private final int maxSerializedBytes;
    private final int maxSerializedRnsFrameBytes;
    private final RnsPeerRouter peerRouter = new RnsPeerRouter();
    private final AtomicLong rnsToMesh = new AtomicLong();
    private final AtomicLong broadcastRnsToMesh = new AtomicLong();
    private final AtomicLong unicastRnsToMesh = new AtomicLong();
    private final AtomicLong fragmentsToMesh = new AtomicLong();
    private final AtomicLong fragmentsFromMesh = new AtomicLong();
    private final AtomicLong deviceRejects = new AtomicLong();
    private final AtomicLong repairQueueDeferrals = new AtomicLong();
    private final AtomicLong oversizedRnsFrames = new AtomicLong();
    private final AckTracker acknowledgements = new AckTracker();
    private final AdaptiveAckPolicy adaptiveAck = new AdaptiveAckPolicy();
    private final RnsTrafficTelemetry traffic = new RnsTrafficTelemetry();
    private final SessionTelemetry session = new SessionTelemetry(SystemClock.elapsedRealtime());
    private final ScheduledExecutorService ackScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object ackScheduleLock = new Object();
    private ScheduledFuture<?> ackSweep;
    private volatile String radioState = "radio starting";
    private volatile String clientState = "Reticulum starting";
    private volatile String deviceQueueState = "device TX queue: unknown";
    private volatile int deviceQueueMax;
    private volatile int deviceQueueMinFree = Integer.MAX_VALUE;
    private volatile String lastDeviceReject = "none";
    private volatile String lastDeviceQueueResult = "none";
    private volatile String lastRxState = "none";
    private volatile String lastRouteDecision = "none";
    private volatile String lastOversizedFrame = "none";
    private volatile int largestRnsFrameBytes;
    private volatile int largestRnsFrameFragments;
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
        int reservedControlFragments = Math.max(1, Math.min(2, maxQueuedFragments / 4));
        this.maxDataFragments = Math.min(
                127, maxQueuedFragments - reservedControlFragments);
        this.maxDataBytes = maxQueuedBytes
                - reservedControlFragments * (config.fragmentBody + 2);
        this.maxCompleteRnsFrameBytes = completeFrameByteLimit(
                config.fragmentBody, maxDataFragments, maxDataBytes);
        int durationFragments = config.txIntervalMillis <= 0 ? 127
                : Math.max(maxDataFragments,
                        MAX_SERIALIZED_DRAIN_MILLIS / config.txIntervalMillis);
        int sizeFragments = Math.max(1,
                (MAX_SERIALIZED_RNS_BYTES + config.fragmentBody - 1) / config.fragmentBody);
        this.maxSerializedFragments = Math.min(127, Math.min(durationFragments, sizeFragments));
        this.maxSerializedBytes = maxSerializedFragments * (config.fragmentBody + 2);
        this.maxSerializedRnsFrameBytes = Math.min(
                MAX_SERIALIZED_RNS_BYTES,
                completeFrameByteLimit(
                        config.fragmentBody, maxSerializedFragments, maxSerializedBytes));
        this.transmit = new TransmitScheduler(
                config.txIntervalMillis,
                MAX_QUEUED_FRAMES, maxQueuedFragments, maxQueuedBytes,
                2, reservedControlFragments, reservedControlFragments * (config.fragmentBody + 2),
                // Reticulum frames arrive as one causally ordered byte stream.
                // Do not delay announcements independently or allow later data
                // to overtake them; 0.1.19 proved that this can invalidate paths
                // and prevent the corresponding delivery proof from returning.
                0,
                () -> constrainedScheduling()
                        ? radio.recommendedExtraDelayMillis(config.txIntervalMillis) : 0,
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
        ackScheduler.scheduleWithFixedDelay(
                this::pollFragmentRepairs, 1_000, 1_000, TimeUnit.MILLISECONDS);
        publish();
    }

    @Override public void onRadioState(boolean connected, String detail) {
        session.recordRadio(connected);
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
        if ((config.mode.equals("auto_single_peer") || config.mode.equals("auto_multi_peer"))
                && !acceptsAutoDestination(packet, localNode)) return;
        boolean requireLocalDestination = config.mode.equals("gateway_unicast");
        if (!acceptsInbound(packet, config.channel, localNode, requireLocalDestination)) return;
        fragmentsFromMesh.incrementAndGet();
        traffic.recordRadioRx(packet.payload.length, SystemClock.elapsedRealtime());
        lastRxState = describeInbound(packet, source);
        try {
            int possibleControlBytes = startsWith(packet.payload, FragmentProtocol.REQUEST_PREFIX)
                    ? config.fragmentBody + 2 : FragmentProtocol.REQUEST_PREFIX.length + 2;
            boolean allowControl = transmit.canAcceptControl(1, possibleControlBytes);
            FragmentProtocol.Result result = fragments.receive(source, packet.payload, allowControl);
            if (result.controlDeferred) repairQueueDeferrals.incrementAndGet();
            for (byte[] frame : result.frames) {
                if (config.mode.equals("auto_multi_peer")) {
                    peerRouter.observeInbound(source, frame);
                }
                traffic.recordFrame(false, frame);
                ReticulumTcpServer.Delivery delivery = reticulum.sendFrame(frame);
                if (delivery == ReticulumTcpServer.Delivery.REJECTED) {
                    reportError("Inbound RNS spool full; dropped completed frame from " + source);
                }
            }
            if (!queueRepairTransmissions(result.transmissions)) {
                repairQueueDeferrals.incrementAndGet();
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

    static boolean acceptsAutoDestination(ProtoCodec.RadioPacket packet, long localNode) {
        return packet.destination == NodeId.BROADCAST
                || (localNode != 0 && packet.destination == localNode);
    }

    @Override public void onClientState(boolean connected, String detail) {
        session.recordClient(connected);
        clientState = detail;
        publish();
    }

    @Override public void onFrame(byte[] frame) {
        try {
            int estimatedFragments = Math.max(1,
                    (frame.length + config.fragmentBody - 1) / config.fragmentBody);
            largestRnsFrameBytes = Math.max(largestRnsFrameBytes, frame.length);
            largestRnsFrameFragments = Math.max(largestRnsFrameFragments, estimatedFragments);
            String destination;
            if (config.mode.equals("auto_multi_peer")) {
                RnsPeerRouter.Decision decision = peerRouter.destinationFor(frame);
                destination = decision.destination;
                lastRouteDecision = decision.reason + " → " + destination;
            } else if (config.mode.equals("auto_single_peer")
                    && RnsPacketMetadata.isOpaqueIfac(frame)) {
                // IFAC masks the Reticulum header. A fixed single-peer mode does
                // not need to infer it: every opaque frame can safely use the
                // explicitly configured radio peer, including its announce.
                destination = config.gateway;
                lastRouteDecision = "opaque IFAC fixed peer → " + destination;
            } else {
                destination = config.outboundDestination(isAnnounceFrame(frame));
                lastRouteDecision = (destination.equals(RnsPeerRouter.BROADCAST)
                        ? "configured/discovery broadcast" : "configured peer")
                        + " → " + destination;
            }
            List<FragmentProtocol.Transmission> transmissions = fragments.encode(frame, destination);
            // Preserve the exact order emitted by the local RNS instance. Packet
            // type is telemetry only; it is not sufficient to infer dependencies
            // between an announce, data frame and proof at this transparent edge.
            if (transmit.enqueueSerialized(
                    transmissions, true, maxSerializedFragments, maxSerializedBytes)) {
                rnsToMesh.incrementAndGet();
                if (destination.equals("^all")) broadcastRnsToMesh.incrementAndGet();
                else unicastRnsToMesh.incrementAndGet();
                traffic.recordFrame(true, frame);
            } else if (estimatedFragments > maxSerializedFragments
                    || encodedBytes(transmissions) > maxSerializedBytes) {
                oversizedRnsFrames.incrementAndGet();
                lastOversizedFrame = frame.length + " B/" + estimatedFragments
                        + " fragments (local admission; nothing reached Meshtastic)";
            }
            publish();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            reportError("Could not queue Reticulum frame: " + useful(error));
        }
    }

    private boolean queueTransmissions(
            List<FragmentProtocol.Transmission> transmissions, boolean waitForCapacity,
            int priority)
            throws InterruptedException {
        return transmit.enqueue(transmissions, waitForCapacity, priority);
    }

    private boolean queueRepairTransmissions(
            List<FragmentProtocol.Transmission> transmissions) throws InterruptedException {
        boolean accepted = true;
        for (FragmentProtocol.Transmission transmission : transmissions) {
            if (!transmit.enqueue(Collections.singletonList(transmission), false)) accepted = false;
        }
        return accepted;
    }

    private void sendTransmission(FragmentProtocol.Transmission transmission) throws Exception {
        if (!awaitRadioIdentity()) {
            throw new IllegalStateException("radio identity was not received within 45 seconds");
        }
        boolean requestAck = requestsRadioAck(
                config.ackPolicy, transmission, config.mode.equals("broadcast"));
        if (requestAck && config.ackPolicy.equals("adaptive")) {
            requestAck = adaptiveAck.permits(acknowledgements.snapshot());
        }
        long packetId = radio.send(
                transmission.payload, NodeId.parse(transmission.destination), requestAck);
        fragmentsToMesh.incrementAndGet();
        traffic.recordRadioTx(
                transmission.reason, transmission.payload.length, SystemClock.elapsedRealtime());
        if (requestAck) {
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
        if (max > 0) {
            deviceQueueMax = Math.max(deviceQueueMax, max);
            deviceQueueMinFree = Math.min(deviceQueueMinFree, free);
        }
        lastDeviceQueueResult = queueStatusResultName(result) + " (" + result + ")"
                + (queueStatusSucceeded(result) ? " accepted" : " rejected");
        if (!queueStatusSucceeded(result)) {
            lastDeviceReject = queueStatusResultName(result) + " (" + result + ")";
            deviceRejects.incrementAndGet();
            lastError = "Meshtastic device rejected TX: " + lastDeviceReject;
        }
        deviceQueueState = "device TX queue: " + free + "/" + max + " free"
                + "; peak used: " + deviceQueuePeakUsed() + "/" + deviceQueueMax
                + "; rejects: " + deviceRejects.get() + ", last: " + lastDeviceReject
                + "; queue result: " + lastDeviceQueueResult;
        publish();
    }

    private void publish() {
        TransmitScheduler.Snapshot queue = transmit.snapshot();
        FragmentProtocol.Snapshot reassembly = fragments.snapshot();
        ReticulumTcpServer.Snapshot client = reticulum.snapshot();
        AckTracker.Snapshot ack = acknowledgements.snapshot();
        long now = SystemClock.elapsedRealtime();
        RnsTrafficTelemetry.Snapshot trafficSnapshot = traffic.snapshot(now);
        RnsPeerRouter.Snapshot peerSnapshot = peerRouter.snapshot();
        long dropped = queue.dataRejectedFrames + queue.dataFailedFrames;
        String summary =
                radioState + "\n" + clientState
                        + "\n" + session.describe(now)
                        + "\ntopology: " + topologySummary() + ", local channel slot: " + config.channel
                        + "\nMQTT forwarding: " + config.mqttForwardingPolicy + " → "
                        + (radio.mqttForwardingAllowed() ? "allowed" : "denied")
                        + "\ntraffic scheduling: " + config.trafficProfile
                        + addressingStatus(peerSnapshot)
                        + "\nTX RNS→mesh: " + rnsToMesh.get() + " frames / "
                        + fragmentsToMesh.get() + " fragments"
                        + "; addressing broadcast/unicast " + broadcastRnsToMesh.get()
                        + "/" + unicastRnsToMesh.get() + " frames"
                        + "\nRX mesh→bridge: " + reassembly.completedFrames + " frames / "
                        + fragmentsFromMesh.get() + " fragments; last: " + lastRxState
                        + "\n" + trafficSnapshot.frameMix
                        + "\n" + trafficSnapshot.radioWindow
                        + "\nradio queue: " + queue.frames + " frames, " + queue.fragments
                        + "/" + maxQueuedFragments + " fragments, " + queue.bytes
                        + "/" + maxQueuedBytes + " bytes"
                        + ", drain ≈" + formatDuration(queue.estimatedDrainMillis)
                        + "; peak: " + queue.peakFrames + " frames/"
                        + queue.peakFragments + " fragments/" + queue.peakBytes + " bytes"
                        + "\n" + deviceQueueState
                        + "; backpressure: " + queue.backpressureEvents
                        + ", local retries: " + queue.retryAttempts + ", dropped: " + dropped
                        + "\nscheduler: RNS FIFO causal order; accepted "
                        + queue.normalPriorityFrames + " RNS frames; queue pacing "
                        + queue.adaptivePacingEvents + " events, last +"
                        + queue.currentExtraDelayMillis + " ms"
                        + "\nRNS frame admission: complete frame ≤"
                        + maxCompleteRnsFrameBytes + " B/" + maxDataFragments
                        + " fragments queued normally; serialized bulk ≤"
                        + maxSerializedRnsFrameBytes + " B/" + maxSerializedFragments
                        + " fragments (one at a time); active/accepted "
                        + queue.serializedFrames + "/" + queue.serializedAcceptedFrames
                        + "; largest seen " + largestRnsFrameBytes + " B/"
                        + largestRnsFrameFragments + " fragments; oversize rejected: "
                        + oversizedRnsFrames.get() + ", last: " + lastOversizedFrame
                        + "\nRNS client delivery: " + client.deliveredTotal() + " frames ("
                        + client.deliveredDirect + " direct, " + client.spool.replayedFrames
                        + " replayed); inbound spool: " + client.spool.frames + "/"
                        + ReticulumTcpServer.MAX_SPOOLED_FRAMES + " frames, " + client.spool.bytes + "/"
                        + ReticulumTcpServer.MAX_SPOOLED_BYTES + " bytes; queued: " + client.spool.queuedFrames
                        + ", duplicate: " + client.spool.duplicateFrames
                        + ", expired: " + client.spool.expiredFrames
                        + ", rejected: " + client.spool.rejectedFrames
                        + "\nreassembly: " + reassembly.activeAssemblies + " active, "
                        + reassembly.awaitingFinal + " awaiting final, "
                        + reassembly.missingFragments + " missing; completed: "
                        + reassembly.completedFrames + ", repair REQ: "
                        + reassembly.repairRequests + " (final: "
                        + reassembly.finalRepairRequests + "), retransmits: "
                        + reassembly.retransmissions + ", capped: "
                        + reassembly.cappedRepairs + ", expired: "
                        + reassembly.expiredAssemblies + ", duplicates: "
                        + reassembly.duplicateFrames
                        + "\nrepair flow: " + reassembly.repairBudgetUsed + "/"
                        + reassembly.repairBudgetLimit + " requests in rolling minute; throttled: "
                        + reassembly.throttledRepairs + ", queue-deferred: "
                        + repairQueueDeferrals.get()
                        + "\nadmission: data " + queue.dataRejectedFrames + " rejected/"
                        + queue.dataFailedFrames + " send-failed; control "
                        + queue.controlRejectedFrames + " rejected/"
                        + queue.controlFailedFrames + " send-failed; last reject: "
                        + queue.lastRejection;
        if (!config.ackPolicy.equals("off") && !config.mode.equals("broadcast")) {
            String ackMode = config.ackPolicy.equals("adaptive")
                    ? "adaptive; " + adaptiveAck.describe(ack) : config.ackPolicy;
            summary += "\nradio ACK (" + ackMode + "): "
                    + ack.confirmed + " confirmed, " + ack.failed
                    + " NAK, " + ack.unknown + " unknown, " + ack.pending
                    + " pending; last: " + ack.lastResult;
        } else {
            String reason = config.mode.equals("broadcast")
                    ? "disabled in broadcast mode" : "disabled";
            summary += "\nradio ACK: " + reason
                    + " (Reticulum/LXMF proofs remain authoritative)";
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
        if (config.mode.equals("auto_multi_peer")) {
            return localNodeLabel()
                    + " auto multi-peer (announce/unknown broadcast, learned destinations unicast)";
        }
        if (config.mode.equals("auto_single_peer")) {
            return localNodeLabel() + " ↔ " + config.gateway
                    + " auto single-peer (announce broadcast, other RNS unicast)";
        }
        return localNodeLabel() + " ↔ " + config.gateway + " fixed unicast";
    }

    private String localNodeLabel() { return localNode == 0 ? "local identity pending" : NodeId.format(localNode); }

    private boolean constrainedScheduling() {
        return config.trafficProfile.equals("constrained_auto");
    }

    static boolean isAnnounceFrame(byte[] frame) {
        RnsPacketMetadata metadata = RnsPacketMetadata.parse(frame);
        return metadata != null && metadata.packetType == RnsPacketMetadata.ANNOUNCE;
    }

    private String addressingStatus(RnsPeerRouter.Snapshot peer) {
        if (!config.mode.equals("auto_multi_peer")) {
            return "\naddressing decision: " + lastRouteDecision;
        }
        return "\npeer routing: " + peer.peers + "/32 peers, " + peer.routes
                + "/512 routes; learned " + peer.learnedRoutes + ", conflicts "
                + peer.routeConflicts + ", unknown broadcasts " + peer.unknownBroadcasts
                + ", opaque IFAC broadcasts " + peer.opaqueIfacBroadcasts
                + "; last: " + lastRouteDecision
                + "\npeer table: " + peer.peerSummary;
    }

    private int deviceQueuePeakUsed() {
        if (deviceQueueMax <= 0 || deviceQueueMinFree == Integer.MAX_VALUE) return 0;
        return Math.max(0, deviceQueueMax - deviceQueueMinFree);
    }

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

    static String transportLabel(ProtoCodec.RadioPacket packet) {
        String arrival = switch (packet.transportMechanism) {
            case 1, 2, 3, 4 -> "LoRa";
            case 5 -> "MQTT";
            case 6 -> "multicast UDP";
            case 7 -> "PhoneAPI";
            case 8 -> "unicast UDP";
            default -> "radio path unknown";
        };
        if (!packet.viaMqtt || packet.transportMechanism == 5) return arrival;
        return packet.transportMechanism == 0 ? "MQTT-origin, arrival unknown" : "MQTT→" + arrival;
    }

    static boolean requestsRadioAck(
            String policy, FragmentProtocol.Transmission transmission, boolean broadcastMode) {
        if (broadcastMode) return false;
        if (transmission.destination.equals("^all") || policy.equals("off")) return false;
        if (policy.equals("all")) return true;
        if (policy.equals("adaptive")) policy = "critical";
        if (!policy.equals("critical")) throw new IllegalArgumentException("unknown ACK policy " + policy);
        if (transmission.reason.equals("request") || transmission.reason.equals("retransmit")) return true;
        byte[] payload = transmission.payload;
        return transmission.reason.equals("data") && payload.length >= 2 && payload[1] < -1;
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

    private void pollFragmentRepairs() {
        if (closed) return;
        try {
            int requestBytes = FragmentProtocol.REQUEST_PREFIX.length + 2;
            FragmentProtocol.Result result = fragments.pollRepairs(
                    1, transmit.canAcceptControl(1, requestBytes));
            if (result.controlDeferred) {
                repairQueueDeferrals.incrementAndGet();
                publish();
                return;
            }
            if (result.transmissions.isEmpty()) return;
            if (!queueRepairTransmissions(result.transmissions)) {
                repairQueueDeferrals.incrementAndGet();
            } else publish();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            reportError("Meshtastic periodic repair failed: " + useful(error));
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

    static int fragmentQueueLimit(int intervalMillis) {
        if (intervalMillis <= 0) return 256;
        return Math.max(4, Math.min(256, MAX_QUEUE_HORIZON_MILLIS / intervalMillis));
    }

    static int completeFrameByteLimit(int fragmentBody, int maxFragments, int maxBytes) {
        int upper = Math.max(0, maxFragments * fragmentBody);
        for (int bytes = upper; bytes >= 0; bytes--) {
            int fragments = Math.max(1, (bytes + fragmentBody - 1) / fragmentBody);
            if (fragments <= maxFragments && bytes + 2 * fragments <= maxBytes) return bytes;
        }
        return 0;
    }

    private static int encodedBytes(List<FragmentProtocol.Transmission> transmissions) {
        int total = 0;
        for (FragmentProtocol.Transmission transmission : transmissions) {
            total += transmission.payload.length;
        }
        return total;
    }

    static String routingErrorName(int result) {
        return switch (result) {
            case 0 -> "NONE";
            case 1 -> "NO_ROUTE";
            case 2 -> "GOT_NAK";
            case 3 -> "TIMEOUT";
            case 4 -> "NO_INTERFACE";
            case 5 -> "MAX_RETRANSMIT";
            case 6 -> "NO_CHANNEL";
            case 7 -> "TOO_LARGE";
            case 8 -> "NO_RESPONSE";
            case 9 -> "DUTY_CYCLE_LIMIT";
            case 32 -> "BAD_REQUEST";
            case 33 -> "NOT_AUTHORIZED";
            case 34 -> "PKI_FAILED";
            case 35 -> "PKI_UNKNOWN_PUBKEY";
            case 38 -> "RATE_LIMIT_EXCEEDED";
            case 39 -> "PKI_SEND_FAIL_PUBLIC_KEY";
            default -> "UNKNOWN";
        };
    }

    static boolean queueStatusSucceeded(int result) {
        return ProtoCodec.queueStatusSucceeded(result);
    }

    static String queueStatusResultName(int result) {
        return ProtoCodec.queueStatusResultName(result);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
