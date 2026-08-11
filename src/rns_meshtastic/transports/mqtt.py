"""Binary Meshtastic MQTT ServiceEnvelope backend.

This backend intentionally does not use the JSON topics. Port 76 is carried as
the decoded binary payload of a MeshPacket. A physical Meshtastic MQTT gateway
must use a matching channel with downlink enabled and MQTT encryption disabled.
"""

from __future__ import annotations

import hashlib
import random
import ssl
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any

from rns_meshtastic.addresses import BROADCAST_NUM, format_node_id, parse_node_id
from rns_meshtastic.transports.base import PacketCallback, StateCallback, TransportBackend


@dataclass(frozen=True, slots=True)
class MqttConfig:
    host: str
    virtual_node_id: str
    channel_name: str
    port: int = 1883
    root: str = "msh/EU_868"
    username: str | None = None
    password: str | None = None
    tls: bool = False
    tls_insecure: bool = False
    channel_index: int = 0
    downlink_hop_limit: int = 0
    qos: int = 1
    subscribe_pki: bool = False
    client_id: str | None = None
    dedup_ttl: float = 300.0

    def __post_init__(self) -> None:
        if not self.host:
            raise ValueError("MQTT host is required")
        parse_node_id(self.virtual_node_id)
        if (
            not self.channel_name
            or "/" in self.channel_name
            or "+" in self.channel_name
            or "#" in self.channel_name
        ):
            raise ValueError("channel_name must be a non-empty MQTT topic component")
        if not 1 <= self.port <= 65535:
            raise ValueError("MQTT port is invalid")
        if not 0 <= self.channel_index <= 7:
            raise ValueError("channel_index must be between 0 and 7")
        if not 0 <= self.downlink_hop_limit <= 7:
            raise ValueError("downlink_hop_limit must be between 0 and 7")
        if self.qos not in {0, 1, 2}:
            raise ValueError("MQTT qos must be 0, 1 or 2")


class MqttBackend(TransportBackend):
    def __init__(self, config: MqttConfig) -> None:
        self.config = config
        self.local_node_id = format_node_id(config.virtual_node_id)
        self._node_num = parse_node_id(config.virtual_node_id)
        self._packet_callback: PacketCallback | None = None
        self._state_callback: StateCallback | None = None
        self._client: Any | None = None
        self._connected = threading.Event()
        self._closed = False
        self._lock = threading.RLock()
        self._seen: OrderedDict[tuple[int, int, bytes], float] = OrderedDict()
        self._next_packet_id = random.SystemRandom().randrange(1, 0xFFFFFFFF)

    @property
    def topic_prefix(self) -> str:
        return f"{self.config.root.rstrip('/')}/2/e"

    def start(self, packet_callback: PacketCallback, state_callback: StateCallback) -> None:
        import paho.mqtt.client as mqtt

        self._packet_callback = packet_callback
        self._state_callback = state_callback
        client_id = self.config.client_id or f"rns-{self.local_node_id[1:]}"
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id, clean_session=True)
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message = self._on_message
        if self.config.username is not None:
            client.username_pw_set(self.config.username, self.config.password)
        if self.config.tls:
            certificate_requirement = ssl.CERT_NONE if self.config.tls_insecure else ssl.CERT_REQUIRED
            client.tls_set(cert_reqs=certificate_requirement)
            client.tls_insecure_set(self.config.tls_insecure)
        with self._lock:
            self._client = client
        client.connect_async(self.config.host, self.config.port, keepalive=60)
        client.loop_start()

    def send(self, payload: bytes, destination: str) -> None:
        from meshtastic.protobuf import mesh_pb2, mqtt_pb2, portnums_pb2

        if not self._connected.is_set():
            raise ConnectionError("MQTT broker is not connected")
        packet = mesh_pb2.MeshPacket()
        setattr(packet, "from", self._node_num)
        packet.to = parse_node_id(destination)
        with self._lock:
            packet.id = self._next_packet_id
            self._next_packet_id = (self._next_packet_id + 1) & 0xFFFFFFFF or 1
        packet.channel = self.config.channel_index
        packet.hop_limit = self.config.downlink_hop_limit
        packet.hop_start = self.config.downlink_hop_limit
        packet.want_ack = False
        packet.decoded.portnum = portnums_pb2.PortNum.RETICULUM_TUNNEL_APP
        packet.decoded.payload = payload
        packet.priority = mesh_pb2.MeshPacket.Priority.RELIABLE

        envelope = mqtt_pb2.ServiceEnvelope()
        envelope.packet.CopyFrom(packet)
        envelope.channel_id = self.config.channel_name
        envelope.gateway_id = self.local_node_id
        topic = f"{self.topic_prefix}/{self.config.channel_name}/{self.local_node_id}"
        with self._lock:
            if self._client is None:
                raise ConnectionError("MQTT client is closed")
            info = self._client.publish(
                topic,
                envelope.SerializeToString(),
                qos=self.config.qos,
                retain=False,
            )
        if info.rc != 0:
            raise ConnectionError(f"MQTT publish failed with rc={info.rc}")

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            client, self._client = self._client, None
        self._connected.clear()
        if client is not None:
            try:
                client.disconnect()
            finally:
                client.loop_stop()

    def _on_connect(
        self,
        client: Any,
        userdata: Any,
        flags: Any,
        reason_code: Any,
        properties: Any,
    ) -> None:
        del userdata, flags, properties
        reason_value = getattr(reason_code, "value", reason_code)
        if bool(getattr(reason_code, "is_failure", False)) or reason_value != 0:
            if self._state_callback:
                self._state_callback(False, f"MQTT connect rejected: {reason_code}")
            return
        client.subscribe(
            f"{self.topic_prefix}/{self.config.channel_name}/+",
            qos=self.config.qos,
        )
        if self.config.subscribe_pki:
            client.subscribe(f"{self.topic_prefix}/PKI/+", qos=self.config.qos)
        self._connected.set()
        if self._state_callback:
            self._state_callback(True, None)

    def _on_disconnect(
        self,
        client: Any,
        userdata: Any,
        disconnect_flags: Any,
        reason_code: Any,
        properties: Any,
    ) -> None:
        del client, userdata, disconnect_flags, properties
        self._connected.clear()
        if not self._closed and self._state_callback:
            self._state_callback(False, f"MQTT disconnected: {reason_code}")

    def _on_message(self, client: Any, userdata: Any, message: Any) -> None:
        del client, userdata
        from meshtastic.protobuf import mqtt_pb2, portnums_pb2

        try:
            envelope = mqtt_pb2.ServiceEnvelope.FromString(message.payload)
            if envelope.gateway_id == self.local_node_id or not envelope.HasField("packet"):
                return
            packet = envelope.packet
            if not packet.HasField("decoded"):
                return
            if packet.decoded.portnum != portnums_pb2.PortNum.RETICULUM_TUNNEL_APP:
                return
            source_num = int(getattr(packet, "from")) & 0xFFFFFFFF
            if source_num == BROADCAST_NUM:
                return
            digest = hashlib.blake2s(packet.decoded.payload, digest_size=8).digest()
            dedup_key = (source_num, int(packet.id), digest)
            now = time.monotonic()
            with self._lock:
                self._cleanup_seen_locked(now)
                if dedup_key in self._seen:
                    return
                self._seen[dedup_key] = now
            if self._packet_callback:
                self._packet_callback(
                    format_node_id(source_num),
                    format_node_id(int(packet.to) & 0xFFFFFFFF),
                    bytes(packet.decoded.payload),
                )
        except Exception as exc:
            if self._state_callback:
                self._state_callback(self._connected.is_set(), f"ignored invalid MQTT envelope: {exc}")

    def _cleanup_seen_locked(self, now: float) -> None:
        cutoff = now - self.config.dedup_ttl
        while self._seen:
            key, seen_at = next(iter(self._seen.items()))
            if seen_at >= cutoff:
                break
            self._seen.pop(key, None)
