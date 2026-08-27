# Public Reticulum boundary and iOS direction

This document records two design decisions that follow the Android 0.2.2 and
Linux `auto_multi_peer` field work. It is an architecture decision, not a claim
that the managed Docker profile already configures a public upstream or that an
iOS build exists in this repository.

## Home gateway connected to public Reticulum

### Recommended default: one Transport instance with a controlled boundary

The normal home deployment should use one `rnsd` Transport instance:

```text
trusted LAN/VPN clients
        │  internal
        │
        ├──────────────┐
        │              │
Meshtastic radio       │
auto_multi_peer        │
internal               ▼
                  home rnsd ───── boundary ───── trusted public upstream(s)
                       │
                       └── shared local instance ── lxmd
```

The intended interface policy is:

| Interface | Mode | Additional policy | Purpose |
| --- | --- | --- | --- |
| Meshtastic radio | `internal` | conservative announce cap and existing bounded scheduler | The constrained private segment. Dynamic Meshtastic peer interfaces inherit the parent mode. |
| Private LAN/VPN listener | `internal` | IFAC and host firewall as appropriate | Local clients are part of the same private side and can issue recursive path requests across the boundary. |
| Outbound public uplink | `boundary` | `announces_from_internal = no` | Public announces do not flood the internal LoRa segment, and private announces received on internal interfaces do not propagate to the public uplink. |
| Local shared instance | local | filesystem/process permissions | Lets `lxmd` use the same Transport without a network listener. |

This is the Reticulum-native solution for a LoRa network connected to a much
faster public network. `internal` interfaces can resolve a specific destination
across a `boundary` with a path request, even though public announces are not
automatically propagated into the internal segment. Setting
`announces_from_internal = no` on the public interface also suppresses automatic
propagation of announces learned from the radio or private LAN.

The effect is demand-driven connectivity: a private client can request and use
an outside path, but attaching a large public announce domain does not turn it
into continuous LoRa broadcast traffic. Return traffic for a path or session
that a private client deliberately opened is still allowed; otherwise the
outside service would not be usable.

The public uplink should be an outbound connection to a small, explicit set of
trusted upstreams. Prefer `BackboneInterface` where the remote supports it;
Reticulum recommends it over the older TCP interfaces for public entry points.
Do not publish the home LAN client listener directly on the Internet.

An illustrative future RNS configuration is:

```ini
[[Meshtastic radio hub]]
  type = RNSMeshtasticInterface
  enabled = yes
  mode = internal
  # Existing PhoneAPI, auto_multi_peer, pacing and IFAC settings follow.

[[Private LAN or VPN clients]]
  type = TCPServerInterface
  enabled = yes
  mode = internal
  listen_ip = 172.16.16.10
  listen_port = 4242
  # Use a private IFAC outside a controlled baseline.

[[Trusted public upstream]]
  type = BackboneInterface
  enabled = yes
  mode = boundary
  remote = example.public.transport
  target_port = 4242
  announces_from_internal = no
```

This fragment documents the target policy only. The managed Compose renderer
does not yet expose these public-uplink variables, and a real hostname must not
be added until its operator has explicitly permitted the connection.

### What this policy is not

Interface modes control path discovery and announce propagation. They are not
an application firewall. IFAC authenticates/isolate packets on one interface;
it does not by itself forbid a Transport instance from forwarding between two
valid interfaces.

The one-instance design also has a narrow caveat: destinations originating
locally on the home Transport instance, such as its own `lxmd` propagation
service, can announce on active interfaces. LXMF identity authentication and
small quotas protect mailbox access, but do not make the service invisible.

### Strict-isolation alternative

Use two instances only when the requirement is stronger than traffic control:

```text
radio + private clients ── private rnsd     public rnsd ── public upstreams
                              │                  │
                              └── no RNS link ───┘
```

The instances must not be joined by an ordinary TCP, Backbone, UDP or other RNS
interface. Joining them creates a normal forwarding path and defeats the hard
boundary. A user client may have one explicit interface to each independent
network, or a future application-level relay may copy only allowlisted LXMF
objects with identity, size, rate and expiry policy. `lxmd` is store-and-forward
for LXMF; it is not such a general cross-network policy relay.

Start with the single-instance `internal`/`boundary` design. Move to two
instances only if hiding private destinations or prohibiting all unsolicited
outside reachability is a stated security requirement.

### Acceptance gates before enabling a public uplink

1. Capture a five-minute idle baseline with no public uplink.
2. Add exactly one permitted upstream as `boundary`, with
   `announces_from_internal = no`.
3. Verify that public announce activity increases on the boundary interface but
   does not increase Meshtastic TX counters or radio channel utilisation.
4. From a private client, request one known public LXMF destination and exchange
   one short message. Only demand-driven path response/data/proof traffic should
   cross the radio.
5. From the public side, request an unannounced private radio destination. The
   Transport must not recursively search the internal radio segment for that
   boundary request.
6. Repeat with the public uplink disconnected and verify that private LoRa/LAN
   communication and local `lxmd` operation continue.
7. Keep public bulk, automatic public peering and unrestricted sync out of the
   acceptance run.

## iPhone/iPad feasibility

### Decision: integrate the bridge into an iOS Reticulum client

A separate iOS bridge process that serves another iOS app over loopback TCP is
not the production target. It can work while both apps are active, but iOS does
not provide Android-style indefinite foreground services, and a BLE event that
wakes the bridge does not guarantee that a separate LXMF client is running to
consume the loopback frame.

The production architecture should keep all three layers in one process:

```text
iOS Reticulum/LXMF client
        │ raw RNS interface callback
Meshtastic port-76 bridge core
        │ ToRadio / FromRadio protobuf stream
CoreBluetooth or TCP PhoneAPI
        │
Meshtastic radio
```

This is no longer a green-field app problem:

- the official Meshtastic Apple application contains a native Swift
  CoreBluetooth PhoneAPI transport, Swift protobuf models, restoration handling
  and TCP transport that can be used as implementation references;
- Reticulum Mobile App already has an iOS application, a shared Reticulum/LXMF
  stack, BLE/TCP transports, attachments and voice-note support, plus an
  App-Store-signed state-restoration path;
- Retichat iOS already embeds a Rust Reticulum/LXMF stack and native BLE/RNode
  interface callbacks.

The best first integration target is Reticulum Mobile App because it already
shares most protocol and application logic across Android/iOS and explicitly
supports iOS BLE state restoration. Retichat is a valid second adapter target.
The choice must be confirmed with a short API and licensing spike before code
is copied or coupled. This project is GPL-3.0-or-later, Reticulum Mobile App is
AGPL-3.0, Retichat is MIT, and Meshtastic Apple is GPL-3.0; distribution of a
combined application must honour the applicable copyleft terms.

### Reusable and platform-specific work

Reusable behaviour/specification:

- port 76 fragmentation, reassembly, bounded repair and retransmission cache;
- FIFO scheduler, queue admission, pacing and diagnostics semantics;
- `broadcast`, fixed unicast, `auto_single_peer` and `auto_multi_peer` routing;
- binary fixtures and golden vectors derived from the Android/Python tests.

iOS-specific implementation:

- SwiftProtobuf `ToRadio`/`FromRadio` framing over Meshtastic BLE and TCP;
- CoreBluetooth discovery, pairing, characteristic flow control, reconnect and
  state restoration;
- injection/extraction of raw RNS frames through the selected client's native
  interface API;
- lifecycle, local notifications, energy measurement and TestFlight/App Store
  packaging.

The existing Android bridge is a Java/Android service and cannot simply be
compiled for iOS. The protocol logic should be ported against shared golden
vectors, not translated line-by-line.

### Engineering estimate

For one experienced mobile/network developer, assuming collaboration with one
existing iOS Reticulum client:

| Phase | Expected effort |
| --- | --- |
| Target/API/licence spike and shared binary fixtures | 3–5 working days |
| Foreground TCP PhoneAPI + port-76 core + text interop | 1–2 weeks |
| BLE PhoneAPI, pairing, flow control and reconnect | 2–3 weeks |
| `auto_multi_peer`, queues/repair/diagnostics and small resources | 2–3 weeks |
| Background restoration, device tests, energy/soak and TestFlight hardening | 3–5 weeks |

A credible integrated beta is therefore roughly **8–12 person-weeks**, not a
weekend port. A standalone bridge intended to serve arbitrary separate iOS
clients would take at least as long and would retain the cross-app background
limitation, so it is not recommended. Building an entirely new iOS LXMF client
as well as the bridge would be a separate, much larger project.

The build/sign/test loop requires macOS with Xcode, an Apple developer identity
for durable background entitlements/TestFlight, and at least one physical
iPhone plus a Meshtastic radio. Linux CI can keep protocol fixtures and static
checks, but cannot replace device-level CoreBluetooth validation.

### iOS delivery order

1. Export platform-neutral PhoneAPI/port-76 fixtures from this repository.
2. Complete a time-boxed adapter spike against Reticulum Mobile App and
   Retichat; choose one target on API stability, licence fit and testability.
3. Prove foreground TCP interop with Android 0.2.2 and Linux
   `auto_multi_peer` before adding BLE.
4. Add BLE and same-room pure-LoRa text tests.
5. Add state restoration, reconnect, screen-off and forced-termination tests.
   A user force-quit must be documented as a platform stop condition.
6. Only then accept PTT/images and multi-hop/MQTT-assisted paths.

## Primary references

- [Reticulum interface modes and common options](https://github.com/markqvist/Reticulum/blob/master/docs/source/interfaces.rst)
- [Reticulum personal-infrastructure guidance](https://github.com/markqvist/Reticulum/blob/master/docs/source/gettingstartedfast.rst)
- [Apple Core Bluetooth background processing](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [Apple Bluetooth state-restoration relaunch rules](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)
- [Meshtastic Apple](https://github.com/meshtastic/Meshtastic-Apple)
- [Reticulum Mobile App](https://github.com/thatSFguy/reticulum-mobile-app)
- [Retichat iOS](https://github.com/jrl290/Retichat-ios)

