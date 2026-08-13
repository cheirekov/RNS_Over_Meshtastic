# Reticulum over Meshtastic

Reticulum transport over Meshtastic using the officially assigned
`RETICULUM_TUNNEL_APP` PortNum 76. The functional MVP is complete: Reticulum
announces, LXMF messages and small payloads have crossed real Meshtastic LoRa
and MQTT paths between Linux, Sideband and Columba clients. The project is now
in active optimisation and hardening; it is not yet presented as a
production-ready or emergency-communications system.

The implementation does not change a radio's region, modem preset, channels or
MQTT configuration. Configure radios with an official Meshtastic client first.

## Project status

| Area | Status | Meaning |
| --- | --- | --- |
| Functional MVP | Complete | Linux and Android bridges exchange Reticulum traffic over real Meshtastic radios. |
| LoRa and MQTT validation | Complete for the documented scenarios | Pure LoRa, LoRa–MQTT–LoRa and MQTT virtual-node paths have been exercised. |
| Direct phone-to-phone mode | Complete | Two Android bridges and two radios can communicate without a Linux gateway. |
| Queue, power and background hardening | Active | Bounded queues, pacing and Android foreground operation are implemented; longer field tests continue. |
| Production readiness | Not claimed | Capacity limits, delivery behaviour and failure recovery still need wider measurement and soak testing. |

## Architecture and implemented capabilities

```text
Sideband / Columba / other RNS client
                │ local TCP or shared RNS instance
        Linux or Android bridge
                │ PortNum 76 + fragmentation + pacing
     Meshtastic PhoneAPI or binary MQTT
                │
       Meshtastic LoRa / MQTT mesh
```

The Linux/NixOS implementation provides:

- a native Meshtastic radio backend over TCP PhoneAPI, serial or BLE;
- broadcast and configurable gateway-unicast addressing modes;
- one logical Reticulum child interface per unicast Meshtastic peer;
- a Reticulum TCP server for LAN/VPN clients without their own radio;
- a binary MQTT virtual node using Meshtastic `ServiceEnvelope` protobufs;
- legacy-compatible two-byte fragmentation, missing-fragment requests and a
  bounded retransmission cache;
- optional Reticulum IFAC isolation with `network_name` and `passphrase`.

Android bridge 0.1.8 provides:

- a direct BLE or TCP PhoneAPI connection without depending on the official
  Meshtastic Android app;
- a loopback-only Reticulum TCP endpoint for Sideband, Columba and compatible
  clients on the same phone;
- gateway-unicast operation through a Linux radio and direct broadcast
  operation between Android radios without a Linux gateway;
- global LoRa fragment pacing, bounded frame/fragment/byte queues, TCP
  backpressure, retransmission reserve and Meshtastic `queue_status` flow
  control;
- serialized BLE GATT operations, capped reconnect backoff and a 45-second
  pre-handshake frame hold instead of silently dropping early traffic;
- Android `connectedDevice` foreground-service operation without a permanent
  partial wake lock, plus coalesced status updates and a safe PhoneAPI
  heartbeat;
- separate TX/RX frame and fragment telemetry, last inbound peer/path/RF
  metadata, and correlation of optional Meshtastic routing ACK/NAK responses;
- delivery reporting that distinguishes radio confirmation, explicit NAK and
  an unknown result after ACK timeout from Reticulum/LXMF delivery proofs.

The `10 Mbps` value that a Reticulum client can display belongs to the local TCP
connection to the Android bridge. It is not an estimate of LoRa throughput.
The bridge currently enforces the radio bottleneck at its bounded scheduler and
PhoneAPI queue boundary.

## Demonstrated end-to-end scenarios

The following have been exercised on real hardware and clients:

- Reticulum announces and bidirectional short LXMF messages through a
  Wi-Fi-connected Linux Meshtastic gateway;
- Android over BLE → Meshtastic LoRa → Linux gateway → Sideband/Columba;
- Android over BLE → LoRa/MQTT path → remote Meshtastic gateway → Linux;
- two Android phones, each with its own Meshtastic radio, in broadcast mode and
  without an intermediate Linux server;
- two Android phones in fixed reciprocal Meshtastic unicast mode without an
  intermediate Linux server;
- operation with and without Reticulum IFAC;
- a small image transfer as a controlled low-bandwidth test;
- Android foreground operation on Pixel Android and Honor MagicOS after the
  required OEM battery permissions were granted;
- MQTT downlink with a non-zero hop limit on a broker whose deployment permits
  it, including a returned Meshtastic routing ACK.

Automated validation currently contains 31 Python tests and 24 Android unit
tests, in addition to Android lint and containerised APK builds. Exact,
repeatable procedures and the distinction between native Meshtastic DM and the
decoded MQTT virtual-node path are in [docs/TESTING.md](docs/TESTING.md).

## Active optimisation and next work

Work after the MVP is deliberately measurement-driven:

1. Run two-phone and phone-to-Linux background soak tests, including radio
   disconnect/reconnect, screen-off operation, queue peaks, delivery latency
   and battery drain on more Android vendors.
2. Characterise useful payload sizes and safe pacing for the supported modem
   presets instead of treating the local TCP rate as radio capacity.
3. Field-validate the new radio ACK/NAK telemetry against Reticulum/LXMF proofs
   and tune the constrained return path without treating an ACK timeout as
   proof that the payload was not delivered.
4. Evaluate an adaptive profile: broadcast for discovery/announces and
   Meshtastic PKI unicast for known peers, without weakening Reticulum's own
   routing and security model.
5. Add LXMF-level store-and-forward through a propagation node. Persistent
   replay of arbitrary raw Reticulum frames is intentionally not the design.
6. Evaluate multiple Linux radios first as active/passive failover or receive
   diversity. Same-channel bandwidth aggregation is not assumed to be safe or
   useful.
7. Polish release packaging, upgrade paths, diagnostics and field-test
   reporting before declaring a stable release.

See [docs/CAPACITY_AND_STORE_FORWARD.md](docs/CAPACITY_AND_STORE_FORWARD.md)
for the queueing, file, location, voice-note and multi-radio design assessment,
and [docs/ANDROID_BACKGROUND.md](docs/ANDROID_BACKGROUND.md) for the current
power model and soak-test checklist.

The agreed milestone order and the boundary between bridge, Reticulum and LXMF
responsibilities are fixed in [docs/ROADMAP.md](docs/ROADMAP.md).

## Current boundaries and out of scope

The following are not goals of the current optimisation phase:

- live voice calls, continuous PTT or media streaming over shared LoRa airtime;
- arbitrary large-file transfer or bulk traffic over public Meshtastic MQTT;
- production SLA, safety certification or guaranteed emergency delivery;
- disk-backed replay of opaque Reticulum packets after their useful lifetime;
- same-channel multi-radio transmission intended to multiply LoRa bandwidth;
- automatic provisioning or modification of radio region, preset, channel keys
  or MQTT settings;
- exposing the Reticulum TCP server directly to the public Internet;
- replacing Meshtastic routing or maintaining a custom Meshtastic firmware fork.

Small files, location updates and short voice notes can be explored as bounded,
queued LXMF objects, but their feasibility depends on measured airtime and must
not starve announces, delivery proofs or short text messages.

## PortNum choice

Port 76 is the registered Meshtastic application identifier for a Reticulum
Network Stack tunnel and remains the interoperable default. MQTT is another
transport path for the same Meshtastic `Data` packet, not an alternative to its
PortNum. A private deployment can technically use a number in Meshtastic's
private range 256–511 if every endpoint is changed together, but that is not
wire-compatible with existing Reticulum-over-Meshtastic implementations and is
not currently exposed as a configuration option.

Named ports such as `TEXT_MESSAGE_APP`, `IP_TUNNEL_APP` and `SERIAL_APP` have
different payload and module semantics and are not substitutes for port 76.
The authoritative assignments and ranges are in the
[Meshtastic PortNum schema](https://github.com/meshtastic/protobufs/blob/master/meshtastic/portnums.proto).

## Quick start

Use Devbox so the host does not need a global Python, Reticulum or Meshtastic
installation:

```bash
devbox shell
devbox run test
uv run rns-meshtastic fragment-selftest
```

Inspect a Wi-Fi-connected Meshtastic radio without modifying it:

```bash
uv run rns-meshtastic radio-info --tcp-host 192.168.1.50
```

Run a Linux gateway by copying an appropriate configuration from `examples/`,
installing the loader shim into that Reticulum configuration directory and
starting `rnsd`:

```bash
uv run rns-meshtastic install-interface --config-dir ./var/rns-gateway
uv run rnsd --config ./var/rns-gateway -v
```

Documentation by scenario:

- [Architecture](docs/ARCHITECTURE.md)
- [Linux, radio and MQTT testing](docs/TESTING.md)
- [Android bridge testing](docs/ANDROID_TESTING.md)
- [Direct Android broadcast](docs/DIRECT_ANDROID_BROADCAST.md)
- [Android background operation](docs/ANDROID_BACKGROUND.md)
- [Post-MVP roadmap](docs/ROADMAP.md)

## Safety and network policy

- Prefer a private Meshtastic channel and enable Reticulum IFAC
  (`network_name` + `passphrase`).
- Use a LAN or VPN for the Reticulum TCP server.
- Do not use public Meshtastic MQTT for Reticulum bulk traffic.
- Set `mqtt_downlink_hops` to `0` for a broker/network with a zero-hop policy,
  or to `1`–`3` only where the broker and local mesh explicitly permit RF
  propagation. The implementation does not assume all brokers enforce zero-hop.
- Use one MQTT downlink gateway per overlapping RF island. Multiple subscribed
  downlink gateways can transmit duplicate packets at the same time.

## License

GPL-3.0-or-later. The fragment wire format is compatible with the earlier
`landandair/RNS_Over_Meshtastic` project; this implementation is a rewrite with
separate transport and protocol layers.
