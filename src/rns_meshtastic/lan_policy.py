"""Small access-control wrapper around Reticulum's TCP server interface."""

from __future__ import annotations

import ipaddress
import socket
import threading
from collections.abc import Iterable
from typing import Any

import RNS
from RNS.Interfaces.Interface import Interface
from RNS.Interfaces.TCPInterface import TCPServerInterface


def parse_networks(value: Any) -> tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...]:
    """Parse ConfigObj strings/lists as canonical IPv4/IPv6 networks."""

    if value is None:
        return ()
    entries: Iterable[Any] = value if isinstance(value, (list, tuple)) else str(value).split(",")
    networks = []
    for entry in entries:
        text = str(entry).strip()
        if text:
            networks.append(ipaddress.ip_network(text, strict=False))
    return tuple(networks)


def normalise_client_address(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    address = ipaddress.ip_address(value.split("%", 1)[0])
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        return address.ipv4_mapped
    return address


class PolicyTCPServerInterface(TCPServerInterface):
    """Reticulum TCP listener with a deny-wins CIDR admission policy.

    An empty allowlist means "all source addresses". The denylist is then
    applied as an exception. Policy is evaluated before a Reticulum child
    interface is created, so rejected sockets cannot inject RNS frames.
    """

    def __init__(self, owner: Any, configuration: Any) -> None:
        config = Interface.get_config_obj(configuration)
        self.allowed_networks = parse_networks(config.get("allow_networks"))
        self.denied_networks = parse_networks(config.get("deny_networks"))
        self.blocked_ip_count = 0
        self.blocked_ip_list = [str(network) for network in self.denied_networks]
        self._policy_lock = threading.Lock()
        super().__init__(owner, configuration)

    def client_allowed(self, source: str) -> bool:
        address = normalise_client_address(source)
        if any(address in network for network in self.denied_networks):
            return False
        return not self.allowed_networks or any(address in network for network in self.allowed_networks)

    def incoming_connection(self, handler: Any) -> None:
        source = str(handler.client_address[0])
        try:
            allowed = self.client_allowed(source)
        except ValueError:
            allowed = False
        if allowed:
            super().incoming_connection(handler)
            return

        with self._policy_lock:
            self.blocked_ip_count += 1
        RNS.log(f"Rejected LAN Reticulum client from {source} by address policy", RNS.LOG_NOTICE)
        try:
            handler.request.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        finally:
            handler.request.close()
