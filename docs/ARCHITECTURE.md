# Architecture

The implementation separates four concerns:

1. `FragmentProtocol` maps one Reticulum frame to legacy-compatible port 76
   fragments and handles missing-fragment requests.
2. `NativeBackend` talks to one real radio over Meshtastic PhoneAPI.
3. `MqttBackend` is a protocol-level virtual node that publishes and consumes
   binary `ServiceEnvelope` messages.
4. `RNSMeshtasticInterface` maps those packets into Reticulum. In unicast hub
   mode it creates a point-to-point child interface for each source NodeNum.

```text
Reticulum packet
      │
RNSMeshtasticInterface
      │
FragmentProtocol (port 76)
      ├── NativeBackend ── PhoneAPI ── Meshtastic radio ── LoRa
      └── MqttBackend ──── ServiceEnvelope ── MQTT ── radio gateway ── LoRa
```

## Loop controls

- Meshtastic firmware does not re-uplink packets marked `via_mqtt`.
- The MQTT backend ignores its own `gateway_id`.
- MQTT inbound packets are deduplicated by source NodeNum, packet ID and payload
  digest for five minutes by default.
- Reticulum performs its own packet deduplication after reassembly.
- MQTT messages are never retained.

## Current design boundaries

- MQTT JSON is unsupported because port 76 is binary and is not one of the JSON
  downlink message types.
- The simple MQTT virtual node does not own a Meshtastic PKI private key. MQTT
  downlink is therefore channel-encrypted by the physical gateway. A future
  Portduino-backed virtual node can provide a full Meshtastic identity.
- The Android foreground-service bridge is implemented and uses the same port
  76 fragmentation and gateway-unicast semantics. Its Reticulum-facing link is
  loopback TCP, so clients see the local TCP bitrate while the bridge enforces
  the actual radio constraint with pacing, bounded queues and PhoneAPI flow
  control.
- Persistent store-and-forward belongs at the LXMF propagation layer. The
  transport does not persist and replay arbitrary opaque Reticulum frames.
