# Reticulum over Meshtastic

Experimental, testable Reticulum transport over Meshtastic port 76
(`RETICULUM_TUNNEL_APP`). The Linux implementation supports:

- a radio connected through Meshtastic TCP PhoneAPI, serial or BLE;
- broadcast over a Meshtastic messaging channel;
- optional hub-and-spoke unicast to a configured Meshtastic Node ID;
- a binary Meshtastic MQTT virtual node using `ServiceEnvelope` protobufs;
- legacy-compatible two-byte fragmentation, missing-fragment requests and a
  bounded retransmission cache;
- one logical Reticulum child interface per unicast radio peer on a gateway.

The project never changes the radio region, modem preset or channels. Configure
the radios with the official Meshtastic client first.

## Current status

This repository contains the Linux/NixOS gateway and a native Android
foreground-service bridge. The Android app exposes a loopback-only Reticulum
TCP endpoint to Sideband/Columba and connects directly to Meshtastic TCP
PhoneAPI or BLE; it does not depend on the Meshtastic Android app.

Verified on 11 August 2026:

- 26 protocol/backend tests;
- real `rncp` transfer through the local MQTT broadcast configuration;
- real bidirectional `rncp` transfer through the unicast hub child interface;
- binary port-76 publish/subscribe on `mqtt.meshtastic.vip:1883` under
  `msh/Bulgaria` (broker transport only; no physical LoRa gateway was involved).
- a pure-LoRa traceroute with MQTT disabled from the physical gateway to a
  T-LoRa Pager through one forward relay;
- an MQTT port-76 downlink through the physical gateway to that pager, with a
  successful LoRa routing ACK returned through MQTT. The probe used three hops,
  and therefore also verifies that this broker path did not enforce zero-hop.
- a containerised Android API 35 build, six Android protocol tests, and a debug
  APK assembled without installing Gradle or the Android SDK on the host.
- the Android codec's two-stage PhoneAPI handshake against `172.16.16.115`,
  returning `!8fd13c64` and completing after 231 `FromRadio` frames.

See [docs/TESTING.md](docs/TESTING.md) for exact tests, beginning with tests that
need neither a radio nor an MQTT broker.

For the two-phone scenario (one phone with a Meshtastic radio, one without),
see [docs/ANDROID_TESTING.md](docs/ANDROID_TESTING.md).

## Quick start

```bash
devbox shell
devbox run test
uv run rns-meshtastic fragment-selftest
```

To inspect a Wi-Fi-connected Meshtastic radio without modifying it:

```bash
uv run rns-meshtastic radio-info --tcp-host 192.168.1.50
```

To run the gateway, copy one of the configurations from `examples/`, install
the loader shim into that Reticulum configuration directory and start `rnsd`:

```bash
uv run rns-meshtastic install-interface --config-dir ./var/rns-gateway
uv run rnsd --config ./var/rns-gateway -v
```

## Safety and network policy

- Use a private channel and Reticulum IFAC (`network_name` + `passphrase`).
- Do not expose the Reticulum TCP server directly to the public Internet; use a
  LAN or VPN.
- Do not use the public Meshtastic MQTT service for Reticulum bulk traffic.
- `mqtt_downlink_hops` is explicit. Set it to `0` for a broker/network with a
  zero-hop policy, or to `1`–`3` only when the broker and local mesh permit
  propagation. The implementation does not assume that all brokers enforce
  zero-hop.
- Use one MQTT downlink gateway per overlapping RF island. Multiple downlink
  gateways subscribed to the same channel can transmit duplicates at once.

## License

GPL-3.0-or-later. The fragment wire format is compatible with the earlier
`landandair/RNS_Over_Meshtastic` project; this implementation is a rewrite with
separate transport and protocol layers.
