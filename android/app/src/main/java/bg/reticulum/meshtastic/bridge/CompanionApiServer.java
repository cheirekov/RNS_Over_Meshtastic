package bg.reticulum.meshtastic.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Localhost-only, read-only bridge capabilities and status API. */
final class CompanionApiServer implements AutoCloseable {
    static final int DEFAULT_PORT = 7823;
    private static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final Pattern TX = Pattern.compile("TX RNS→mesh: (\\d+) frames / (\\d+) fragments");
    private static final Pattern RX = Pattern.compile("RX mesh→bridge: (\\d+) frames / (\\d+) fragments");
    private static final Pattern QUEUE = Pattern.compile("radio queue: (\\d+) frames, (\\d+)/(\\d+) fragments, (\\d+)/(\\d+) bytes");
    private static final Pattern FRAME_BYTES = Pattern.compile(
            "RNS frame mix: TX .*?, (\\d+) B, last [^;]*; RX .*?, (\\d+) B, last");
    private static final Pattern SESSION = Pattern.compile(
            "bridge session: ([0-9a-f]+), uptime (\\d+)h (\\d+)m (\\d+)s");
    private static final Pattern PEER_COUNTS = Pattern.compile(
            "peer routing: (\\d+)/(\\d+) peers, (\\d+)/(\\d+) routes");
    private static final Pattern PEER = Pattern.compile(
            "(![0-9a-fA-F]{8}) \\((\\d+) routes, seen (\\d+)s ago\\)");
    private final int requestedPort;
    private final BridgeConfig config;
    private final Supplier<String> statusSupplier;
    private final ExecutorService clients = Executors.newFixedThreadPool(2);
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile boolean closed;

    CompanionApiServer(int port, BridgeConfig config, Supplier<String> statusSupplier) {
        this.requestedPort = port;
        this.config = config;
        this.statusSupplier = statusSupplier;
    }

    void start() throws IOException {
        if (server != null) return;
        server = new ServerSocket(requestedPort, 8, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::acceptLoop, "bridge-companion-api");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() { return server == null ? requestedPort : server.getLocalPort(); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                clients.submit(() -> handle(socket));
            } catch (IOException error) {
                if (!closed) break;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5_000);
            String request = readRequest(socket.getInputStream());
            String firstLine = request.lines().findFirst().orElse("");
            String[] parts = firstLine.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                respond(socket.getOutputStream(), 405, "{\"error\":\"read-only GET API\"}");
                return;
            }
            String body = response(parts[1]);
            if (body == null) respond(socket.getOutputStream(), 404, "{\"error\":\"not found\"}");
            else respond(socket.getOutputStream(), 200, body);
        } catch (IOException ignored) {
            // A localhost client can disconnect at any time; transport stays authoritative.
        }
    }

    private String response(String path) {
        String status = value(statusSupplier.get());
        return switch (path) {
            case "/v1/capabilities" -> capabilitiesJson(config.localPort, port());
            case "/v1/status" -> statusJson(config, status);
            case "/v1/traffic" -> trafficJson(status);
            case "/v1/peers" -> peersJson(status);
            default -> null;
        };
    }

    static String capabilitiesJson(int rnsPort, int apiPort) {
        return "{\"schema\":1,\"implementation\":\"rns-over-meshtastic-android\""
                + ",\"implementation_version\":" + quote(BuildConfig.VERSION_NAME)
                + ",\"rns_tcp_port\":" + rnsPort
                + ",\"status_api_port\":" + apiPort
                + ",\"constrained_transport\":true,\"realtime_supported\":false"
                + ",\"maximum_serialized_rns_bytes\":8192,\"meshtastic_portnum\":76"
                + ",\"addressing_modes\":[\"broadcast\",\"gateway_unicast\","
                + "\"auto_single_peer\",\"auto_multi_peer\"]}";
    }

    static String statusJson(BridgeConfig config, String status) {
        boolean running = !status.equals("Bridge stopped") && !status.equals("Bridge is not running");
        Matcher session = SESSION.matcher(status);
        String transportId = null;
        long uptime = 0;
        if (session.find()) {
            transportId = session.group(1);
            uptime = Long.parseLong(session.group(2)) * 3600
                    + Long.parseLong(session.group(3)) * 60
                    + Long.parseLong(session.group(4));
        }
        StringBuilder alerts = new StringBuilder("[");
        for (String warning : config.safetyWarnings()) {
            if (alerts.length() > 1) alerts.append(',');
            alerts.append("{\"severity\":\"warning\",\"code\":\"lora_policy\",\"message\":")
                    .append(quote(warning)).append('}');
        }
        alerts.append(']');
        return "{\"schema\":1,\"captured_at\":" + quote(Instant.now().toString())
                + ",\"running\":" + running
                + ",\"implementation\":\"rns-over-meshtastic-android\""
                + ",\"implementation_version\":" + quote(BuildConfig.VERSION_NAME)
                + ",\"transport_id\":" + (transportId == null ? "null" : quote(transportId))
                + ",\"uptime_seconds\":" + uptime
                + ",\"transport\":" + quote(config.transport)
                + ",\"radio_state\":" + quote(firstLine(status))
                + ",\"rns_state\":" + quote(secondLine(status))
                + ",\"lxmd_state\":\"not_local\""
                + ",\"policy_profile\":" + quote(config.trafficProfile)
                + ",\"topology\":" + quote(config.mode)
                + ",\"channel_index\":" + config.channel
                + ",\"hop_limit\":" + config.hops
                + ",\"peers\":" + peerArrayJson(status) + ",\"alerts\":" + alerts
                + ",\"status_text\":" + quote(status) + "}";
    }

    static String trafficJson(String status) {
        long[] tx = matches(TX, status, 2);
        long[] rx = matches(RX, status, 2);
        long[] queue = matches(QUEUE, status, 5);
        long[] bytes = matches(FRAME_BYTES, status, 2);
        return "{\"schema\":1,\"captured_at\":" + quote(Instant.now().toString())
                + ",\"lora\":" + counterJson(bytes[1], bytes[0], true, "android diagnostics")
                + ",\"lan\":" + counterJson(bytes[0], bytes[1], true, "android diagnostics")
                + ",\"public\":" + counterJson(0, 0, false, "not measured")
                + ",\"propagation\":" + counterJson(0, 0, false, "not measured")
                + ",\"tx_rns_frames\":" + tx[0] + ",\"tx_meshtastic_fragments\":" + tx[1]
                + ",\"rx_rns_frames\":" + rx[0] + ",\"rx_meshtastic_fragments\":" + rx[1]
                + ",\"queue_frames\":" + queue[0] + ",\"queue_fragments\":" + queue[1]
                + ",\"queue_fragment_limit\":" + queue[2] + ",\"queue_bytes\":" + queue[3]
                + ",\"queue_byte_limit\":" + queue[4] + "}";
    }

    static String peersJson(String status) {
        String routing = lineWithPrefix(status, "peer routing:");
        String table = lineWithPrefix(status, "peer table:");
        long[] counts = matches(PEER_COUNTS, status, 4);
        return "{\"schema\":1,\"captured_at\":" + quote(Instant.now().toString())
                + ",\"peer_count\":" + counts[0] + ",\"peer_limit\":" + counts[1]
                + ",\"route_count\":" + counts[2] + ",\"route_limit\":" + counts[3]
                + ",\"peers\":" + peerArrayJson(status)
                + ",\"routing_summary\":" + quote(routing)
                + ",\"peer_table\":" + quote(table) + "}";
    }

    private static String peerArrayJson(String status) {
        Matcher matcher = PEER.matcher(lineWithPrefix(status, "peer table:"));
        StringBuilder peers = new StringBuilder("[");
        while (matcher.find()) {
            if (peers.length() > 1) peers.append(',');
            peers.append("{\"peer\":")
                    .append(quote(matcher.group(1).toLowerCase(Locale.ROOT)))
                    .append(",\"routes\":").append(matcher.group(2))
                    .append(",\"last_seen_seconds\":").append(matcher.group(3))
                    .append(",\"source\":\"meshtastic\"}");
        }
        return peers.append(']').toString();
    }

    private static String counterJson(long rxBytes, long txBytes, boolean available, String source) {
        return "{\"rx_bytes\":" + rxBytes + ",\"tx_bytes\":" + txBytes
                + ",\"rx_bps\":0.0,\"tx_bps\":0.0,\"available\":" + available
                + ",\"source\":" + quote(source) + "}";
    }

    private static long[] matches(Pattern pattern, String input, int count) {
        long[] result = new long[count];
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) return result;
        for (int index = 0; index < count; index++) {
            result[index] = Long.parseLong(matcher.group(index + 1));
        }
        return result;
    }

    private static String firstLine(String value) {
        int end = value.indexOf('\n');
        return end < 0 ? value : value.substring(0, end);
    }

    private static String secondLine(String value) {
        String[] lines = value.split("\\n", 3);
        return lines.length < 2 ? "unknown" : lines[1];
    }

    private static String lineWithPrefix(String value, String prefix) {
        for (String line : value.split("\\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "none";
    }

    private static String value(String value) { return value == null ? "status unavailable" : value; }

    static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    private static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < MAX_REQUEST_BYTES) {
            int value = input.read();
            if (value < 0) break;
            buffer.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> 4;
            };
            if (matched == 4) return buffer.toString(StandardCharsets.US_ASCII);
        }
        throw new IOException("invalid or oversized HTTP request");
    }

    private static void respond(OutputStream output, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Method Not Allowed";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(data);
        output.flush();
    }

    @Override public void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        clients.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }
}
