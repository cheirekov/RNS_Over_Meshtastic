# Optional Linux transport and LXMF propagation service

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

## Configure

Docker with Compose v2 is the only host requirement. Create a private env file:

```bash
cp examples/linux-service.env.example .env.linux-service
chmod 600 .env.linux-service
```

Replace every `CHANGE-ME` value. At minimum set:

```dotenv
MESHTASTIC_TCP_HOST=192.0.2.10
RNS_ALLOWED_NODES=!a1b3b3b8

RNS_RADIO_IFAC_NAME=private-radio-segment
RNS_RADIO_IFAC_PASSPHRASE=long-random-radio-passphrase
RNS_TCP_IFAC_NAME=private-vpn-segment
RNS_TCP_IFAC_PASSPHRASE=different-long-random-vpn-passphrase
```

The radio-side and TCP-side IFAC credentials must be different. The renderer
rejects placeholders, missing unicast allowlists, malformed Node IDs, reused
IFAC pairs and unsafe multiline/comment characters before either daemon starts.
Secrets are not printed in logs or status helpers.

`gateway_unicast`/`hub` is the conservative default. `RNS_ALLOWED_NODES` is the
comma-separated list of Android or other client radio Node IDs. Use `broadcast`
only as an explicit compatibility test, together with `RNS_GATEWAY_ROLE=client`;
it does not require an allowlist. A `gateway_unicast` client instead requires
`RNS_GATEWAY_NODE=!aabbcc11`.

The TCP listener is published on host loopback by default. For a VPN address:

```dotenv
RNS_TCP_PUBLISH_IP=172.16.16.10
```

Do not publish port 4242 directly to the public Internet. Keep a host firewall
and use the configured 128-bit TCP IFAC.

## Start and inspect

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml up -d --build rnsd lxmd
docker compose --env-file .env.linux-service -f compose.linux.yaml ps
docker compose --env-file .env.linux-service -f compose.linux.yaml logs --tail 100 rnsd lxmd
```

Run the one-shot status helpers:

```bash
docker compose --env-file .env.linux-service -f compose.linux.yaml \
  --profile tools run --rm rns-status

docker compose --env-file .env.linux-service -f compose.linux.yaml \
  --profile tools run --rm lxmd-status
```

Expected RNS status includes the Meshtastic hub, the LAN/VPN TCP server and the
shared local instance. `lxmd-status` prints the propagation destination and
store/peer information. `LXMD_AUTOPEER=no` is deliberate: it prevents an
unbounded peering/sync workload on the constrained LoRa segment.

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

For a private IFAC-only pilot, `LXMD_AUTH_REQUIRED=no` is sufficient. If the
node becomes reachable outside that controlled RNS segment, set:

```dotenv
LXMD_AUTH_REQUIRED=yes
LXMD_ALLOWED_IDENTITIES=0123456789abcdef0123456789abcdef
```

The values are Reticulum identity hashes allowed to query/download from this
node, not Meshtastic Node IDs.

## Client and offline acceptance test

1. Connect a Sideband/Columba client to the host/VPN TCP port with the TCP-side
   IFAC. A radio-side client uses the separate radio IFAC.
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

## Backup, upgrade and rollback

The managed config contains IFAC secrets and the LXMF volume contains encrypted
messages and node identity material. Store backups with restrictive access:

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
