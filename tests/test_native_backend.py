from rns_meshtastic.transports.native import NativeBackend, NativeConfig


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
