package bg.reticulum.meshtastic.bridge;

import android.content.Context;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeEngine implements AutoCloseable, RadioTransport.Listener, ReticulumTcpServer.Listener {
    interface StatusListener { void onStatus(String status); }

    private final BridgeConfig config;
    private final FragmentProtocol fragments;
    private final RadioTransport radio;
    private final ReticulumTcpServer reticulum;
    private final StatusListener status;
    private final ExecutorService transmit = Executors.newSingleThreadExecutor();
    private final AtomicLong rnsToMesh = new AtomicLong();
    private final AtomicLong meshToRns = new AtomicLong();
    private volatile String radioState = "radio starting";
    private volatile String clientState = "Reticulum starting";
    private volatile long localNode;

    BridgeEngine(Context context, BridgeConfig config, StatusListener status) {
        this.config = config;
        this.status = status;
        this.fragments = new FragmentProtocol(config.fragmentBody);
        this.radio = config.transport.equals("ble")
                ? new BlePhoneApiTransport(context, config)
                : new TcpPhoneApiTransport(config);
        this.reticulum = new ReticulumTcpServer(config.localPort, this);
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
            queueTransmissions(result.transmissions);
            publish();
        } catch (Exception error) {
            status.onStatus("Dropped port 76 packet from " + source + ": " + useful(error));
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
            queueTransmissions(transmissions);
            rnsToMesh.incrementAndGet();
            publish();
        } catch (Exception error) {
            status.onStatus("Could not queue Reticulum frame: " + useful(error));
        }
    }

    private void queueTransmissions(List<FragmentProtocol.Transmission> transmissions) {
        if (transmissions.isEmpty()) return;
        transmit.submit(() -> {
            for (int i = 0; i < transmissions.size(); i++) {
                FragmentProtocol.Transmission tx = transmissions.get(i);
                try {
                    if (!awaitRadioIdentity()) {
                        status.onStatus("Meshtastic TX timed out: radio identity was not received within 45 seconds");
                        return;
                    }
                    radio.send(tx.payload, NodeId.parse(tx.destination));
                    if (i + 1 < transmissions.size() && config.txIntervalMillis > 0) Thread.sleep(config.txIntervalMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception error) {
                    status.onStatus("Meshtastic TX failed (" + tx.reason + "): " + useful(error));
                    return;
                }
            }
        });
    }

    private boolean awaitRadioIdentity() throws InterruptedException {
        if (radio.isReady()) return true;
        status.onStatus("Reticulum frame queued; waiting for " + config.transport.toUpperCase() + " radio identity…");
        for (int attempt = 0; attempt < 90; attempt++) {
            if (radio.isReady()) return true;
            Thread.sleep(500);
        }
        return false;
    }

    private void publish() {
        status.onStatus(radioState + "\n" + clientState + "\nframes: RNS→mesh " + rnsToMesh.get() + ", mesh→RNS " + meshToRns.get());
    }

    @Override public void close() {
        reticulum.close();
        radio.close();
        transmit.shutdownNow();
    }

    private static String useful(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
