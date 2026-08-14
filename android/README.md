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
over the direct Android tunnel remains experimental until the Reticulum-facing
MTU/bitrate mismatch is resolved.

Version 0.1.13 separates MQTT origin from the final radio delivery mechanism,
so a packet forwarded by MQTT and finally received over LoRa is shown as
`MQTT→LoRa`. The PhoneAPI flag is labelled `OK-to-MQTT permission`: it permits
uplink but does not prove that the radio has an active broker session. A
completed inbound RNS frame can also wait in a volatile FIFO for up to five
minutes while the local Sideband/Columba TCP client reconnects. The FIFO is
bounded to 32 frames and 64 KiB, deduplicated and visible in status counters;
it is deliberately not a persistent mailbox or LXMF propagation node.

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
