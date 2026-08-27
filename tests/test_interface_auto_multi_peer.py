import threading

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
        self.sent: list[tuple[bytes, str]] = []
        self.sent_event = threading.Event()

    def start(self, packet_callback, state_callback) -> None:
        self.packet_callback = packet_callback
        state_callback(True, None)

    def send(self, payload: bytes, destination: str) -> None:
        self.sent.append((payload, destination))
        self.sent_event.set()

    def close(self) -> None:
        pass


class FakeReticulumDefaults:
    def __getattr__(self, _name):
        return lambda: 0


def make_interface(monkeypatch, *, allowed_nodes: str = "", max_peers: int = 4):
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
        "tx_interval": "0",
    }
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
