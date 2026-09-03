# Optional Linux transport and LXMF propagation service

For the 0.5.0 operator UI, policy profiles, metrics and explicit staged
apply/rollback workflow, see [GATEWAY_CONSOLE.md](GATEWAY_CONSOLE.md).

This profile runs two separate processes without installing RNS, LXMF or the
Meshtastic Python library on the host:

- `rnsd` owns the Meshtastic PhoneAPI radio, the Reticulum transport instance
  and the LAN/VPN TCP listener;
- `lxmd` connects to that shared Reticulum instance and provides encrypted LXMF
  propagation storage.

The containers share only the network namespace required for Reticulum's local
shared instance. RNS and LXMF state live in separate persistent Docker volumes.
This is intentionally not a disk spool of opaque Reticulum frames: message
expiry, retrieval and encryption remain LXMF responsibilities.

The image is reproducible from `uv.lock` and currently pins LXMF 1.1.1. Both
services run as an unprivileged UID after rendering their managed config. Linux
capabilities are dropped and the container root filesystems are read-only.
All Compose services use the same locally tagged image, and only `rnsd` renders
the shared managed profile. This prevents an older `lxmd` or one-shot status
container from overwriting a newly generated config during an upgrade.

## Configure

Docker with Compose v2 is the only host requirement. Create a private env file:

```bash
cp examples/linux-service.env.example .env.linux-service
chmod 600 .env.linux-service
```

At minimum set the radio host. The conservative fixed-peer hub also requires an
allowlist:

```dotenv
MESHTASTIC_TCP_HOST=192.0.2.10
RNS_ALLOWED_NODES=!a1b3b3b8
```

Radio-side and TCP-side IFAC are independently optional. To disable one, leave
both its name and passphrase empty. This is the simplest controlled baseline:

```dotenv
RNS_RADIO_IFAC_NAME=
RNS_RADIO_IFAC_PASSPHRASE=
RNS_TCP_IFAC_NAME=
RNS_TCP_IFAC_PASSPHRASE=
```

For an IFAC-enabled deployment, set both values in each required pair. If both
interfaces use IFAC, keep their credential pairs different:

```dotenv
RNS_RADIO_IFAC_NAME=private-radio-segment
RNS_RADIO_IFAC_PASSPHRASE=long-random-radio-passphrase
RNS_TCP_IFAC_NAME=private-vpn-segment
RNS_TCP_IFAC_PASSPHRASE=different-long-random-vpn-passphrase
```

The renderer rejects half-configured IFAC pairs, short/placeholding enabled
passphrases, missing unicast allowlists, malformed Node IDs, reused IFAC pairs
and unsafe multiline/comment characters before either daemon starts. Secrets
are not printed in logs or status helpers.

`gateway_unicast`/`hub` is the conservative default. `RNS_ALLOWED_NODES` is the
comma-separated list of Android or other client radio Node IDs. Use `broadcast`
only as an explicit compatibility test, together with `RNS_GATEWAY_ROLE=client`;
it does not require an allowlist. A `gateway_unicast` client instead requires
`RNS_GATEWAY_NODE=!aabbcc11`.

For scalable Android `auto_multi_peer` ingress use:

```dotenv
RNS_MESH_MODE=auto_multi_peer
RNS_GATEWAY_ROLE=hub
RNS_GATEWAY_NODE=
RNS_ALLOWED_NODES=
RNS_MAX_PEERS=32
RNS_PEER_ANNOUNCE_IDLE_SECONDS=900
```

This is a first-class Linux hub mode, not broadcast compatibility mode. An
Android bridge sends its initial announce or an unknown destination by channel
broadcast. Linux accepts it, creates one Reticulum child interface for that
Meshtastic source Node ID, and then replies through that child as a Meshtastic
unicast DM. Reticulum route learning remains attached to the child interface,
so many TCP clients and many radio bridges can share the same Linux Transport
Node without collapsing into one radio path.

An empty `RNS_ALLOWED_NODES` is accepted only in explicit `auto_multi_peer`
mode and means open radio discovery, bounded by `RNS_MAX_PEERS` (1–512, default
32). In a private deployment set a comma-separated allowlist; discovery still
uses broadcast on air, but non-allowlisted source radios are discarded before
fragment reassembly or peer creation. `gateway_unicast`/`hub` continues to
require a non-empty allowlist and continues to reject broadcast.

Linux вижда състоянието на Meshtastic радиото, но няма надежден сигнал, че
Android bridge приложението зад научен peer е натиснало `Stop`. Затова след 15
минути без входящ port-76 трафик към peer-а default policy спира **само**
обикновените context-0 периодични RNS announces към него. Data, proofs,
link/path traffic, explicit path responses и IFAC-opaque frames продължават.
Първият входящ frame след Android reconnect автоматично активира announce
доставката. `RNS_PEER_ANNOUNCE_IDLE_SECONDS=0` изключва тази защита; други
стойности са 300–86400 секунди.

The TCP listener is published on host loopback by default. For a VPN address:

```dotenv
RNS_TCP_PUBLISH_IP=172.16.16.10
```

Do not publish port 4242 directly to the public Internet. Without TCP IFAC,
bind only to a trusted LAN/VPN address and enforce access with the host
firewall. IFAC is strongly recommended beyond a controlled baseline.

## Public Reticulum connectivity

Do not add a public peer as another unrestricted `gateway` beside the radio.
The managed profile accepts an explicit comma-separated endpoint list:

```dotenv
RNS_PUBLIC_UPSTREAMS=193.193.182.147:4242
```

With an empty value, public connectivity is disabled and the existing radio and
LAN interfaces remain `gateway`. With one or more endpoints, the renderer makes
the Meshtastic and private LAN/VPN interfaces `internal`; each outbound
`BackboneInterface` is `boundary` and has
`announces_from_internal = No`. Public announces are therefore not automatically
rebroadcast over LoRa, and radio/LAN announces are not automatically exported.
A private client can still request a specific outside path on demand.

To make public announces visible only to trusted LAN/VPN clients, opt in with:

```dotenv
RNS_LAN_PUBLIC_VISIBILITY=yes
```

This keeps the Meshtastic radio `internal`, changes only the LAN/VPN listener to
`gateway`, and leaves every public uplink as `boundary`. Public announces can
then reach LAN clients without being forwarded to LoRa. It does not override
`announces_from_internal = No`: LAN/radio announces are not exported to a
boundary. Keep the listener private and use `no` when LAN clients must not see
external boundary announces.

Add at most eight explicitly permitted `host:port` endpoints, one at a time.
IPv6 literals use `[address]:port`. URLs, credentials, comments, duplicates,
invalid hosts and invalid ports are rejected before `rnsd` starts. The private
TCP listener must remain bound to a trusted LAN/VPN address; the public entries
are outbound connections and do not publish a new host port.

`mode` and IFAC are routing/isolation controls, not an application firewall.
The one-instance caveat and the two-instance strict-isolation alternative are
documented in [PUBLIC_BOUNDARY_AND_IOS.md](PUBLIC_BOUNDARY_AND_IOS.md).

## Private IFAC boundary upstreams

Private upstreams use the same bounded `boundary` policy as public upstreams,
but require a separate IFAC shared by all private endpoints in this gateway
instance:

```dotenv
RNS_PRIVATE_UPSTREAMS=private-a.example:4242,private-b.example:4242
RNS_PRIVATE_UPSTREAM_IFAC_NAME=home-backbone
RNS_PRIVATE_UPSTREAM_IFAC_PASSPHRASE=replace-with-a-separate-long-secret
```

This authenticates the private backbone but does not make it an unrestricted
Reticulum gateway. Its IFAC pair must differ from the radio and LAN IFAC pairs.
Console and `traffic-report` account private-boundary traffic separately.

With `RNS_PUBLIC_DISCOVERY=auto` or `trusted_auto`, explicit endpoints can be
temporary bootstrap connections. Each bootstrap list must be an exact subset
of its normal upstream list:

```dotenv
RNS_PUBLIC_BOOTSTRAP_UPSTREAMS=seed.example:4242
RNS_PRIVATE_BOOTSTRAP_UPSTREAMS=private-seed.example:4242
```

Reticulum closes bootstrap-only interfaces after a discovered interface is
connected. Bootstrap settings are rejected in `off` or `manual` discovery
modes.

## Start and inspect

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml up -d --build rnsd lxmd
docker compose --env-file .env.linux-service -f compose.linux.yaml ps
docker compose --env-file .env.linux-service -f compose.linux.yaml logs --tail 100 rnsd lxmd
```

Inspect the running containers directly so status always uses the same image as
the daemons:

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml exec -T \
  rnsd rnstatus --config /data/rns --all --totals

docker compose --env-file .env.linux-service -f compose.linux.yaml exec -T \
  lxmd lxmd --config /data/lxmd --rnsconfig /data/rns --status
```

Expected RNS status includes the Meshtastic hub, the LAN/VPN TCP server and the
shared local instance. `lxmd-status` prints the propagation destination and
store/peer information. `LXMD_AUTOPEER=no` and `LXMD_FROM_STATIC_ONLY=yes` are
deliberate: they prevent automatic peering and reject propagation sync offers
from arbitrary public nodes. Direct client propagation delivery remains
available. Set explicit static peers only as a separate measured experiment.

### Public/LoRa traffic baseline

The profile can store a counter snapshot in the persistent RNS volume and then
report the change over a test window. Save the baseline immediately before the
test:

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  run --rm traffic-baseline
```

After the idle or message test, print the delta:

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  run --rm traffic-report
```

The report separates `LoRa RNS payload`, `Public boundary aggregate`,
`Private IFAC boundary aggregate` and the private TCP listener, plus each
configured boundary connection. In
`auto_multi_peer`, inbound bytes live on dynamic peer interfaces while outbound
bytes also appear on them; the report deliberately adds peer RX but not peer TX
to the physical parent, avoiding double-counting. Values are RNS frame bytes
accepted by interfaces, not Meshtastic protobuf/framing overhead, RF airtime or
`ChUtil`. A daemon restart invalidates a baseline and is reported explicitly;
save a fresh baseline before continuing.

The native backend replaces the complete Meshtastic PhoneAPI session after a
TCP reset or write failure. A normal recovery sequence in `rnsd` logs is
`transport offline` followed by `transport connected`; reconnect attempts use
bounded exponential backoff up to 30 seconds. Existing dynamic peer interfaces
are marked down with the parent and restored after reconnect, while already
admitted outbound fragments remain in the bounded scheduler queue. If status
shows the parent `Down` for longer than the backoff while the radio TCP port is
reachable, capture the logs; restarting `rnsd` should be recovery, not the
normal operating mechanism.

## Conservative propagation limits

Defaults are intentionally small:

- 64 MiB total message store;
- 8 KiB maximum accepted propagation message;
- 64 KiB maximum inbound propagation sync;
- one concurrent inbound sync;
- four peers maximum, with automatic peering disabled;
- propagation announce at start and then every six hours.

These are admission limits, not an assertion that an 8 KiB transfer is
practical over a busy LoRa mesh. Begin with short text. Files, images and voice
notes remain a separate measured experiment.

For a private, controlled pilot, `LXMD_AUTH_REQUIRED=no` is sufficient. If the
node becomes reachable outside that controlled RNS segment, set:

```dotenv
LXMD_AUTH_REQUIRED=yes
LXMD_ALLOWED_IDENTITIES=0123456789abcdef0123456789abcdef
```

The values are Reticulum identity hashes allowed to query/download from this
node, not Meshtastic Node IDs.

## Client and offline acceptance test

1. Connect a Sideband/Columba client to the host/VPN TCP port. When IFAC is
   enabled on that interface, configure the matching credentials in the client.
2. Wait for or request the LXMF propagation-node announce and select that node
   in the client's propagation-node settings.
3. Exchange one ordinary online text first.
4. Take recipient B offline.
5. From A, send one short LXMF message using propagation delivery and wait for
   the client to finish offering it to the propagation node.
6. Bring B online and request a propagation-node sync.
7. Verify that B retrieves the message and that A/B report the appropriate LXMF
   state. A Meshtastic radio ACK is not an LXMF storage or retrieval receipt.
8. Capture `rns-status`, `lxmd-status` and the daemon logs. Do not start with a
   file or a message burst.

## Android auto-multi-peer ingress acceptance

This validates one Linux TCP client against one Android client attached to a
Meshtastic radio; a second phone is not involved.

1. Start `rnsd` and `lxmd` with Linux `RNS_MESH_MODE=auto_multi_peer`.
2. On Android select `auto_multi_peer`, the same logical Meshtastic channel,
   body 200, interval 2000 ms and the intended MQTT policy. Leave its fixed
   unicast-peer field unused.
3. Connect the Android Reticulum client to `127.0.0.1:7822`. Connect the other
   client directly to the Linux TCP listener, never through the Android bridge.
4. Announce from Android first. Linux must log `created Reticulum peer interface
   for !...`; `rnstatus` must show a `MeshtasticPeerInterface` for that radio.
5. Announce from the TCP client after peer creation. The Android client should
   receive it through the learned Linux radio peer.
6. Exchange one numbered short text in each direction and wait for LXMF proofs.
   Only then test a burst, propagation delivery or a small resource.

For Android 0.2.1+, once a route has been learned, ordinary single-destination
traffic should increase the Android diagnostic `unicast` counter. Announces,
Reticulum `PLAIN`/`GROUP`, unknown destinations and IFAC-opaque frames can still
increase `broadcast` by design. A populated peer table with sustained `N/0`
addressing during direct single-destination chat is a regression signal.

If an allowlist is enabled, it contains radio Node IDs such as `!a1b3b3b8`, not
Reticulum destination hashes. A TCP client announce sent before any radio peer
has introduced itself cannot be replayed to a nonexistent child interface; send
or wait for a fresh announce after peer creation.

## Backup, upgrade and rollback

The managed config can contain IFAC secrets, and the LXMF volume contains
encrypted messages and node identity material. Store backups with restrictive
access:

```bash
mkdir -p backup/linux-service/rns backup/linux-service/lxmd
chmod 700 backup/linux-service
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  cp rnsd:/data/rns/. backup/linux-service/rns
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  cp lxmd:/data/lxmd/. backup/linux-service/lxmd
```

For a normal upgrade, keep both named volumes and rebuild the image. Rollback
means stopping the stack and starting the previous image with the same volumes.
Do not delete the volumes unless the node identity, path state and propagation
store are intentionally being discarded.

Stop without deleting state:

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml down
```
