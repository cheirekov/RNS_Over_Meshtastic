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
| Android auto multi-peer | Two-peer laboratory acceptance complete | Learned routing, PKI unicast, images and PTT passed without admission, reassembly or delivery loss; three-peer and multi-hop field validation remain. |
| Serialized small resources | Laboratory acceptance complete | RNS frames of 3,747 B/19 fragments and 7,907 B/40 fragments crossed pure LoRa with proofs and no missing fragments. |
| Queue, power and background hardening | Active | Bounded queues, pacing and Android foreground operation are implemented; longer field tests continue. |
| Linux LXMF propagation service | Implemented, field acceptance pending | Reproducible non-root `rnsd` + `lxmd` containers, persistent state and conservative quotas are available. |
| Public-network boundary | Implemented, acceptance active | Up to eight explicit outbound upstreams use `boundary`; radio remains `internal`, while opt-in LAN public visibility and baseline/delta traffic reporting are available. |
| iOS bridge | Feasibility and delivery plan complete | The supported direction is an in-process interface inside an iOS Reticulum/LXMF client, not a standalone cross-app background bridge. |
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
- broadcast, configurable gateway-unicast and first-class bounded
  `auto_multi_peer` hub operation;
- one logical Reticulum child interface per unicast Meshtastic peer;
- a Reticulum TCP server for LAN/VPN clients without their own radio;
- a binary MQTT virtual node using Meshtastic `ServiceEnvelope` protobufs;
- legacy-compatible two-byte fragmentation, missing-fragment requests and a
  bounded retransmission cache;
- optional Reticulum IFAC isolation with `network_name` and `passphrase`;
- an optional Docker service profile with separate `rnsd` and `lxmd` processes,
  persistent identities/message storage and no host-side Python installation.
- optional outbound public `BackboneInterface` connections with strict endpoint
  validation, Reticulum `boundary` policy and no automatic internal-announce
  export; a persistent baseline/delta report separates LoRa, public and private
  TCP RNS payload counters without double-counting dynamic radio peers;
- opt-in public announce visibility for trusted LAN/VPN clients while the
  Meshtastic radio stays isolated as `internal`.

Android bridge 0.2.2 provides:

- a direct BLE or TCP PhoneAPI connection without depending on the official
  Meshtastic Android app;
- a loopback-only Reticulum TCP endpoint for Sideband, Columba and compatible
  clients on the same phone;
- gateway-unicast operation through a Linux radio and direct broadcast
  operation between Android radios without a Linux gateway;
- an `auto_single_peer` mode that broadcasts only RNS announces and sends all
  later data/link/proof frames by Meshtastic unicast to one configured peer,
  while preserving the original RNS FIFO order;
- an experimental bounded `auto_multi_peer` mode: announce, group and unknown
  destinations use channel broadcast; the bridge learns volatile mappings from
  RNS destination, packet-proof and link hashes to Meshtastic Node IDs and uses
  PKI unicast for known peers. The table is limited to 32 peers/512 routes with
  a 24-hour idle expiry and an optional Meshtastic Node-ID allowlist;
- IFAC-safe addressing: opaque frames stay unicast in explicit
  `auto_single_peer`, while `auto_multi_peer` uses broadcast because the hidden
  destination hash cannot safely select one of several radio peers;
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
  an unknown result after ACK timeout from Reticulum/LXMF delivery proofs;
- synchronous accounting of BLE GATT write completion and two bounded local
  retries, so a late GATT rejection is not silently counted as a sent fragment;
- explicit `adaptive`, `off`, `critical` and diagnostic `all` Meshtastic ACK
  policies. `adaptive` starts with the same sparse selection as `critical`, but
  suspends radio ACK requests for five minutes when their return path is
  demonstrably unhealthy. Broadcast never requests radio ACKs;
- persistent PhoneAPI rejection telemetry, including explicit
  `DUTY_CYCLE_LIMIT (9)` and `RATE_LIMIT_EXCEEDED (38)` results; regulatory
  duty-cycle override is never enabled by the bridge;
- explicit admission diagnostics plus a bounded serialized-bulk path for one
  larger RNS frame at a time. At the default 200-byte/2000-ms settings, normal
  admission is 600 bytes and serialized admission is at most 8 KiB (about 82
  seconds of fragment pacing), while repair control retains reserved capacity;
- path diagnostics that distinguish direct MQTT reception from an
  MQTT-origin packet whose final delivery mechanism was LoRa (`MQTT→LoRa`);
- an `inherit / force_off` MQTT forwarding policy for bridge packets. It can
  reduce the radio's `OK-to-MQTT` permission for controlled pure-LoRa tests but
  can never grant MQTT forwarding against the radio owner's setting;
- a volatile five-minute inbound spool (32 frames, 64 KiB) for a short local
  Sideband/Columba disconnect, with FIFO replay, deduplication, expiry and
  rejection counters. This is not persistent LXMF store-and-forward;
- bounded periodic repair of stalled assemblies, including a compatible `REQ`
  for an unknown/lost final fragment. Each missing position gets at most three
  attempts with exponential backoff, all assemblies share a 12-request rolling
  minute budget, and queue-full repair is deferred without consuming an
  attempt. Control and data transmissions are interleaved so repair cannot
  starve new RNS frames;
- separate data/control admission counters and a **Copy diagnostics** report
  containing an ISO timestamp, app version, Android/device identity and the
  complete visible status without radio or IFAC secrets;
- a visible app version plus per-start session ID, monotonic uptime and
  duplicate-suppressed radio/RNS-client up/down counters for background soak
  and restart diagnosis;
- current and peak bridge/device queue occupancy, retained until the bridge is
  restarted so a post-test report does not lose the burst high-water mark;
- a Reticulum frame-type breakdown and a rolling 60-second count of actual
  Meshtastic data/repair fragments, so channel utilisation can be correlated
  with announce, proof, link and user-data traffic without inspecting secrets;
- a reversible `constrained_auto / transparent` scheduler profile. Both keep
  the causal FIFO order of the raw Reticulum stream. `constrained_auto` only
  stretches fragment pacing before the Meshtastic firmware queue fills;
  `transparent` uses the configured fixed interval.

Version 0.2.1 corrects the Reticulum destination-type wire constants used by
`auto_multi_peer`. Normal `SINGLE` LXMF traffic is now eligible for a learned
Meshtastic unicast route instead of being mistaken for `PLAIN` traffic and
broadcast. It also hardens the Linux native PhoneAPI backend: a lost TCP/serial/
BLE session is replaced completely with bounded reconnect backoff, queued
frames wait for the replacement transport, and dynamic radio-peer interfaces
track the physical parent state instead of remaining falsely `Up`.

Version 0.2.2 corrects PhoneAPI transmit-queue diagnostics. Firmware
`QueueStatus.res` uses the firmware `ERRNO` namespace, not the protobuf
`Routing.Error` enum: result 35 is the successful `ERRNO_SHOULD_RELEASE`, not
`PKI_UNKNOWN_PUBKEY`. The bridge now counts only genuine queue rejection
results as device rejects and reports the last queue result separately. Actual
Meshtastic routing NAKs remain visible and authoritative in the radio-ACK line.

The `10 Mbps` value that a Reticulum client displays belongs to the standard
local TCP interface and is not an estimate of LoRa throughput. It is not purely
cosmetic, however: Reticulum also uses the interface bitrate when calculating
initial packet-proof and link-establishment timeouts. Android 0.1.11+ therefore
keeps only a near-interface radio queue and applies TCP backpressure early,
instead of acknowledging minutes of work into a slow LoRa scheduler. Existing
Sideband/Columba TCP configuration has no standard bitrate negotiation field;
the remaining timeout mismatch is tracked as an upstream-integration issue.
Recent Sideband versions create a high-speed Backbone client whose automatic
hardware MTU can be many kilobytes. Android 0.2.0 can serialize one bounded
frame beyond the short normal queue, but this does not change Reticulum's TCP
bitrate/timeout estimate and does not make LoRa a bulk medium. Frames beyond the
reported serialized limit are rejected locally before any Meshtastic fragment
is sent.

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
- two Android 0.2.0 bridges in `auto_multi_peer` mode, learning 96/158 volatile
  RNS routes and moving most post-discovery traffic by direct Meshtastic PKI
  unicast with zero route conflicts;
- operation with and without Reticulum IFAC;
- bidirectional images up to a measured 7,907-byte/40-fragment RNS frame and
  PTT in a same-room pure-LoRa test, with end-to-end confirmations and no
  admission rejection, incomplete assembly or expired frame. General file and
  real-field media delivery remain experimental and are not claimed;
- Android foreground operation on Pixel Android and Honor MagicOS after the
  required OEM battery permissions were granted;
- MQTT downlink with a non-zero hop limit on a broker whose deployment permits
  it, including a returned Meshtastic routing ACK.

Automated validation currently contains 76 Python tests and 75 Android unit
tests, in addition to Android lint and containerised APK builds. Exact,
repeatable procedures and the distinction between native Meshtastic DM and the
decoded MQTT virtual-node path are in [docs/TESTING.md](docs/TESTING.md).

## Active optimisation and next work

Work after the MVP is deliberately measurement-driven:

1. Run two-phone and phone-to-Linux background soak tests, including radio
   disconnect/reconnect, screen-off operation, queue peaks, delivery latency
   and battery drain on more Android vendors.
2. Characterise useful payload sizes, safe pacing and TCP-backpressure latency
   for the supported modem presets; evaluate a standard way for clients to
   represent the constrained segment instead of the fixed TCP bitrate guess.
3. Field-validate the new radio ACK/NAK telemetry against Reticulum/LXMF proofs
   and tune the constrained return path without treating an ACK timeout as
   proof that the payload was not delivered.
4. Field-validate `auto_multi_peer` with three or more Android radios, including
   route learning/expiry, unknown-destination broadcast, allowlists and the
   documented IFAC broadcast fallback.
5. Repeat the accepted Linux `auto_multi_peer` hub path with several Android
   radio peers and an explicit allowlist. Initial peer discovery must be
   broadcast; learned return paths must appear as per-radio child interfaces
   and Meshtastic unicast. Keep TCP/PhoneAPI reconnect in the soak test.
6. Field-characterise the accepted serialized-bulk path over one and two LoRa
   hops: drain time, proof return, channel utilisation, reconnect behaviour and
   the 8 KiB oversize boundary. Treat PTT as bounded store-and-forward audio,
   not live voice capacity.
7. Field-validate the implemented LXMF propagation node with one bounded
   offline short-text offer/retrieval cycle. Persistent replay of arbitrary raw
   Reticulum frames is intentionally not the design.
8. Evaluate multiple Linux radios first as active/passive failover or receive
   diversity. Same-channel bandwidth aggregation is not assumed to be safe or
   useful.
9. Run the controlled one-upstream `internal`/`boundary` acceptance and compare
   baseline/delta LoRa counters before adding each further public server. Do not
   expose the private TCP listener publicly.
10. Begin the iOS adapter with shared binary fixtures and a foreground TCP
   proof against an existing iOS Reticulum/LXMF client; BLE/background work
   follows only after that proof.
11. Polish release packaging, upgrade paths, diagnostics and field-test
   reporting before declaring a stable release.

See [docs/CAPACITY_AND_STORE_FORWARD.md](docs/CAPACITY_AND_STORE_FORWARD.md)
for the queueing, file, location, voice-note and multi-radio design assessment,
and [docs/ANDROID_BACKGROUND.md](docs/ANDROID_BACKGROUND.md) for the current
power model and soak-test checklist.

The optional reproducible `rnsd` + `lxmd` Linux service profile, its conservative
LoRa quotas, security boundary, backup procedure and offline-message acceptance
test are documented in [docs/LINUX_SERVICE.md](docs/LINUX_SERVICE.md).

The selected public-network boundary, strict-isolation alternative and iOS
integration effort are documented in
[docs/PUBLIC_BOUNDARY_AND_IOS.md](docs/PUBLIC_BOUNDARY_AND_IOS.md).

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
