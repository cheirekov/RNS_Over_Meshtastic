import json
import threading
import time
from pathlib import Path

import RNS

from rns_meshtastic.addresses import BROADCAST_ID
from rns_meshtastic.framing import FragmentProtocol
from rns_meshtastic.interface import MeshtasticPeerInterface, RNSMeshtasticInterface


class FakeOwner:
    def __init__(self) -> None:
        self.received: list[tuple[bytes, object]] = []

    def inbound(self, frame: bytes, interface: object) -> None:
        self.received.append((frame, interface))


class FakeBackend:
    local_node_id = "!8fd13c64"

    def __init__(self) -> None:
        self.packet_callback = None
        self.state_callback = None
        self.sent: list[tuple[bytes, str]] = []
        self.sent_event = threading.Event()

    def start(self, packet_callback, state_callback) -> None:
        self.packet_callback = packet_callback
        self.state_callback = state_callback
        state_callback(True, None)

    def send(self, payload: bytes, destination: str) -> None:
        self.sent.append((payload, destination))
        self.sent_event.set()

    def close(self) -> None:
        pass


class FakeReticulumDefaults:
    def __getattr__(self, _name):
        return lambda: 0


def make_interface(
    monkeypatch,
    *,
    allowed_nodes: str = "",
    max_peers: int = 4,
    peer_announce_idle_timeout: int = 900,
    telemetry_file: Path | None = None,
):
    backend = FakeBackend()
    owner = FakeOwner()
    monkeypatch.setattr(
        RNSMeshtasticInterface,
        "_make_backend",
        lambda _interface, _config: backend,
    )
    monkeypatch.setattr(RNS.Transport, "add_interface", lambda _interface: None)
    monkeypatch.setattr(RNS.Transport, "remove_interface", lambda _interface: None)
    monkeypatch.setattr(RNS.Reticulum, "get_instance", lambda: FakeReticulumDefaults())
    config = {
        "name": "auto multi-peer test",
        "transport": "native",
        "connection": "tcp",
        "tcp_host": "192.0.2.10",
        "mesh_mode": "auto_multi_peer",
        "gateway_role": "hub",
        "allowed_nodes": allowed_nodes,
        "max_peers": str(max_peers),
        "peer_announce_idle_timeout": str(peer_announce_idle_timeout),
        "tx_interval": "0",
    }
    if telemetry_file is not None:
        config["telemetry_file"] = str(telemetry_file)
    interface = RNSMeshtasticInterface(owner, config)
    interface.final_init()
    return interface, backend, owner


def broadcast_frame(frame: bytes) -> bytes:
    return FragmentProtocol().encode(frame, BROADCAST_ID)[0].payload


def test_auto_multi_peer_discovers_broadcast_source_and_replies_by_unicast(monkeypatch) -> None:
    interface, backend, owner = make_interface(monkeypatch)
    try:
        assert backend.packet_callback is not None
        backend.packet_callback(
            "!a1b3b3b8",
            BROADCAST_ID,
            broadcast_frame(b"android announce"),
        )

        assert len(owner.received) == 1
        frame, peer = owner.received[0]
        assert frame == b"android announce"
        assert isinstance(peer, MeshtasticPeerInterface)
        assert peer.peer_node == "!a1b3b3b8"

        peer.process_outgoing(b"tcp client reply")
        assert backend.sent_event.wait(timeout=1.0)
        assert backend.sent[-1][1] == "!a1b3b3b8"
    finally:
        interface.detach()


def test_auto_multi_peer_applies_optional_radio_allowlist(monkeypatch) -> None:
    interface, backend, owner = make_interface(monkeypatch, allowed_nodes="!11223344")
    try:
        assert backend.packet_callback is not None
        backend.packet_callback(
            "!a1b3b3b8",
            BROADCAST_ID,
            broadcast_frame(b"not allowed"),
        )

        assert owner.received == []
        assert interface.spawned_interfaces == []
    finally:
        interface.detach()


def test_auto_multi_peer_open_discovery_is_bounded_by_peer_limit(monkeypatch) -> None:
    interface, backend, owner = make_interface(monkeypatch, max_peers=1)
    try:
        assert backend.packet_callback is not None
        backend.packet_callback(
            "!a1b3b3b8",
            BROADCAST_ID,
            broadcast_frame(b"first peer"),
        )
        backend.packet_callback(
            "!11223344",
            BROADCAST_ID,
            broadcast_frame(b"second peer"),
        )

        assert [peer.peer_node for peer in interface.spawned_interfaces] == ["!a1b3b3b8"]
        assert [frame for frame, _peer in owner.received] == [b"first peer"]
    finally:
        interface.detach()


def test_auto_multi_peer_child_tracks_physical_transport_state(monkeypatch) -> None:
    interface, backend, owner = make_interface(monkeypatch)
    try:
        assert backend.packet_callback is not None
        assert backend.state_callback is not None
        backend.packet_callback(
            "!a1b3b3b8",
            BROADCAST_ID,
            broadcast_frame(b"discovery"),
        )
        peer = owner.received[0][1]
        assert peer.online

        backend.state_callback(False, "test disconnect")
        assert not interface.online
        assert not peer.online

        backend.state_callback(True, None)
        assert interface.online
        assert peer.online
    finally:
        interface.detach()


def rns_frame(*, packet_type: int, context: int) -> bytes:
    return bytes([packet_type, 0]) + (b"\x11" * 16) + bytes([context]) + b"payload"


def test_idle_peer_suppresses_only_ordinary_announces_and_recovers(monkeypatch) -> None:
    interface, backend, owner = make_interface(monkeypatch, peer_announce_idle_timeout=300)
    try:
        assert backend.packet_callback is not None
        backend.packet_callback(
            "!a1b3b3b8",
            BROADCAST_ID,
            broadcast_frame(b"discovery"),
        )
        peer = owner.received[0][1]
        peer.last_inbound_monotonic = time.monotonic() - 301
        backend.sent.clear()
        backend.sent_event.clear()

        peer.process_outgoing(rns_frame(packet_type=1, context=0))
        assert not backend.sent_event.wait(timeout=0.1)
        assert interface._telemetry["idle_peer_announces_suppressed"] == 1

        # PATH_RESPONSE announce remains deliverable while the peer is idle.
        peer.process_outgoing(rns_frame(packet_type=1, context=0x0B))
        assert backend.sent_event.wait(timeout=1.0)
        assert backend.sent[-1][1] == "!a1b3b3b8"

        # Any inbound bridge traffic refreshes the lease.
        backend.sent_event.clear()
        backend.packet_callback(
            "!a1b3b3b8",
            BROADCAST_ID,
            broadcast_frame(b"bridge returned"),
        )
        peer.process_outgoing(rns_frame(packet_type=1, context=0))
        assert backend.sent_event.wait(timeout=1.0)
    finally:
        interface.detach()


def test_idle_telemetry_heartbeat_does_not_transmit(monkeypatch, tmp_path: Path) -> None:
    telemetry_path = tmp_path / "telemetry.json"
    monkeypatch.setattr(RNSMeshtasticInterface, "TELEMETRY_HEARTBEAT_SECONDS", 0.05)
    interface, backend, _owner = make_interface(monkeypatch, telemetry_file=telemetry_path)
    try:
        initial = json.loads(telemetry_path.read_text(encoding="utf-8"))
        time.sleep(0.65)
        refreshed = json.loads(telemetry_path.read_text(encoding="utf-8"))
        assert refreshed["captured_at"] != initial["captured_at"]
        assert refreshed["heartbeat_interval_seconds"] == 0.05
        assert refreshed["last_radio_activity_at"] is None
        assert backend.sent == []
    finally:
        interface.detach()
