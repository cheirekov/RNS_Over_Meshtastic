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
    mqtt_forwarding_policy: str = "inherit"

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
        if self.mqtt_forwarding_policy not in {"inherit", "force_off"}:
            raise ValueError("mqtt_forwarding_policy must be inherit or force_off")


class NativeBackend(TransportBackend):
    def __init__(self, config: NativeConfig) -> None:
        self.config = config
        self._interface: Any | None = None
        self._packet_callback: PacketCallback | None = None
        self._state_callback: StateCallback | None = None
        self._pub: Any | None = None
        self._lock = threading.RLock()
        self._connect_lock = threading.Lock()
        self._closed = False
        self._online = False
        self._retired_interfaces: list[Any] = []
        self._reconnect_wakeup = threading.Event()
        self._reconnect_stop = threading.Event()
        self._reconnect_thread: threading.Thread | None = None

    def start(self, packet_callback: PacketCallback, state_callback: StateCallback) -> None:
        from meshtastic.protobuf import portnums_pb2
        from pubsub import pub

        self._portnum = portnums_pb2.PortNum.RETICULUM_TUNNEL_APP
        self._packet_callback = packet_callback
        self._state_callback = state_callback
        self._pub = pub
        pub.subscribe(self._on_receive, "meshtastic.receive")
        pub.subscribe(self._on_disconnected, "meshtastic.connection.lost")

        try:
            interface = self._open_interface()
            with self._lock:
                self._interface = interface
            self._set_local_node(interface)
            self._notify_connected()
            self._reconnect_thread = threading.Thread(
                target=self._reconnect_loop,
                name=f"meshtastic-{self.config.connection}-reconnect",
                daemon=True,
            )
            self._reconnect_thread.start()
        except Exception as exc:
            self.close()
            state_callback(False, str(exc))
            raise

    def _open_interface(self) -> Any:
        if self.config.connection == "tcp":
            from meshtastic.tcp_interface import TCPInterface

            return TCPInterface(
                hostname=self.config.tcp_host,
                portNumber=self.config.tcp_port,
            )
        if self.config.connection == "serial":
            from meshtastic.serial_interface import SerialInterface

            return SerialInterface(devPath=self.config.serial_port)

        from meshtastic.ble_interface import BLEInterface

        return BLEInterface(address=self.config.ble_address)

    def send(self, payload: bytes, destination: str) -> None:
        from meshtastic.protobuf import mesh_pb2

        with self._lock:
            if self._closed or self._interface is None:
                raise ConnectionError("Meshtastic PhoneAPI is not connected")
            # MeshInterface.sendData() does not currently expose Data.bitfield.
            # Build explicitly so the packet carries the radio owner's current
            # config_ok_to_mqtt policy, including across auto-PKI handling.
            packet = mesh_pb2.MeshPacket()
            packet.channel = self.config.channel_index
            packet.decoded.payload = payload
            packet.decoded.portnum = self._portnum
            if self._mqtt_forwarding_allowed(self._interface):
                packet.decoded.bitfield = 1  # BITFIELD_OK_TO_MQTT_MASK
            packet.priority = mesh_pb2.MeshPacket.Priority.RELIABLE
            interface = self._interface
            try:
                interface._sendPacket(
                    packet,
                    destinationId=destination,
                    wantAck=self.config.want_ack and destination != BROADCAST_ID,
                    hopLimit=self.config.hop_limit,
                    pkiEncrypted=self.config.pki_required and destination != BROADCAST_ID,
                )
            except Exception as exc:
                self._mark_offline(interface, f"PhoneAPI send failed: {exc}")
                raise

    @staticmethod
    def _mqtt_uplink_permitted(interface: Any) -> bool:
        """Mirror the radio's privacy policy for auto-PKI PhoneAPI packets."""
        try:
            return bool(interface.localNode.localConfig.lora.config_ok_to_mqtt)
        except (AttributeError, TypeError):
            return False

    def _mqtt_forwarding_allowed(self, interface: Any) -> bool:
        return (
            self.config.mqtt_forwarding_policy == "inherit"
            and self._mqtt_uplink_permitted(interface)
        )

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._online = False
            interface, self._interface = self._interface, None
            retired, self._retired_interfaces = self._retired_interfaces, []
        self._reconnect_stop.set()
        self._reconnect_wakeup.set()
        if self._pub is not None:
            for callback, topic in (
                (self._on_receive, "meshtastic.receive"),
                (self._on_disconnected, "meshtastic.connection.lost"),
            ):
                with contextlib.suppress(Exception):
                    self._pub.unsubscribe(callback, topic)
        for item in [interface, *retired]:
            if item is None:
                continue
            with contextlib.suppress(Exception):
                item.close()
        thread = self._reconnect_thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=5.0)

    def _belongs_to_us(self, interface: Any) -> bool:
        with self._lock:
            return interface is self._interface

    def _on_disconnected(self, interface: Any, topic: Any = None) -> None:
        del topic
        self._mark_offline(interface, "PhoneAPI connection lost")

    def _mark_offline(self, interface: Any, detail: str) -> None:
        with self._lock:
            if self._closed or interface is not self._interface:
                return
            was_online = self._online
            self._online = False
            self._interface = None
            self._retired_interfaces.append(interface)
        if was_online and self._state_callback:
            self._state_callback(False, detail)
        self._reconnect_wakeup.set()

    def _reconnect_loop(self) -> None:
        delay = 1.0
        while not self._reconnect_stop.is_set():
            self._reconnect_wakeup.wait()
            self._reconnect_wakeup.clear()
            if self._reconnect_stop.is_set():
                return
            while not self._reconnect_stop.is_set():
                with self._lock:
                    if self._closed or self._interface is not None:
                        break
                    retired, self._retired_interfaces = self._retired_interfaces, []
                for interface in retired:
                    with contextlib.suppress(Exception):
                        interface.close()
                try:
                    with self._connect_lock:
                        replacement = self._open_interface()
                    with self._lock:
                        if self._closed or self._interface is not None:
                            keep = False
                        else:
                            self._interface = replacement
                            keep = True
                    if not keep:
                        with contextlib.suppress(Exception):
                            replacement.close()
                        break
                    self._set_local_node(replacement)
                    self._notify_connected()
                    delay = 1.0
                    break
                except Exception as exc:
                    if self._state_callback:
                        self._state_callback(False, f"PhoneAPI reconnect failed: {exc}")
                    if self._reconnect_stop.wait(delay):
                        return
                    delay = min(delay * 2.0, 30.0)

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
