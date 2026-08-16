# Android bridge

This app exposes a Reticulum-compatible TCP interface on
`127.0.0.1:7822` and carries its frames over Meshtastic port 76. It talks
directly to a radio through TCP PhoneAPI or the Meshtastic BLE GATT profile;
the Meshtastic Android app is not required.

The bridge is intentionally loopback-only. Sideband or Columba runs on the
same phone and connects to it as a normal Reticulum TCP client.

Version 0.1.8 reports both bridge directions, the last accepted Meshtastic
peer/path metadata and optional per-fragment Meshtastic routing ACK/NAK. A
radio ACK is diagnostic information for one Meshtastic packet; it is not an
LXMF delivery receipt.

Version 0.1.9 waits for the Android BLE GATT write callback before removing a
fragment from the bridge scheduler. A rejected or timed-out local write is
retried at most twice and is exposed by the `local retries` counter; exhaustion
increments `dropped` instead of silently reporting the fragment as sent.

Version 0.1.10 adds `off`, `critical` and `all (diagnostic only)` ACK policies.
Use `critical` for the next unicast reliability test. It requests a Meshtastic
ACK only for the final fragment of each multi-fragment RNS frame and for
fragment repair control traffic. Single-fragment RNS frames use Reticulum's own
proof/retry behaviour. `all` reproduced severe queue/return-path contention
and is not a normal operating profile.

Version 0.1.11 moves TCP backpressure close to the constrained interface. At
the default 2000 ms pacing the data queue holds one maximum-size RNS frame,
instead of accepting roughly two minutes of traffic before slowing the local
Sideband/Columba producer. Test this version with ACK `off` first; `critical`
is a separate follow-up measurement.

Version 0.1.12 adds diagnostics without changing the port 76 wire format or
radio scheduling policy. It reports scheduler admission rejections separately
from local send failures, including the rejected frame's fragment/byte size.
It also reports active fragment assemblies, assemblies still awaiting their
final fragment, known missing fragments, repair requests/retransmissions,
expiry and duplicates.

This distinction matters for files and images. Current Sideband releases use a
high-speed Reticulum Backbone/TCP interface and can submit RNS resource frames
far larger than the bridge's deliberately short LoRa queue horizon. Such a
frame is rejected locally instead of being silently buffered for minutes; the
0.1.12 `admission` line makes that condition explicit. File/resource transfer
over the direct Android tunnel remains experimental. Version 0.2.0 below adds
a bounded one-frame serialized path, but does not resolve the Reticulum-facing
MTU/bitrate mismatch.

Version 0.1.13 separates MQTT origin from the final radio delivery mechanism,
so a packet forwarded by MQTT and finally received over LoRa is shown as
`MQTT→LoRa`. The PhoneAPI flag is labelled `OK-to-MQTT permission`: it permits
uplink but does not prove that the radio has an active broker session. A
completed inbound RNS frame can also wait in a volatile FIFO for up to five
minutes while the local Sideband/Columba TCP client reconnects. The FIFO is
bounded to 32 frames and 64 KiB, deduplicated and visible in status counters;
it is deliberately not a persistent mailbox or LXMF propagation node.

Version 0.1.14 adds periodic fragment repair. The legacy two-byte format learns
the fragment count only from the negative position on the final fragment. If
that fragment is lost, an updated receiver now sends `REQ` with position zero,
meaning "retransmit the final cached fragment". Updated Android and Linux
senders answer it, after which the existing missing-position repair completes
the frame. Position zero remains invalid for data, and older peers simply do
not answer this optional repair extension.

Version 0.1.15 hardens that extension for asymmetric or broken mesh paths.
Periodic requests are limited to three per unresolved position with 5/10/20
second backoff, and no more than one repair request is emitted per scheduler
pass. A `capped` counter exposes unresolved positions whose repair budget is
spent. Broadcast mode never requests Meshtastic radio ACKs, including for its
unicast repair control packets; otherwise firmware retries and protocol repair
can amplify each other when the reverse path is unavailable.

Version 0.1.16 adds congestion containment across all incomplete frames. Repair
requests share a 12-per-minute rolling budget, a full control queue defers a
repair without spending one of its three attempts, and the scheduler alternates
waiting repair control with normal data. Status now separates data loss from
control admission and exposes repair throttling/deferral. The optional
`adaptive` radio ACK policy starts with the sparse `critical` selection and
backs off for five minutes after 12 unconfirmed pending ACKs or a resolved
confirmation rate below 25%; Reticulum/LXMF proofs remain authoritative. Use
**Copy diagnostics** to copy a timestamped, versioned status report for field
tests.

Version 0.1.17 adds an `inherit / force_off` MQTT forwarding policy for bridge
packets. `inherit` preserves the radio owner's `config_ok_to_mqtt`; `force_off`
omits the packet permission even when the radio allows it. The bridge never
offers `force_on`. Status reports both the configured and effective policy and
retains bridge/device queue high-water marks until restart. Linux native
PhoneAPI uses the same `mqtt_forwarding_policy` values.

Version 0.1.18 adds secret-free Reticulum packet-type counters and a rolling
60-second radio fragment/byte window. It does not change fragmentation,
scheduling, ACK policy or the port 76 wire format; it makes background
announce/proof/link traffic distinguishable from user data and repair traffic.

Version 0.1.20 keeps strict FIFO causal order for every Reticulum frame. The
0.1.19 packet-type priority and independent announce spacing were withdrawn:
a same-room field test proved that later data could overtake path announces
and prevent the expected proof from returning. The default `constrained_auto`
profile now changes pacing only. PhoneAPI queue occupancy adds 0/1x/2x/3x of
the configured base interval before the next fragment at the 0/25/50/75%
queue thresholds, capped at eight seconds. Select `transparent` for FIFO with
the configured fixed base interval. Neither profile rewrites or discards RNS
frames.

Version 0.1.21 adds the bounded `auto_single_peer` addressing mode. It does not
reorder, delay by packet type or inspect LXMF content. RNS announces use the
configured channel broadcast, while every other RNS frame uses Meshtastic
unicast to the explicitly configured peer Node ID. Inbound port 76 traffic is
accepted only from that peer and only when addressed to broadcast or the local
radio. Diagnostics count accepted outbound broadcast and unicast RNS frames
separately. There is no automatic broadcast fallback after an ACK timeout.

Version 0.1.22 is an observability-only follow-up. The version and build code
are visible in the main screen, and each bridge start gets an eight-character
session ID with monotonic uptime plus radio and local-RNS-client up/down event
counters. Repeated identical callbacks are coalesced, and an initially
disconnected state is not counted as a disconnect. This distinguishes a real
empty run from diagnostics copied after `Save & start`, service recreation or
a transport reconnect. Radio framing, addressing, pacing and ACK behaviour are
unchanged from 0.1.21.

Version 0.2.0 adds bounded multi-peer learning and serialized resource
admission. In `auto_multi_peer`, RNS announces, group/plain packets and unknown
destinations remain channel broadcast. From valid inbound frames the bridge
learns destination hashes, reverse packet-proof hashes and link IDs and sends
later known traffic by Meshtastic unicast. The volatile table is bounded to 32
radio peers and 512 routes, expires idle entries after 24 hours and can be
restricted with the optional Node-ID allowlist. It does not inspect LXMF
content. In multi-peer mode, IFAC-masked RNS headers cannot be routed safely,
so they are reported as `opaque-ifac` and use broadcast fallback. Explicit
`auto_single_peer` remains unicast because its only peer is already known.

The normal default queue remains three data fragments/600 RNS bytes. One larger
frame can now be serialized without admitting a second large frame, while a
control fragment stays reserved for repair. At body 200 and interval 2000 ms,
the reported upper bound is 8 KiB/41 fragments (roughly 82 seconds before
adaptive delay and return traffic). The limit is on each RNS frame, not directly
on the attachment size. A larger frame is rejected locally and diagnostics say
explicitly that nothing reached Meshtastic.

The first 0.2.0 same-room pure-LoRa acceptance on 2026-08-16 passed with two
Android bridges in `auto_multi_peer`: both learned the other radio with zero
route conflicts or unknown-destination fallbacks, and 167 of 232 cumulative TX
RNS frames used learned PKI unicast after discovery. Images and PTT completed
with end-to-end confirmations. The largest observed outbound frames were
3,747 B/19 fragments and 7,907 B/40 fragments; serialized admission accepted
all five large frames across both directions, with zero oversize/data/control
rejection, incomplete assembly, expiry, device rejection or local send
failure. Queue peaks of 19 and 40 fragments are the intentionally serialized
single frame, not overflow of the normal four-fragment window. Backpressure
55/58 is likewise expected: it proves that later local TCP work was held behind
the radio serializer.

The Pixel radio reconnected twice (`radio up/down 3/2`) and both local RNS
clients reconnected during the run. No completed frame was lost; the bounded
inbound spool replayed nine frames on the Honor side and one on the Pixel side.
Reconnect frequency remains an explicit screen-off and multi-hop field-test
observation, not a closed reliability result.

## Reproducible build

No Android SDK or Gradle installation is required on the host:

```bash
cd android
docker compose build android-build
docker compose run --rm android-build
```

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Compose keeps Gradle's debug signing keystore in the
`android-debug-signing` Docker volume. Keep that volume to install later debug
builds as upgrades. Removing the volume changes the signing identity and then
Android requires the previously installed debug app to be uninstalled first.

Install it with an existing `adb`, or copy the APK to the phone and approve
installation from that source. The Docker image pins Gradle/AGP, Android API
35, build-tools 35.0.0, and verifies the official Android command-line-tools
archive by SHA-256.

## Radio transports

- **TCP:** enter the Wi-Fi radio address and port `4403`. Do not keep another
  PhoneAPI TCP client connected to that radio.
- **BLE:** enter the radio MAC address. Disconnect it from the official
  Meshtastic app first; a BLE peripheral cannot serve both apps at once.

The app requests only the runtime permissions required by the selected
transport. It runs as a foreground `connectedDevice` service. It deliberately
does not hold a permanent partial wake lock; Android's BLE stack and the
foreground service keep the connection lifecycle active without forcing the
CPU awake continuously.

See `../docs/ANDROID_TESTING.md` for the complete Linux gateway, Sideband and
hardware test sequence.
