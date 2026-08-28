# Columba direct Meshtastic interface specification

Status: decision-complete design for Milestone 0.4; no Columba source is
modified by this repository.

## Decision and upstream gate

The desired operator experience is a native **Meshtastic** entry in Columba's
interface manager, without the standalone bridge. Columba is the right first
client because its recommended build already embeds the official Python
RNS/LXMF implementation through Chaquopy and has typed interface configuration,
RNode wizards and Android transport adapters.

Direct integration is technically feasible, but it is **not currently cleared
for implementation by copying this project**:

- Columba is MPL-2.0 and this repository is GPL-3.0-or-later.
- Columba's maintainer has already identified bundling the existing GPL Python
  interface in the same APK as a licence blocker in
  [discussion #413](https://github.com/torlando-tech/columba/discussions/413).
- The current Columba Python backend deliberately limits Python-side authored
  code to RNS/LXMF and transport adapters. Adding a new external interface must
  be accepted as part of that architecture, not injected as an opaque facade.

Therefore the first upstream action is a short proposal asking whether the
maintainer will accept one of these explicit paths:

1. a separately authored adapter under a licence accepted by Columba and all
   relevant rightsholders;
2. a clean-room implementation from the public port 76 specification and test
   vectors, without copying GPL source;
3. continued companion mode only.

Until this is answered, the standalone bridge on `127.0.0.1:7822` remains the
supported Columba path. This is a legal/maintainer gate, not a transport defect.

## Target configuration model

After upstream approval, add one parcelable `InterfaceConfig.Meshtastic`
variant and a `MESHTASTIC` interface classifier. Persist only settings, never
discovered public keys or Reticulum route state.

| Field | Type / default | Meaning |
| --- | --- | --- |
| `connectionType` | `TCP` then `BLE` | PhoneAPI transport owned by Columba |
| `tcpHost` / `tcpPort` | host, `4403` | Dedicated Wi-Fi Meshtastic radio |
| `bleAddress` | Android device address | Selected BLE PhoneAPI peripheral |
| `channelIndex` | integer `0..7` | Slot for channel broadcast only |
| `hopLimit` | integer `0..7`, recommended `3` | Meshtastic hop limit |
| `meshMode` | `auto_multi_peer` | Announce/unknown broadcast, learned destinations unicast |
| `allowedNodes` | optional Node-ID list | Ingress peer allowlist |
| `mqttPolicy` | `inherit` / `force_off` | Never grants OK-to-MQTT against radio policy |
| `trafficProfile` | `constrained_auto` | FIFO plus bounded adaptive pacing |
| `ifacName` / `ifacPassphrase` | optional pair | Reticulum IFAC; secret storage required |
| advanced framing | frozen defaults | Port 76 body, queue and repair limits |

The UI must label the interface as constrained LoRa, disable realtime-call
expectations and warn before image/PTT/resource transfer. Meshtastic radio ACK
must never be displayed as an LXMF delivery receipt.

## Stage 1 — direct TCP PhoneAPI

Scope is a Wi-Fi radio only. This proves in-process ownership without Android
BLE lifecycle complexity.

1. Add the typed config model, database migration, JSON/AIDL round trip,
   validation and a Meshtastic wizard.
2. For Columba's official Python backend, deploy the approved interface module
   in the same controlled external-interface directory used by other required
   Python RNS adapters.
3. Render an RNS interface section that selects `auto_multi_peer`, TCP host,
   port, channel, hops, MQTT policy, allowlist and optional IFAC.
4. Install the approved Python package/dependencies only in the Python backend
   flavour. The experimental Kotlin Reticulum backend must report this
   interface as unsupported until it has a native counterpart.
5. Map interface online/offline, peer count, queue pressure and constrained
   capacity into Columba's existing status/statistics surfaces.

Columba must exclusively own PhoneAPI port 4403 while this interface is active.
The standalone bridge and official Meshtastic app must be stopped for that
radio.

## Stage 2 — BLE PhoneAPI

BLE uses a small Kotlin-to-Python byte transport because Chaquopy cannot rely
on pyjnius for Android BLE. It follows the same lifecycle pattern as Columba's
RNode adapters but speaks Meshtastic PhoneAPI:

```text
Kotlin BLE foreground lifecycle
        ⇅ length-delimited PhoneAPI protobuf bytes
approved Python RNS Meshtastic interface
        ⇅ frozen port 76 fragments
official Python RNS transport
```

The Kotlin side owns permission, scan/select, one GATT operation at a time,
notifications, reconnect backoff and screen-off lifecycle. The Python side owns
RNS packet classification, multi-peer route learning, fragmentation, repair,
admission and telemetry. There must be exactly one queue/backpressure authority;
the adapter cannot buffer an additional unbounded copy.

## Interoperability boundary

The normative wire contract remains [PORT76_PROTOCOL.md](PORT76_PROTOCOL.md)
and the binary fixtures under `tests/vectors/`. An independent Columba
implementation must pass the same encode/decode, final-fragment repair,
destination-type and proof/link-route vectors before radio testing. Additive
telemetry may differ; bytes emitted on Meshtastic port 76 may not.

## Acceptance gates

- Licence path and file boundaries accepted in writing by Columba maintainers.
- TCP stage passes Android emulator/unit tests and one dedicated Wi-Fi radio.
- BLE stage passes permission denial, reconnect, screen-off and radio handover.
- Two Columba direct interfaces learn each other in `auto_multi_peer` and
  exchange announce, numbered short LXMF, proof, small image and short PTT.
- Columba direct ↔ standalone bridge ↔ Linux gateway interoperability passes.
- Public upstream announce storm does not produce LoRa TX.
- Oversize resource is rejected with a useful constrained-transport warning.
- No channel keys, IFAC passphrases, payloads or signing material appear in
  logs, crash reports or telemetry.

## Upstream files expected to change

Exact paths may evolve, but the current Columba architecture places the work
in these boundaries:

- `rns-api`: parcelable `InterfaceConfig` variant and backend capability;
- `data`: persisted interface type/config migration;
- `app`: wizard, validation, status and constrained-traffic UX;
- `rns-backend-py`: approved external interface deployment and RNS config
  rendering;
- Kotlin Android transport module: BLE PhoneAPI adapter in Stage 2 only;
- tests: AIDL/JSON/config round trips, lifecycle and interoperability vectors.

This assessment was checked against Columba commit
`ca437686ca75848e3112efd93b1beacd42c1bb6f` and its
[official repository](https://github.com/torlando-tech/columba). Re-check the
upstream tree and licences immediately before proposing code.
