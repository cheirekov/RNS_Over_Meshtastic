import threading
from types import SimpleNamespace

import pytest

from rns_meshtastic.transports.native import NativeBackend, NativeConfig


class FakeInterface:
    def __init__(self, mqtt_permitted):
        self.sent = []
        self.localNode = SimpleNamespace(
            localConfig=SimpleNamespace(
                lora=SimpleNamespace(config_ok_to_mqtt=mqtt_permitted)
            )
        )

    def _sendPacket(self, packet, **kwargs):
        self.sent.append((packet, kwargs))


def test_connected_notification_is_emitted_once():
    backend = NativeBackend(NativeConfig(connection="tcp", tcp_host="127.0.0.1"))
    states = []
    backend._state_callback = lambda online, detail: states.append((online, detail))

    backend._notify_connected()
    backend._notify_connected()

    assert states == [(True, None)]


def test_disconnect_notification_requires_online_state():
    backend = NativeBackend(NativeConfig(connection="tcp", tcp_host="127.0.0.1"))
    first = object()
    backend._interface = first
    states = []
    backend._state_callback = lambda online, detail: states.append((online, detail))

    backend._on_disconnected(first)
    second = object()
    backend._interface = second
    backend._notify_connected()
    backend._on_disconnected(second)
    backend._on_disconnected(second)

    assert states == [(True, None), (False, "PhoneAPI connection lost")]


def test_disconnect_replaces_entire_native_session(monkeypatch):
    class ReconnectInterface:
        def __init__(self, node_num):
            self.closed = False
            self.myInfo = SimpleNamespace(my_node_num=node_num)

        def close(self):
            self.closed = True

    backend = NativeBackend(NativeConfig(connection="tcp", tcp_host="127.0.0.1"))
    first = ReconnectInterface(0x11223344)
    replacement = ReconnectInterface(0xAABBCC11)
    backend._interface = first
    backend._online = True
    states = []
    connected = threading.Event()

    def state_callback(online, detail):
        states.append((online, detail))
        if online:
            connected.set()

    backend._state_callback = state_callback
    monkeypatch.setattr(backend, "_open_interface", lambda: replacement)
    backend._reconnect_thread = threading.Thread(target=backend._reconnect_loop, daemon=True)
    backend._reconnect_thread.start()

    backend._on_disconnected(first)

    assert connected.wait(1.0)
    assert backend._interface is replacement
    assert backend.local_node_id == "!aabbcc11"
    assert first.closed
    assert states == [
        (False, "PhoneAPI connection lost"),
        (True, None),
    ]
    backend.close()


def test_stale_disconnect_cannot_take_replacement_offline():
    backend = NativeBackend(NativeConfig(connection="tcp", tcp_host="127.0.0.1"))
    stale = object()
    replacement = object()
    backend._interface = replacement
    backend._online = True
    states = []
    backend._state_callback = lambda online, detail: states.append((online, detail))

    backend._on_disconnected(stale)

    assert backend._interface is replacement
    assert backend._online
    assert states == []


@pytest.mark.parametrize("mqtt_permitted", [False, True])
def test_send_mirrors_radio_mqtt_policy(mqtt_permitted):
    backend = NativeBackend(
        NativeConfig(
            connection="tcp",
            tcp_host="127.0.0.1",
            channel_index=1,
            hop_limit=3,
            want_ack=True,
        )
    )
    interface = FakeInterface(mqtt_permitted)
    backend._interface = interface
    backend._portnum = 76

    backend.send(b"reticulum", "!8fd1336c")

    packet, kwargs = interface.sent[0]
    assert packet.channel == 1
    assert packet.decoded.portnum == 76
    assert packet.decoded.payload == b"reticulum"
    assert packet.decoded.HasField("bitfield") is mqtt_permitted
    assert packet.decoded.bitfield == int(mqtt_permitted)
    assert kwargs == {
        "destinationId": "!8fd1336c",
        "wantAck": True,
        "hopLimit": 3,
        "pkiEncrypted": False,
    }


def test_send_can_force_mqtt_forwarding_off_without_changing_radio_config():
    backend = NativeBackend(
        NativeConfig(
            connection="tcp",
            tcp_host="127.0.0.1",
            mqtt_forwarding_policy="force_off",
        )
    )
    interface = FakeInterface(True)
    backend._interface = interface
    backend._portnum = 76

    backend.send(b"reticulum", "!8fd1336c")

    packet, _ = interface.sent[0]
    assert not packet.decoded.HasField("bitfield")
    assert packet.decoded.bitfield == 0
