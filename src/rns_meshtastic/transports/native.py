"""Meshtastic PhoneAPI backend for TCP, serial and BLE radios."""

from __future__ import annotations

import contextlib
import threading
from dataclasses import dataclass
from typing import Any

from rns_meshtastic.addresses import BROADCAST_ID, format_node_id
from rns_meshtastic.transports.base import PacketCallback, StateCallback, TransportBackend


@dataclass(frozen=True, slots=True)
class NativeConfig:
    connection: str
    tcp_host: str | None = None
    tcp_port: int = 4403
    serial_port: str | None = None
    ble_address: str | None = None
    channel_index: int = 0
    hop_limit: int = 3
    want_ack: bool = False
    pki_required: bool = False

    def __post_init__(self) -> None:
        if self.connection not in {"tcp", "serial", "ble"}:
            raise ValueError("connection must be tcp, serial or ble")
        if self.connection == "tcp" and not self.tcp_host:
            raise ValueError("tcp_host is required for a TCP Meshtastic device")
        if self.connection == "serial" and not self.serial_port:
            raise ValueError("serial_port is required for a serial Meshtastic device")
        if self.connection == "ble" and not self.ble_address:
            raise ValueError("ble_address is required for a BLE Meshtastic device")
        if not 0 <= self.channel_index <= 7:
            raise ValueError("channel_index must be between 0 and 7")
        if not 0 <= self.hop_limit <= 7:
            raise ValueError("hop_limit must be between 0 and 7")


class NativeBackend(TransportBackend):
    def __init__(self, config: NativeConfig) -> None:
        self.config = config
        self._interface: Any | None = None
        self._packet_callback: PacketCallback | None = None
        self._state_callback: StateCallback | None = None
        self._pub: Any | None = None
        self._lock = threading.RLock()
        self._closed = False
        self._online = False

    def start(self, packet_callback: PacketCallback, state_callback: StateCallback) -> None:
        from meshtastic.protobuf import portnums_pb2
        from pubsub import pub

        self._portnum = portnums_pb2.PortNum.RETICULUM_TUNNEL_APP
        self._packet_callback = packet_callback
        self._state_callback = state_callback
        self._pub = pub
        pub.subscribe(self._on_receive, "meshtastic.receive")
        pub.subscribe(self._on_connected, "meshtastic.connection.established")
        pub.subscribe(self._on_disconnected, "meshtastic.connection.lost")

        try:
            if self.config.connection == "tcp":
                from meshtastic.tcp_interface import TCPInterface

                interface = TCPInterface(
                    hostname=self.config.tcp_host,
                    portNumber=self.config.tcp_port,
                )
            elif self.config.connection == "serial":
                from meshtastic.serial_interface import SerialInterface

                interface = SerialInterface(devPath=self.config.serial_port)
            else:
                from meshtastic.ble_interface import BLEInterface

                interface = BLEInterface(address=self.config.ble_address)
            with self._lock:
                self._interface = interface
            self._set_local_node(interface)
            self._notify_connected()
        except Exception as exc:
            self.close()
            state_callback(False, str(exc))
            raise

    def send(self, payload: bytes, destination: str) -> None:
        with self._lock:
            if self._closed or self._interface is None:
                raise ConnectionError("Meshtastic PhoneAPI is not connected")
            self._interface.sendData(
                payload,
                destinationId=destination,
                portNum=self._portnum,
                wantAck=self.config.want_ack and destination != BROADCAST_ID,
                wantResponse=False,
                channelIndex=self.config.channel_index,
                hopLimit=self.config.hop_limit,
                pkiEncrypted=self.config.pki_required and destination != BROADCAST_ID,
            )

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._online = False
            interface, self._interface = self._interface, None
        if self._pub is not None:
            for callback, topic in (
                (self._on_receive, "meshtastic.receive"),
                (self._on_connected, "meshtastic.connection.established"),
                (self._on_disconnected, "meshtastic.connection.lost"),
            ):
                with contextlib.suppress(Exception):
                    self._pub.unsubscribe(callback, topic)
        if interface is not None:
            with contextlib.suppress(Exception):
                interface.close()

    def _belongs_to_us(self, interface: Any) -> bool:
        with self._lock:
            return self._interface is None or interface is self._interface

    def _on_connected(self, interface: Any, topic: Any = None) -> None:
        del topic
        if not self._belongs_to_us(interface):
            return
        with self._lock:
            if self._interface is None:
                self._interface = interface
        self._set_local_node(interface)
        self._notify_connected()

    def _on_disconnected(self, interface: Any, topic: Any = None) -> None:
        del topic
        if not self._belongs_to_us(interface):
            return
        with self._lock:
            was_online = self._online
            self._online = False
        if was_online and self._state_callback:
            self._state_callback(False, "PhoneAPI connection lost")

    def _on_receive(self, packet: dict[str, Any], interface: Any) -> None:
        if not self._belongs_to_us(interface) or self._packet_callback is None:
            return
        decoded = packet.get("decoded")
        if not decoded:
            return
        portnum = decoded.get("portnum")
        if portnum not in {"RETICULUM_TUNNEL_APP", 76, self._portnum}:
            return
        payload = decoded.get("payload")
        source_num = packet.get("from")
        if payload is None or source_num is None:
            return
        destination_num = packet.get("to", 0xFFFFFFFF)
        self._packet_callback(
            format_node_id(int(source_num) & 0xFFFFFFFF),
            format_node_id(int(destination_num) & 0xFFFFFFFF),
            bytes(payload),
        )

    def _set_local_node(self, interface: Any) -> None:
        try:
            node_num = int(interface.myInfo.my_node_num) & 0xFFFFFFFF
            self.local_node_id = format_node_id(node_num)
        except Exception:
            self.local_node_id = None

    def _notify_connected(self) -> None:
        with self._lock:
            if self._online:
                return
            self._online = True
        if self._state_callback:
            self._state_callback(True, None)
