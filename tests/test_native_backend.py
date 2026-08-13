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
    interface = object()
    backend._interface = interface
    states = []
    backend._state_callback = lambda online, detail: states.append((online, detail))

    backend._on_disconnected(interface)
    backend._notify_connected()
    backend._on_disconnected(interface)
    backend._on_disconnected(interface)

    assert states == [(True, None), (False, "PhoneAPI connection lost")]


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
