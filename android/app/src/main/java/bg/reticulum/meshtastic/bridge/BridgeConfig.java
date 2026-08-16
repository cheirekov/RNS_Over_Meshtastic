package bg.reticulum.meshtastic.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class BridgeConfig {
    static final String PREFS = "bridge";
    final String transport;
    final String radioHost;
    final int radioPort;
    final String bleAddress;
    final int localPort;
    final int channel;
    final int hops;
    final String mqttForwardingPolicy;
    final String trafficProfile;
    final String mode;
    final String gateway;
    final String allowedSources;
    final Set<String> allowedSourceNodes;
    final int fragmentBody;
    final int txIntervalMillis;
    final String ackPolicy;

    BridgeConfig(
            String transport, String radioHost, int radioPort, String bleAddress, int localPort,
            int channel, int hops, String mode, String gateway, int fragmentBody,
            int txIntervalMillis, String ackPolicy, String allowedSources,
            String mqttForwardingPolicy, String trafficProfile) {
        this.transport = transport;
        this.radioHost = radioHost;
        this.radioPort = radioPort;
        this.bleAddress = bleAddress;
        this.localPort = localPort;
        this.channel = channel;
        this.hops = hops;
        this.mqttForwardingPolicy = mqttForwardingPolicy;
        this.trafficProfile = trafficProfile;
        this.mode = mode;
        this.gateway = gateway;
        this.allowedSources = allowedSources.trim();
        this.allowedSourceNodes = parseAllowedSources(this.allowedSources);
        this.fragmentBody = fragmentBody;
        this.txIntervalMillis = txIntervalMillis;
        this.ackPolicy = ackPolicy;
        validate();
    }

    static BridgeConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new BridgeConfig(
                p.getString("transport", "tcp"),
                p.getString("radio_host", "172.16.16.115"),
                p.getInt("radio_port", 4403),
                p.getString("ble_address", ""),
                p.getInt("local_port", 7822),
                p.getInt("channel", 0),
                p.getInt("hops", 3),
                p.getString("mode", "gateway_unicast"),
                p.getString("gateway", "!8fd13c64"),
                p.getInt("fragment_body", 200),
                p.getInt("tx_interval_ms", 2000),
                p.contains("ack_policy")
                        ? p.getString("ack_policy", "off")
                        : (p.getBoolean("want_ack", false) ? "critical" : "off"),
                p.getString("allowed_sources", ""),
                p.getString("mqtt_forwarding_policy", "inherit"),
                p.getString("traffic_profile", "constrained_auto"));
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("transport", transport)
                .putString("radio_host", radioHost)
                .putInt("radio_port", radioPort)
                .putString("ble_address", bleAddress)
                .putInt("local_port", localPort)
                .putInt("channel", channel)
                .putInt("hops", hops)
                .putString("mqtt_forwarding_policy", mqttForwardingPolicy)
                .putString("traffic_profile", trafficProfile)
                .putString("mode", mode)
                .putString("gateway", gateway)
                .putString("allowed_sources", allowedSources)
                .putInt("fragment_body", fragmentBody)
                .putInt("tx_interval_ms", txIntervalMillis)
                .putString("ack_policy", ackPolicy)
                .remove("want_ack")
                .apply();
    }

    String outboundDestination(boolean announce) {
        if (mode.equals("broadcast")) return "^all";
        if (mode.equals("auto_single_peer") && announce) return "^all";
        return gateway;
    }

    boolean allowsMqttForwarding(boolean radioAllows) {
        return mqttForwardingPolicy.equals("inherit") && radioAllows;
    }

    boolean acceptsSource(String source) {
        if (!mode.equals("broadcast") && !mode.equals("auto_multi_peer")) {
            return source.equalsIgnoreCase(gateway);
        }
        return allowedSourceNodes.isEmpty() || allowedSourceNodes.contains(NodeId.format(NodeId.parse(source)));
    }

    private static Set<String> parseAllowedSources(String value) {
        if (value.isBlank()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            String candidate = item.trim();
            if (!candidate.isEmpty()) result.add(NodeId.format(NodeId.parse(candidate)));
        }
        return Collections.unmodifiableSet(result);
    }

    private void validate() {
        if (!transport.equals("tcp") && !transport.equals("ble")) throw new IllegalArgumentException("Transport must be tcp or ble");
        if (transport.equals("tcp") && radioHost.isBlank()) throw new IllegalArgumentException("TCP radio host is required");
        if (transport.equals("ble") && !bleAddress.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            throw new IllegalArgumentException("BLE address must look like AA:BB:CC:DD:EE:FF");
        }
        if (radioPort < 1 || radioPort > 65535 || localPort < 1 || localPort > 65535) throw new IllegalArgumentException("Invalid TCP port");
        if (channel < 0 || channel > 7) throw new IllegalArgumentException("Channel index must be 0..7");
        if (hops < 0 || hops > 7) throw new IllegalArgumentException("Hop limit must be 0..7");
        if (!mqttForwardingPolicy.equals("inherit")
                && !mqttForwardingPolicy.equals("force_off")) {
            throw new IllegalArgumentException("MQTT forwarding policy must be inherit or force_off");
        }
        if (!trafficProfile.equals("constrained_auto")
                && !trafficProfile.equals("transparent")) {
            throw new IllegalArgumentException("Traffic profile must be constrained_auto or transparent");
        }
        if (!mode.equals("broadcast") && !mode.equals("gateway_unicast")
                && !mode.equals("auto_single_peer") && !mode.equals("auto_multi_peer")) {
            throw new IllegalArgumentException("Invalid mode");
        }
        if (mode.equals("gateway_unicast") || mode.equals("auto_single_peer")) NodeId.parse(gateway);
        if (!ackPolicy.equals("adaptive") && !ackPolicy.equals("off")
                && !ackPolicy.equals("critical") && !ackPolicy.equals("all")) {
            throw new IllegalArgumentException("ACK policy must be adaptive, off, critical or all");
        }
        if (fragmentBody < 1 || fragmentBody > 230) throw new IllegalArgumentException("Fragment body must be 1..230");
        if (txIntervalMillis < 0 || txIntervalMillis > 60_000) throw new IllegalArgumentException("TX interval must be 0..60000 ms");
    }
}
