"""Reticulum external interface implementation."""

from __future__ import annotations

import itertools
import json
import os
import queue
import tempfile
import threading
import time
from collections import deque
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import RNS
from RNS.Interfaces.Interface import Interface

from rns_meshtastic.addresses import BROADCAST_ID, format_node_id
from rns_meshtastic.framing import FragmentError, FragmentProtocol, Transmission
from rns_meshtastic.rns_metadata import is_opaque_ifac, parse_rns_frame
from rns_meshtastic.transports import MqttBackend, MqttConfig, NativeBackend, NativeConfig


def _bool(config: Any, key: str, default: bool = False) -> bool:
    if key not in config:
        return default
    if hasattr(config, "as_bool"):
        return bool(config.as_bool(key))
    return str(config[key]).strip().lower() in {"1", "yes", "true", "on"}


def _int(config: Any, key: str, default: int) -> int:
    return int(config[key]) if key in config else default


def _float(config: Any, key: str, default: float) -> float:
    return float(config[key]) if key in config else default


def _optional(config: Any, key: str) -> str | None:
    if key not in config:
        return None
    value = str(config[key]).strip()
    return value or None


def _node_set(value: str | None) -> set[str]:
    if not value:
        return set()
    return {format_node_id(part.strip()) for part in value.split(",") if part.strip()}


@dataclass(order=True, slots=True)
class _QueuedTransmission:
    priority: int
    sequence: int
    transmission: Transmission


class RNSMeshtasticInterface(Interface):
    """RNS interface using native Meshtastic PhoneAPI or binary MQTT."""

    # Reticulum stores this value in bytes. Sideband uses a fixed 16-byte
    # (128-bit) IFAC for its TCP interface whenever an IFAC name or passphrase
    # is configured. Matching that standard default is required because an
    # otherwise identical IFAC key with a different on-wire size is rejected.
    DEFAULT_IFAC_SIZE = 16
    HW_MTU = 564
    TELEMETRY_HEARTBEAT_SECONDS = 10.0

    def __init__(self, owner: Any, configuration: Any) -> None:
        super().__init__()
        config = Interface.get_config_obj(configuration)
        self.owner = owner
        self.name = str(config["name"])
        self.HW_MTU = 564
        self.bitrate = _int(config, "bitrate", 500)
        self.online = False
        self.transport_kind = str(config.get("transport", "native")).strip().lower()
        self.mesh_mode = str(config.get("mesh_mode", "broadcast")).strip().lower()
        self.gateway_role = str(config.get("gateway_role", "client")).strip().lower()
        self.gateway_node = _optional(config, "gateway_node")
        if self.gateway_node:
            self.gateway_node = format_node_id(self.gateway_node)
        self.allowed_nodes = _node_set(_optional(config, "allowed_nodes"))
        self.max_peers = _int(config, "max_peers", 32)
        self.peer_announce_idle_timeout = _float(config, "peer_announce_idle_timeout", 900.0)
        self.tx_interval = _float(config, "tx_interval", 1.0)
        self.max_queued_fragments = _int(config, "max_queued_fragments", 32)
        self.telemetry_file = _optional(config, "telemetry_file")
        self.accept_broadcast_on_hub = self.mesh_mode == "auto_multi_peer" or _bool(
            config, "accept_broadcast_on_hub", False
        )
        self._validate_mode()

        self.protocol = FragmentProtocol(
            fragment_body=_int(config, "fragment_body", 200),
            state_ttl=_float(config, "fragment_ttl", 180.0),
            request_cooldown=_float(config, "request_cooldown", 5.0),
            max_repair_requests_per_window=_int(config, "repair_request_budget", 12),
            repair_window=_float(config, "repair_budget_window", 60.0),
        )
        self.backend = self._make_backend(config)
        self.local_node_id: str | None = None
        self.spawned_interfaces: list[MeshtasticPeerInterface] = []
        self._peers: dict[str, MeshtasticPeerInterface] = {}
        self._peer_lock = threading.RLock()
        self._finalized = False
        self._pending_inbound: list[tuple[str, str, bytes]] = []
        self._tx_queue: queue.PriorityQueue[_QueuedTransmission] = queue.PriorityQueue(
            maxsize=self.max_queued_fragments
        )
        self._queue_admission_lock = threading.Lock()
        self._tx_sequence = itertools.count()
        self._telemetry_lock = threading.Lock()
        self._telemetry_last_write = 0.0
        self._last_radio_activity_at: str | None = None
        self._telemetry = {
            "tx_frames_accepted": 0,
            "tx_frames_rejected": 0,
            "tx_fragments": 0,
            "tx_fragment_bytes": 0,
            "rx_fragments": 0,
            "rx_fragment_bytes": 0,
            "rx_frames": 0,
            "rx_frame_bytes": 0,
            "repair_transmissions": 0,
            "retransmissions": 0,
            "backend_up": 0,
            "backend_down": 0,
            "send_failures": 0,
            "opaque_ifac_frames": 0,
            "idle_peer_announces_suppressed": 0,
        }
        self._observation_lock = threading.Lock()
        self._recent_announces: deque[dict[str, Any]] = deque(maxlen=64)
        self._stop = threading.Event()
        self._backend_online = threading.Event()
        self._worker = threading.Thread(target=self._tx_loop, name=f"{self.name}-tx", daemon=True)
        self._worker.start()

        try:
            self.backend.start(self._on_backend_packet, self._on_backend_state)
            self.local_node_id = self.backend.local_node_id
            if self.local_node_id:
                RNS.log(f"{self}: local Meshtastic node is {self.local_node_id}", RNS.LOG_NOTICE)
        except Exception:
            self._stop.set()
            raise

    def _validate_mode(self) -> None:
        if self.transport_kind not in {"native", "mqtt"}:
            raise ValueError("transport must be native or mqtt")
        if self.mesh_mode not in {"broadcast", "gateway_unicast", "auto_multi_peer"}:
            raise ValueError("mesh_mode must be broadcast, gateway_unicast or auto_multi_peer")
        if self.gateway_role not in {"client", "hub"}:
            raise ValueError("gateway_role must be client or hub")
        if self.mesh_mode == "gateway_unicast" and self.gateway_role == "client" and not self.gateway_node:
            raise ValueError("gateway_node is required for a gateway_unicast client")
        if self.mesh_mode == "broadcast" and self.gateway_role == "hub":
            raise ValueError("gateway_role=hub only applies to gateway_unicast")
        if self.mesh_mode == "auto_multi_peer" and self.gateway_role != "hub":
            raise ValueError("gateway_role=hub is required for auto_multi_peer")
        if not 1 <= self.max_peers <= 512:
            raise ValueError("max_peers must be between 1 and 512")
        if self.peer_announce_idle_timeout != 0 and not (300 <= self.peer_announce_idle_timeout <= 86_400):
            raise ValueError("peer_announce_idle_timeout must be 0 or between 300 and 86400 seconds")
        if not 4 <= self.max_queued_fragments <= 1024:
            raise ValueError("max_queued_fragments must be between 4 and 1024")
        if self.tx_interval < 0:
            raise ValueError("tx_interval cannot be negative")

    def _make_backend(self, config: Any) -> Any:
        if self.transport_kind == "native":
            native = NativeConfig(
                connection=str(config.get("connection", "tcp")).strip().lower(),
                tcp_host=_optional(config, "tcp_host"),
                tcp_port=_int(config, "tcp_port", 4403),
                serial_port=_optional(config, "serial_port"),
                ble_address=_optional(config, "ble_address"),
                channel_index=_int(config, "channel_index", 0),
                hop_limit=_int(config, "hop_limit", 3),
                want_ack=_bool(config, "want_ack", False),
                pki_required=_bool(config, "pki_required", False),
                mqtt_forwarding_policy=str(config.get("mqtt_forwarding_policy", "inherit")).strip().lower(),
            )
            return NativeBackend(native)

        mqtt_password = _optional(config, "mqtt_password")
        mqtt_password_env = _optional(config, "mqtt_password_env")
        if mqtt_password_env:
            mqtt_password = os.environ.get(mqtt_password_env)
            if mqtt_password is None:
                raise ValueError(f"MQTT password environment variable {mqtt_password_env!r} is not set")
        mqtt = MqttConfig(
            host=str(config.get("mqtt_host", "")).strip(),
            port=_int(config, "mqtt_port", 1883),
            username=_optional(config, "mqtt_username"),
            password=mqtt_password,
            tls=_bool(config, "mqtt_tls", False),
            tls_insecure=_bool(config, "mqtt_tls_insecure", False),
            root=str(config.get("mqtt_root", "msh/EU_868")).strip().rstrip("/"),
            virtual_node_id=str(config.get("virtual_node_id", "")).strip(),
            channel_name=str(config.get("mqtt_channel", "")).strip(),
            channel_index=_int(config, "channel_index", 0),
            downlink_hop_limit=_int(config, "mqtt_downlink_hops", 0),
            qos=_int(config, "mqtt_qos", 1),
            subscribe_pki=_bool(config, "mqtt_subscribe_pki", False),
            client_id=_optional(config, "mqtt_client_id"),
            dedup_ttl=_float(config, "mqtt_dedup_ttl", 300.0),
        )
        return MqttBackend(mqtt)

    def final_init(self) -> None:
        self._finalized = True
        if getattr(self, "ifac_key", None) is not None:
            network = getattr(self, "ifac_netname", None)
            RNS.log(
                f"{self}: Reticulum IFAC enabled ({self.ifac_size * 8}-bit, network {network!r})",
                RNS.LOG_NOTICE,
            )
        if self._is_multi_peer_hub():
            self.OUT = False
            self.IN = True
            if self.mesh_mode == "auto_multi_peer":
                scope = (
                    f"allowlist with {len(self.allowed_nodes)} node(s)"
                    if self.allowed_nodes
                    else "open radio ingress"
                )
                RNS.log(
                    f"{self}: auto multi-peer discovery enabled ({scope}, max {self.max_peers} peers)",
                    RNS.LOG_NOTICE,
                )
        pending, self._pending_inbound = self._pending_inbound, []
        for source, destination, payload in pending:
            self._process_backend_packet(source, destination, payload)

    def process_outgoing(self, data: bytes) -> None:
        if self.mesh_mode == "broadcast":
            destination = BROADCAST_ID
        elif self.gateway_role == "client":
            destination = self.gateway_node
        else:
            RNS.log(f"{self}: refusing parent-interface TX without a unicast peer", RNS.LOG_WARNING)
            return
        self._enqueue_frame(data, destination)

    def _enqueue_frame(self, data: bytes, destination: str | None) -> None:
        if destination is None:
            return
        try:
            transmissions = self.protocol.encode(bytes(data), destination)
            self._put_frame(transmissions, priority=10)
            self._telemetry["tx_frames_accepted"] += 1
            self.txb += len(data)
            self._observe_rns_frame("to_mesh", bytes(data), destination)
        except (FragmentError, queue.Full) as exc:
            self._telemetry["tx_frames_rejected"] += 1
            RNS.log(f"{self}: could not queue outbound frame: {exc}", RNS.LOG_ERROR)
        finally:
            self._write_telemetry()

    def _put_frame(self, transmissions: list[Transmission], *, priority: int) -> None:
        with self._queue_admission_lock:
            if self._tx_queue.qsize() + len(transmissions) > self.max_queued_fragments:
                raise queue.Full("complete Reticulum frame exceeds available LoRa queue capacity")
            for transmission in transmissions:
                item = _QueuedTransmission(priority, next(self._tx_sequence), transmission)
                self._tx_queue.put_nowait(item)

    def _put_tx(self, transmission: Transmission, *, priority: int) -> None:
        item = _QueuedTransmission(priority, next(self._tx_sequence), transmission)
        self._tx_queue.put_nowait(item)

    def _tx_loop(self) -> None:
        while not self._stop.is_set():
            try:
                item = self._tx_queue.get(timeout=0.5)
            except queue.Empty:
                self._queue_due_repairs()
                if time.monotonic() - self._telemetry_last_write >= self.TELEMETRY_HEARTBEAT_SECONDS:
                    # File-only liveness heartbeat. This does not enqueue or
                    # transmit anything over Meshtastic/LoRa.
                    self._write_telemetry(force=True)
                continue
            try:
                while not self._stop.is_set() and not self._backend_online.wait(timeout=1.0):
                    pass
                if self._stop.is_set():
                    return
                self.backend.send(item.transmission.payload, item.transmission.destination)
                self._last_radio_activity_at = datetime.now(UTC).isoformat()
                self._telemetry["tx_fragments"] += 1
                self._telemetry["tx_fragment_bytes"] += len(item.transmission.payload)
                if item.transmission.reason == "request":
                    self._telemetry["repair_transmissions"] += 1
                elif item.transmission.reason == "retransmit":
                    self._telemetry["retransmissions"] += 1
                self._write_telemetry()
                if self.tx_interval:
                    self._stop.wait(self.tx_interval)
                self._queue_due_repairs()
            except Exception as exc:
                self._telemetry["send_failures"] += 1
                RNS.log(f"{self}: TX failed, packet returned to queue: {exc}", RNS.LOG_WARNING)
                if not self._stop.wait(2.0):
                    try:
                        self._put_tx(item.transmission, priority=item.priority)
                    except queue.Full:
                        RNS.log(f"{self}: TX queue full after retry", RNS.LOG_ERROR)
            finally:
                self._tx_queue.task_done()

    def _queue_due_repairs(self) -> None:
        # One bounded repair per scheduler pass prevents a broken/asymmetric
        # return path from starving normal Reticulum traffic.
        for transmission in self.protocol.poll_repairs(
            max_requests=1, allow_control=not self._tx_queue.full()
        ).transmissions:
            try:
                self._put_tx(transmission, priority=0)
            except queue.Full:
                RNS.log(f"{self}: TX queue full while scheduling periodic repair", RNS.LOG_ERROR)

    def _on_backend_state(self, online: bool, detail: str | None) -> None:
        self.online = online
        with self._peer_lock:
            for peer in self.spawned_interfaces:
                peer.online = online
        if online:
            self._telemetry["backend_up"] += 1
            self._backend_online.set()
            self.local_node_id = self.backend.local_node_id
            RNS.log(f"{self}: transport connected", RNS.LOG_NOTICE)
        else:
            self._telemetry["backend_down"] += 1
            self._backend_online.clear()
            if detail:
                RNS.log(f"{self}: transport offline: {detail}", RNS.LOG_WARNING)
        self._write_telemetry(force=True)

    def _on_backend_packet(self, source: str, destination: str, payload: bytes) -> None:
        if not self._finalized:
            if len(self._pending_inbound) < 128:
                self._pending_inbound.append((source, destination, payload))
            return
        self._process_backend_packet(source, destination, payload)

    def _process_backend_packet(self, source: str, destination: str, payload: bytes) -> None:
        source = format_node_id(source)
        destination = format_node_id(destination)
        if self.local_node_id and source == self.local_node_id:
            return
        if self.allowed_nodes and source not in self.allowed_nodes:
            RNS.log(f"{self}: ignored non-allowlisted node {source}", RNS.LOG_DEBUG)
            return
        if self.mesh_mode in {"gateway_unicast", "auto_multi_peer"}:
            if self.gateway_role == "client" and source != self.gateway_node:
                return
            if self.gateway_role == "hub":
                valid_destinations = {self.local_node_id} if self.local_node_id else set()
                if self.accept_broadcast_on_hub:
                    valid_destinations.add(BROADCAST_ID)
                if destination not in valid_destinations:
                    return
        self._telemetry["rx_fragments"] += 1
        self._telemetry["rx_fragment_bytes"] += len(payload)
        self._last_radio_activity_at = datetime.now(UTC).isoformat()
        if self._is_multi_peer_hub():
            with self._peer_lock:
                known_peer = self._peers.get(source)
            if known_peer is not None:
                known_peer.note_inbound()
        try:
            result = self.protocol.receive(source, payload, allow_control=not self._tx_queue.full())
        except FragmentError as exc:
            RNS.log(f"{self}: ignored malformed fragment from {source}: {exc}", RNS.LOG_WARNING)
            return
        for transmission in result.transmissions:
            try:
                self._put_tx(transmission, priority=0)
            except queue.Full:
                RNS.log(f"{self}: TX queue full while requesting retransmission", RNS.LOG_ERROR)
        for frame in result.frames:
            if len(frame) > self.HW_MTU + 64:
                RNS.log(f"{self}: ignored oversized reassembled frame ({len(frame)} bytes)", RNS.LOG_WARNING)
                continue
            if self._is_multi_peer_hub():
                peer = self._get_or_create_peer(source)
                if peer is None:
                    continue
                peer.note_inbound()
                peer.rxb += len(frame)
                self.owner.inbound(frame, peer)
            else:
                self.rxb += len(frame)
                self.owner.inbound(frame, self)
            self._observe_rns_frame("from_mesh", frame, source)
            self._telemetry["rx_frames"] += 1
            self._telemetry["rx_frame_bytes"] += len(frame)
        self._write_telemetry()

    def _get_or_create_peer(self, source: str) -> MeshtasticPeerInterface | None:
        with self._peer_lock:
            if source in self._peers:
                return self._peers[source]
            if len(self._peers) >= self.max_peers:
                RNS.log(f"{self}: peer limit reached; ignored {source}", RNS.LOG_WARNING)
                return None
            peer = MeshtasticPeerInterface(self.owner, self, source)
            self._copy_interface_policy(peer)
            self._peers[source] = peer
            self.spawned_interfaces.append(peer)
            RNS.Transport.add_interface(peer)
            RNS.log(f"{self}: created Reticulum peer interface for {source}", RNS.LOG_NOTICE)
            return peer

    def _is_multi_peer_hub(self) -> bool:
        return self.gateway_role == "hub" and self.mesh_mode in {
            "gateway_unicast",
            "auto_multi_peer",
        }

    def _copy_interface_policy(self, peer: MeshtasticPeerInterface) -> None:
        attributes = (
            "mode",
            "gravity",
            "bitrate",
            "HW_MTU",
            "ifac_size",
            "ifac_netname",
            "ifac_netkey",
            "ifac_key",
            "ifac_identity",
            "ifac_signature",
            "ingress_control",
            "ic_max_held_announces",
            "ic_burst_hold",
            "ic_burst_freq",
            "ic_burst_freq_new",
            "ic_new_time",
            "ic_burst_penalty",
            "ic_held_release_interval",
            "egress_control",
            "ec_pr_freq",
            "ic_pr_burst_freq_new",
            "ic_pr_burst_freq",
            "announce_rate_target",
            "announce_rate_grace",
            "announce_rate_penalty",
            "announces_from_internal",
            "announces_to_internal",
        )
        for name in attributes:
            if hasattr(self, name):
                setattr(peer, name, getattr(self, name))

    def _remove_peer(self, peer: MeshtasticPeerInterface) -> None:
        with self._peer_lock:
            self._peers.pop(peer.peer_node, None)
            if peer in self.spawned_interfaces:
                self.spawned_interfaces.remove(peer)

    def detach(self) -> None:
        self.detached = True
        self.online = False
        self._stop.set()
        self._backend_online.set()
        self.backend.close()
        self._write_telemetry(force=True)
        with self._peer_lock:
            peers = list(self.spawned_interfaces)
        for peer in peers:
            peer.detach()

    def _write_telemetry(self, *, force: bool = False) -> None:
        if not self.telemetry_file:
            return
        now = time.monotonic()
        if not force and now - self._telemetry_last_write < 1.0:
            return
        if not self._telemetry_lock.acquire(blocking=False):
            return
        try:
            self._telemetry_last_write = now
            target = Path(self.telemetry_file)
            target.parent.mkdir(parents=True, exist_ok=True)
            peers = []
            with self._peer_lock:
                for peer in self.spawned_interfaces:
                    peers.append(
                        {
                            "node_id": peer.peer_node,
                            "rx_bytes": int(peer.rxb),
                            "tx_bytes": int(peer.txb),
                            "online": bool(peer.online),
                            "last_inbound_at": peer.last_inbound_at,
                            "idle_seconds": round(peer.inbound_idle_seconds(), 1),
                            "announce_delivery_state": peer.announce_delivery_state(),
                            "ordinary_announces_suppressed": peer.ordinary_announces_suppressed,
                        }
                    )
            with self._observation_lock:
                recent_announces = list(self._recent_announces)
            value = {
                "schema": 1,
                "captured_at": datetime.now(UTC).isoformat(),
                "heartbeat_interval_seconds": self.TELEMETRY_HEARTBEAT_SECONDS,
                "last_radio_activity_at": self._last_radio_activity_at,
                "interface": self.name,
                "online": bool(self.online),
                "local_node_id": self.local_node_id,
                "transport": self.transport_kind,
                "mesh_mode": self.mesh_mode,
                "gateway_role": self.gateway_role,
                "tx_interval": self.tx_interval,
                "queue": {
                    "fragments": self._tx_queue.qsize(),
                    "limit": self.max_queued_fragments,
                },
                "counters": dict(self._telemetry),
                "reassembly": self.protocol.telemetry(),
                "peers": peers,
                "announce_observability": {
                    "header_visibility": (
                        "opaque_ifac" if getattr(self, "ifac_key", None) is not None else "plain"
                    ),
                    "payloads_recorded": False,
                },
                "recent_announces": recent_announces,
            }
            with tempfile.NamedTemporaryFile(
                "w", dir=target.parent, delete=False, encoding="utf-8"
            ) as handle:
                json.dump(value, handle, sort_keys=True)
                handle.write("\n")
                temporary = Path(handle.name)
            temporary.chmod(0o600)
            temporary.replace(target)
        except OSError as error:
            RNS.log(f"{self}: could not write telemetry: {error}", RNS.LOG_WARNING)
        finally:
            self._telemetry_lock.release()

    def _observe_rns_frame(self, direction: str, frame: bytes, meshtastic_peer: str) -> None:
        metadata = parse_rns_frame(frame)
        if metadata is None:
            if is_opaque_ifac(frame):
                self._telemetry["opaque_ifac_frames"] += 1
            return
        if not metadata.is_announce:
            return
        observation = {
            "captured_at": datetime.now(UTC).isoformat(),
            "direction": direction,
            "meshtastic_peer": meshtastic_peer,
            "destination_hash": metadata.destination_hash,
            "destination_type": metadata.destination_type,
            "context": metadata.context,
            "frame_bytes": len(frame),
        }
        with self._observation_lock:
            self._recent_announces.append(observation)

    @staticmethod
    def should_ingress_limit() -> bool:
        return False

    def __str__(self) -> str:
        return f"RNSMeshtasticInterface[{self.name}/{self.transport_kind}/{self.mesh_mode}]"


class MeshtasticPeerInterface(Interface):
    """Point-to-point Reticulum child tied to one Meshtastic NodeNum."""

    DEFAULT_IFAC_SIZE = RNSMeshtasticInterface.DEFAULT_IFAC_SIZE

    def __init__(self, owner: Any, parent: RNSMeshtasticInterface, peer_node: str) -> None:
        super().__init__()
        self.owner = owner
        self.parent_interface = parent
        self.peer_node = format_node_id(peer_node)
        self.name = f"{parent.name} peer {self.peer_node}"
        self.HW_MTU = parent.HW_MTU
        self.bitrate = parent.bitrate
        self.IN = True
        self.OUT = True
        self.online = parent.online
        # A dynamically learned Meshtastic radio peer is a normal physical
        # interface, not an RNS tunnel endpoint. Some Reticulum releases
        # leave a non-null inherited ``tunnel_id`` here, which makes announce
        # processing try to update a tunnel table entry that does not exist.
        # Besides noisy warnings, that can degrade path bookkeeping during
        # multi-peer discovery.
        self.tunnel_id = None
        self.last_inbound_monotonic = time.monotonic()
        self.last_inbound_at = datetime.now(UTC).isoformat()
        self.ordinary_announces_suppressed = 0
        self._announce_suppression_active = False

    def note_inbound(self) -> None:
        self.last_inbound_monotonic = time.monotonic()
        self.last_inbound_at = datetime.now(UTC).isoformat()
        if self._announce_suppression_active:
            self._announce_suppression_active = False
            RNS.log(
                f"{self}: inbound activity restored ordinary announce delivery",
                RNS.LOG_NOTICE,
            )

    def inbound_idle_seconds(self) -> float:
        return max(0.0, time.monotonic() - self.last_inbound_monotonic)

    def announce_delivery_state(self) -> str:
        timeout = self.parent_interface.peer_announce_idle_timeout
        if timeout == 0 or self.inbound_idle_seconds() < timeout:
            return "active"
        if getattr(self.parent_interface, "ifac_key", None) is not None:
            return "opaque_ifac"
        return "ordinary_announces_suppressed"

    def _should_suppress_ordinary_announce(self, data: bytes) -> bool:
        timeout = self.parent_interface.peer_announce_idle_timeout
        if timeout == 0 or self.inbound_idle_seconds() < timeout:
            return False
        metadata = parse_rns_frame(data)
        # Only context-0 periodic announces are safe to suppress. Explicit
        # PATH_RESPONSE announces and opaque IFAC frames continue unchanged.
        if metadata is None or not metadata.is_announce or metadata.context != 0:
            return False
        self.ordinary_announces_suppressed += 1
        self.parent_interface._telemetry["idle_peer_announces_suppressed"] += 1
        if not self._announce_suppression_active:
            self._announce_suppression_active = True
            RNS.log(
                f"{self}: suppressing ordinary announces after "
                f"{int(self.inbound_idle_seconds())} seconds without inbound bridge traffic; "
                "data, proofs and path responses remain enabled",
                RNS.LOG_NOTICE,
            )
        self.parent_interface._write_telemetry()
        return True

    def process_outgoing(self, data: bytes) -> None:
        if self.online and not self._should_suppress_ordinary_announce(data):
            self.parent_interface._enqueue_frame(data, self.peer_node)
            self.txb += len(data)

    def detach(self) -> None:
        if self.detached:
            return
        self.detached = True
        self.online = False
        self.parent_interface._remove_peer(self)
        RNS.Transport.remove_interface(self)

    @staticmethod
    def should_ingress_limit() -> bool:
        return False

    def __str__(self) -> str:
        return f"MeshtasticPeerInterface[{self.peer_node} via {self.parent_interface.name}]"
