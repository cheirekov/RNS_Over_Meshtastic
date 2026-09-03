import threading

from rns_meshtastic.lan_policy import PolicyTCPServerInterface, normalise_client_address, parse_networks


def policy(*, allowed: str = "", denied: str = "") -> PolicyTCPServerInterface:
    interface = PolicyTCPServerInterface.__new__(PolicyTCPServerInterface)
    interface.allowed_networks = parse_networks(allowed)
    interface.denied_networks = parse_networks(denied)
    return interface


def test_lan_policy_empty_allowlist_and_deny_wins() -> None:
    interface = policy(denied="192.0.2.10/32, 2001:db8::/32")
    assert interface.client_allowed("192.0.2.11") is True
    assert interface.client_allowed("192.0.2.10") is False
    assert interface.client_allowed("2001:db8::1") is False


def test_lan_policy_allowlist_supports_ipv4_mapped_clients() -> None:
    interface = policy(allowed="10.8.0.0/24", denied="10.8.0.9")
    assert interface.client_allowed("::ffff:10.8.0.8") is True
    assert interface.client_allowed("::ffff:10.8.0.9") is False
    assert interface.client_allowed("::ffff:10.9.0.1") is False
    assert str(normalise_client_address("fe80::1%eth0")) == "fe80::1"


def test_lan_policy_rejects_socket_before_creating_rns_child() -> None:
    class FakeSocket:
        def __init__(self) -> None:
            self.shutdown_called = False
            self.closed = False

        def shutdown(self, _how: int) -> None:
            self.shutdown_called = True

        def close(self) -> None:
            self.closed = True

    class Handler:
        client_address = ("192.0.2.10", 54321)
        request = FakeSocket()

    interface = policy(denied="192.0.2.10/32")
    interface.blocked_ip_count = 0
    interface._policy_lock = threading.Lock()
    interface.incoming_connection(Handler())
    assert interface.blocked_ip_count == 1
    assert Handler.request.shutdown_called is True
    assert Handler.request.closed is True
