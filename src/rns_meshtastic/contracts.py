"""Versioned, secret-free bridge telemetry contracts.

The same top-level shapes are used by the Linux Gateway Console and the
Android companion API.  The wire representation is deliberately plain JSON so
clients do not need to link this GPL package to consume bridge capabilities.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from typing import Any

CONTRACT_VERSION = 1


def captured_at() -> str:
    return datetime.now(UTC).isoformat()


@dataclass(frozen=True, slots=True)
class BridgeAlertV1:
    severity: str
    code: str
    message: str

    def __post_init__(self) -> None:
        if self.severity not in {"info", "warning", "error"}:
            raise ValueError("alert severity must be info, warning or error")


@dataclass(frozen=True, slots=True)
class BridgePeerRouteV1:
    peer: str
    routes: int = 0
    last_seen_seconds: float | None = None
    source: str = "reticulum"


@dataclass(frozen=True, slots=True)
class TrafficCounterV1:
    rx_bytes: int = 0
    tx_bytes: int = 0
    rx_bps: float = 0.0
    tx_bps: float = 0.0


@dataclass(frozen=True, slots=True)
class BridgeTrafficSnapshotV1:
    captured_at: str
    lora: TrafficCounterV1
    lan: TrafficCounterV1
    public: TrafficCounterV1
    propagation: TrafficCounterV1 = field(default_factory=TrafficCounterV1)


@dataclass(frozen=True, slots=True)
class BridgeCapabilitiesV1:
    implementation: str
    implementation_version: str
    rns_tcp_port: int
    status_api_port: int | None
    constrained_transport: bool = True
    realtime_supported: bool = False
    maximum_serialized_rns_bytes: int = 8192
    meshtastic_portnum: int = 76
    addressing_modes: tuple[str, ...] = (
        "broadcast",
        "gateway_unicast",
        "auto_single_peer",
        "auto_multi_peer",
    )


@dataclass(frozen=True, slots=True)
class BridgeStatusV1:
    captured_at: str
    running: bool
    implementation: str
    implementation_version: str
    transport_id: str | None
    uptime_seconds: float
    radio_state: str
    rns_state: str
    lxmd_state: str
    policy_profile: str
    topology: str
    peers: tuple[BridgePeerRouteV1, ...] = ()
    alerts: tuple[BridgeAlertV1, ...] = ()


def to_dict(value: Any) -> dict[str, Any]:
    """Return a JSON-ready contract with an explicit schema version."""

    return {"schema": CONTRACT_VERSION, **asdict(value)}

