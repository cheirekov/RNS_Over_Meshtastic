"""Unprivileged Linux Gateway Console HTTP sidecar."""

from __future__ import annotations

import base64
import difflib
import hmac
import io
import json
import os
import re
import subprocess
import threading
import time
from collections import deque
from datetime import UTC, datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from rns_meshtastic import __version__
from rns_meshtastic.config_schema import config_schema
from rns_meshtastic.contracts import (
    BridgeAlertV1,
    BridgeCapabilitiesV1,
    BridgePeerRouteV1,
    BridgeStatusV1,
    BridgeTrafficSnapshotV1,
    TrafficCounterV1,
    captured_at,
    to_dict,
)
from rns_meshtastic.event_journal import EventJournal
from rns_meshtastic.gateway_config import (
    MANAGED_FIELDS,
    environment_from_process,
    is_secret,
    redact_environment,
    render_env,
    stage_environment,
    validate_gateway_environment,
)
from rns_meshtastic.lxmd_control import request as lxmd_control_request
from rns_meshtastic.traffic_report import collect_rnstatus

CONFIG_FIELDS = MANAGED_FIELDS
HASH = re.compile(r"<([0-9a-fA-F]{32})>")
STORE = re.compile(r"Messagestore contains (\d+) messages, ([^(]+) \(([^ ]+) utilised of ([^)]+)\)")
TELEMETRY_STALE_MINIMUM_SECONDS = 30.0
TRANSIENT_ALERT_SECONDS = 120.0


def _counter(
    value: dict[str, Any] | None,
    *,
    available: bool = True,
    source: str = "rnstatus",
) -> TrafficCounterV1:
    value = value or {}
    return TrafficCounterV1(
        rx_bytes=int(value.get("rx_bytes", value.get("rx", 0)) or 0),
        tx_bytes=int(value.get("tx_bytes", value.get("tx", 0)) or 0),
        rx_bps=float(value.get("rx_bps", value.get("rxs", 0)) or 0),
        tx_bps=float(value.get("tx_bps", value.get("txs", 0)) or 0),
        available=bool(value.get("available", available)),
        source=str(value.get("source", source)),
    )


def _empty_traffic() -> dict[str, Any]:
    return {
        "transport_id": None,
        "transport_uptime": 0,
        "lora": {},
        "private_tcp": {},
        "public": {},
        "private_upstream": {},
        "public_interfaces": {},
        "private_interfaces": {},
        "private_tcp_client_count": 0,
    }


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _age_seconds(value: Any) -> float | None:
    try:
        when = datetime.fromisoformat(str(value))
        if when.tzinfo is None:
            when = when.replace(tzinfo=UTC)
        return max(0.0, (datetime.now(UTC) - when).total_seconds())
    except (TypeError, ValueError):
        return None


def _telemetry_stale_seconds(telemetry: dict[str, Any]) -> float:
    """Return a heartbeat-aware stale threshold with a safe legacy default."""
    try:
        heartbeat = float(telemetry.get("heartbeat_interval_seconds", 10.0))
    except (TypeError, ValueError):
        heartbeat = 10.0
    if heartbeat <= 0 or heartbeat > 300:
        heartbeat = 10.0
    return max(TELEMETRY_STALE_MINIMUM_SECONDS, heartbeat * 3.0)


def collect_discovery(config_dir: Path) -> list[dict[str, Any]]:
    """Read Reticulum's persisted interface-discovery catalogue.

    A silent empty list makes a disabled/broken shared-instance query
    indistinguishable from an honestly empty catalogue. Callers that need the
    distinction should catch the raised RuntimeError and expose it as status.
    """
    result = subprocess.run(
        ["rnstatus", "--config", str(config_dir), "--discovered", "--json"],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise RuntimeError(f"rnstatus discovery query failed: {detail}")
    for line in reversed(result.stdout.splitlines()):
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
        if isinstance(value, dict):
            candidates = value.get("discovered_interfaces") or value.get("interfaces")
            if isinstance(candidates, list):
                return [item for item in candidates if isinstance(item, dict)]
    raise RuntimeError("rnstatus discovery query did not return a JSON catalogue")


def collect_routes(config_dir: Path) -> list[dict[str, Any]]:
    result = subprocess.run(
        ["rnpath", "--config", str(config_dir), "--table", "--json"],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if result.returncode != 0:
        return []
    for line in reversed(result.stdout.splitlines()):
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, list):
            return [item for item in value[:1024] if isinstance(item, dict)]
    return []


def collect_lxmd_status(
    config_dir: Path,
    rns_config_dir: Path,
    control_socket: Path | None = None,
) -> dict[str, Any]:
    if control_socket is not None and control_socket.exists():
        try:
            value = lxmd_control_request(control_socket, "status")
            if value.get("status") == "ok":
                return value
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError):
            pass

    result = subprocess.run(
        ["lxmd", "--config", str(config_dir), "--rnsconfig", str(rns_config_dir), "--status"],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    output = result.stdout.strip()
    destination = HASH.search(output)
    store = STORE.search(output)
    return {
        "schema": 1,
        "up": result.returncode == 0 and destination is not None,
        "destination_hash": destination.group(1).lower() if destination else None,
        "message_count": int(store.group(1)) if store else None,
        "store_used": store.group(2).strip() if store else None,
        "store_utilisation": store.group(3) if store else None,
        "store_limit": store.group(4).strip() if store else None,
        "traffic": {
            "rx_bytes": 0,
            "tx_bytes": 0,
            "rx_bps": 0.0,
            "tx_bps": 0.0,
            "available": False,
            "source": "unavailable",
        },
        "summary": output[-1000:] if output else (result.stderr.strip()[-500:] or "unavailable"),
    }


class GatewayState:
    def __init__(
        self,
        config_dir: Path,
        lxmd_dir: Path,
        stage_dir: Path,
        *,
        control_socket: Path | None = None,
        event_file: Path | None = None,
    ) -> None:
        self.config_dir = config_dir
        self.lxmd_dir = lxmd_dir
        self.stage_dir = stage_dir
        self.control_socket = control_socket or config_dir / "lxmd-control.sock"
        self.events = EventJournal(event_file or config_dir / "gateway-events.jsonl")
        self._lock = threading.RLock()
        self._cached_lxmd: tuple[float, dict[str, Any]] = (0.0, {})
        self._upstream_observations: dict[str, dict[str, Any]] = {}
        self._previous_radio_online: bool | None = None
        self._previous_telemetry_counters: dict[str, int] = {}
        self._previous_reassembly: dict[str, int] = {}
        self._previous_peers: set[str] = set()
        self._seen_announce_observations: deque[str] = deque()
        self._seen_announce_keys: set[str] = set()
        self._transient_alerts: dict[str, tuple[float, BridgeAlertV1]] = {}
        self._radio_disconnect_times: deque[float] = deque()
        self._missing_since: float | None = None
        self._last_collector_error: str | None = None

    def active_environment(self) -> dict[str, str]:
        return redact_environment(self._active_environment_raw())

    def _active_environment_raw(self) -> dict[str, str]:
        process = environment_from_process()
        return {name: process.get(name, "") for name in CONFIG_FIELDS}

    def public_environment(self) -> dict[str, str]:
        return self.active_environment()

    def lxmd(self, *, refresh: bool = False) -> dict[str, Any]:
        with self._lock:
            when, value = self._cached_lxmd
            if not refresh and time.monotonic() - when < 5:
                return value
            value = collect_lxmd_status(self.lxmd_dir, self.config_dir, self.control_socket)
            self._cached_lxmd = (time.monotonic(), value)
            return value

    def status(self) -> dict[str, Any]:
        alerts: list[BridgeAlertV1] = []
        collector_error: str | None = None
        try:
            traffic = collect_rnstatus(self.config_dir)
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            traffic = _empty_traffic()
            collector_error = f"rnstatus collection failed: {error}"
            alerts.append(BridgeAlertV1("error", "status_collector_failed", collector_error))

        telemetry = _load_json(self.config_dir / "meshtastic-telemetry.json")
        discovery = self._discovery_status(traffic)
        if discovery["mode"] != "off" and not discovery["collector"]["available"]:
            alerts.append(
                BridgeAlertV1(
                    "warning",
                    "discovery_collector_failed",
                    "RNS discovery catalogue is unavailable: "
                    + str(discovery["collector"].get("error") or "unknown error"),
                )
            )

        try:
            lxmd = self.lxmd()
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            lxmd = {"up": False, "traffic": {"available": False, "source": "unavailable"}}
            alerts.append(BridgeAlertV1("error", "lxmd_status_failed", f"LXMD status failed: {error}"))
        try:
            routes = collect_routes(self.config_dir)
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
            routes = []

        queue = telemetry.get("queue") if isinstance(telemetry.get("queue"), dict) else {}
        queue_used = int(queue.get("fragments", 0) or 0)
        queue_limit = int(queue.get("limit", 0) or 0)
        queue_pressure = (queue_used / queue_limit) if queue_limit else 0.0
        if queue_limit and queue_pressure >= 0.75:
            alerts.append(BridgeAlertV1("warning", "lora_queue_high", "LoRa queue is at least 75% full"))

        telemetry_age = _age_seconds(telemetry.get("captured_at"))
        telemetry_stale_after = _telemetry_stale_seconds(telemetry)
        if not telemetry:
            alerts.append(
                BridgeAlertV1("error", "meshtastic_telemetry_missing", "Meshtastic telemetry is unavailable")
            )
        elif telemetry_age is None or telemetry_age > telemetry_stale_after:
            alerts.append(
                BridgeAlertV1("error", "meshtastic_telemetry_stale", "Meshtastic telemetry is stale")
            )
        elif not telemetry.get("online"):
            alerts.append(BridgeAlertV1("error", "meshtastic_offline", "Meshtastic transport is offline"))

        enhanced_upstreams = self._observe_upstreams(traffic.get("public_interfaces", {}))
        enhanced_private_upstreams = self._observe_upstreams(
            traffic.get("private_interfaces", {}), source="private"
        )
        for name, upstream in enhanced_upstreams.items():
            if not upstream.get("up"):
                alerts.append(BridgeAlertV1("warning", "public_upstream_down", f"{name} is down"))
            if upstream.get("flapping"):
                alerts.append(
                    BridgeAlertV1(
                        "warning",
                        "public_upstream_flapping",
                        f"{name} changed state at least three times in ten minutes",
                    )
                )
        for name, upstream in enhanced_private_upstreams.items():
            if not upstream.get("up"):
                alerts.append(BridgeAlertV1("warning", "private_upstream_down", f"{name} is down"))
            if upstream.get("flapping"):
                alerts.append(
                    BridgeAlertV1(
                        "warning",
                        "private_upstream_flapping",
                        f"{name} changed state at least three times in ten minutes",
                    )
                )

        if not lxmd.get("up"):
            alerts.append(
                BridgeAlertV1("warning", "lxmd_unavailable", "LXMD propagation service is unavailable")
            )
        utilisation = lxmd.get("store_utilisation_percent")
        if isinstance(utilisation, (int, float)) and utilisation >= 90:
            alerts.append(
                BridgeAlertV1("error", "lxmd_storage_critical", "LXMD message store is at least 90% full")
            )
        elif isinstance(utilisation, (int, float)) and utilisation >= 80:
            alerts.append(
                BridgeAlertV1("warning", "lxmd_storage_high", "LXMD message store is at least 80% full")
            )

        self._observe_telemetry(telemetry, collector_error)
        self._observe_announces(telemetry)
        alerts.extend(self._active_transient_alerts())
        reassembly = telemetry.get("reassembly") if isinstance(telemetry.get("reassembly"), dict) else {}
        missing = int(reassembly.get("missing_fragments", 0) or 0)
        now = time.monotonic()
        if missing:
            self._missing_since = self._missing_since or now
            if now - self._missing_since >= 30:
                alerts.append(
                    BridgeAlertV1(
                        "warning",
                        "reassembly_stalled",
                        f"{missing} fragments have remained missing for at least 30 seconds",
                    )
                )
        else:
            self._missing_since = None

        peers_list: list[BridgePeerRouteV1] = []
        peer_health: list[dict[str, Any]] = []
        for peer in telemetry.get("peers", []):
            if not isinstance(peer, dict):
                continue
            node_id = str(peer.get("node_id", "unknown"))
            route_count = sum(node_id in str(route.get("interface", "")) for route in routes)
            peers_list.append(BridgePeerRouteV1(peer=node_id, routes=route_count, source="meshtastic"))
            peer_health.append(
                {
                    "peer": node_id,
                    "last_inbound_at": peer.get("last_inbound_at"),
                    "idle_seconds": peer.get("idle_seconds"),
                    "announce_delivery_state": peer.get("announce_delivery_state", "unknown"),
                    "ordinary_announces_suppressed": int(peer.get("ordinary_announces_suppressed", 0) or 0),
                }
            )

        status = BridgeStatusV1(
            captured_at=captured_at(),
            running=collector_error is None,
            implementation="rns-over-meshtastic-linux",
            implementation_version=__version__,
            transport_id=traffic.get("transport_id"),
            uptime_seconds=float(traffic.get("transport_uptime", 0) or 0),
            radio_state="up"
            if telemetry.get("online")
            and telemetry_age is not None
            and telemetry_age <= telemetry_stale_after
            else "unknown/down",
            rns_state="up" if collector_error is None else "unknown/down",
            lxmd_state="up" if lxmd.get("up") else "unknown/down",
            policy_profile=os.environ.get("RNS_LORA_POLICY", "conservative"),
            topology=str(telemetry.get("mesh_mode") or os.environ.get("RNS_MESH_MODE", "unknown")),
            peers=tuple(peers_list),
            alerts=tuple(self._deduplicate_alerts(alerts)),
        )
        propagation = lxmd.get("traffic") if isinstance(lxmd.get("traffic"), dict) else {}
        snapshot = BridgeTrafficSnapshotV1(
            captured_at=status.captured_at,
            lora=_counter(traffic.get("lora"), source="rnstatus.rns_payload"),
            lan=_counter(traffic.get("private_tcp"), source="rnstatus.rns_payload"),
            public=_counter(traffic.get("public"), source="rnstatus.rns_payload"),
            propagation=_counter(
                propagation,
                available=bool(propagation.get("available", False)),
                source=str(propagation.get("source", "unavailable")),
            ),
        )
        traffic_snapshot = to_dict(snapshot)
        traffic_snapshot["private_upstream"] = to_dict(
            _counter(traffic.get("private_upstream"), source="rnstatus.rns_payload")
        )
        recent_announces = telemetry.get("recent_announces", [])
        if not isinstance(recent_announces, list):
            recent_announces = []
        activity_age = _age_seconds(telemetry.get("last_radio_activity_at"))
        return to_dict(status) | {
            "traffic": traffic_snapshot,
            "lan_client_count": int(traffic.get("private_tcp_client_count", 0) or 0),
            "queue_pressure_percent": round(queue_pressure * 100, 1),
            "telemetry_age_seconds": round(telemetry_age, 1) if telemetry_age is not None else None,
            "telemetry_stale_after_seconds": telemetry_stale_after,
            "radio_activity": {
                "last_at": telemetry.get("last_radio_activity_at"),
                "age_seconds": round(activity_age, 1) if activity_age is not None else None,
                "state": "active"
                if activity_age is not None and activity_age <= 60
                else ("idle" if activity_age is not None else "no_activity_yet"),
            },
            "meshtastic": telemetry,
            "meshtastic_peer_health": peer_health,
            "public_interfaces": enhanced_upstreams,
            "private_interfaces": enhanced_private_upstreams,
            "lxmd": lxmd,
            # Keep the original list for the early Console API consumers, but
            # provide a versioned, diagnostic-rich object for the UI/API.
            "discovery": discovery["candidates"],
            "discovery_status": discovery,
            "routes": routes,
            "recent_announces": recent_announces[-64:],
            "announce_observability": telemetry.get(
                "announce_observability",
                {"header_visibility": "unavailable", "payloads_recorded": False},
            ),
            "radio_peer_ready": bool(peers_list),
        }

    @staticmethod
    def _endpoint_values(value: str) -> list[str]:
        return [entry.strip() for entry in value.split(",") if entry.strip()]

    @staticmethod
    def _safe_discovery_candidate(raw: dict[str, Any]) -> dict[str, Any]:
        """Return operational metadata only; never expose discovery IFAC data."""
        supported = str(raw.get("type") or "") in {"BackboneInterface", "TCPServerInterface"}
        endpoint = None
        if supported and raw.get("reachable_on") and raw.get("port"):
            endpoint = f"{raw['reachable_on']}:{raw['port']}"
        return {
            "name": str(raw.get("name") or "discovered interface"),
            "type": str(raw.get("type") or "unknown"),
            "status": str(raw.get("status") or "unknown"),
            "transport": bool(raw.get("transport")),
            "network_id": str(raw.get("network_id") or ""),
            "transport_id": str(raw.get("transport_id") or ""),
            "endpoint": endpoint,
            "reachable_on": str(raw.get("reachable_on") or "") or None,
            "port": int(raw["port"]) if isinstance(raw.get("port"), int) else None,
            "hops": int(raw.get("hops", 0) or 0),
            "value": int(raw.get("value", 0) or 0),
            "discovered_at": raw.get("discovered") or raw.get("received"),
            "last_heard_at": raw.get("last_heard"),
            "autoconnect_supported": supported,
        }

    def _discovery_status(self, traffic: dict[str, Any]) -> dict[str, Any]:
        environment = self._active_environment_raw()
        mode = (environment.get("RNS_PUBLIC_DISCOVERY", "off") or "off").lower()
        sources = self._endpoint_values(environment.get("RNS_DISCOVERY_SOURCES", ""))
        public = self._endpoint_values(environment.get("RNS_PUBLIC_UPSTREAMS", ""))
        private = self._endpoint_values(environment.get("RNS_PRIVATE_UPSTREAMS", ""))
        public_bootstrap = set(
            self._endpoint_values(environment.get("RNS_PUBLIC_BOOTSTRAP_UPSTREAMS", ""))
        )
        private_bootstrap = set(
            self._endpoint_values(environment.get("RNS_PRIVATE_BOOTSTRAP_UPSTREAMS", ""))
        )
        try:
            candidates = [self._safe_discovery_candidate(item) for item in collect_discovery(self.config_dir)]
            collector: dict[str, Any] = {"available": True, "error": None}
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            candidates = []
            collector = {"available": False, "error": str(error)}

        counts = {"available": 0, "unknown": 0, "stale": 0}
        for candidate in candidates:
            status = candidate["status"]
            if status in counts:
                counts[status] += 1
        autoconnected = traffic.get("autoconnected_interfaces", [])
        if not isinstance(autoconnected, list):
            autoconnected = []
        bootstrap = {
            "public": [
                {"endpoint": endpoint, "bootstrap": endpoint in public_bootstrap}
                for endpoint in public
            ],
            "private": [
                {"endpoint": endpoint, "bootstrap": endpoint in private_bootstrap, "ifac": True}
                for endpoint in private
            ],
        }
        return {
            "schema": 1,
            "mode": mode if mode in {"off", "manual", "trusted_auto"} else "invalid",
            "enabled": mode != "off",
            "collector": collector,
            "trusted_sources": sources,
            "autoconnect": {
                "enabled": mode == "trusted_auto",
                "maximum": int(environment.get("RNS_DISCOVERY_MAX", "1") or 1),
                "active": [item for item in autoconnected if isinstance(item, dict)],
                "gravity": int(environment.get("RNS_DISCOVERY_GRAVITY", "0") or 0),
            },
            "bootstrap": bootstrap,
            "safety": "Discovered interfaces are boundary-only; public announces never export to LoRa.",
            "counts": {"total": len(candidates), **counts},
            "candidates": candidates,
        }

    def _safe_discovery(self) -> list[dict[str, Any]]:
        try:
            return collect_discovery(self.config_dir)
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
            return []

    def _observe_telemetry(self, telemetry: dict[str, Any], collector_error: str | None) -> None:
        with self._lock:
            online = bool(telemetry.get("online")) if telemetry else None
            if online is not None and online != self._previous_radio_online:
                self.events.append(
                    "radio",
                    "info" if online else "warning",
                    "radio_up" if online else "radio_down",
                    "Meshtastic transport is online" if online else "Meshtastic transport is offline",
                )
                self._previous_radio_online = online

            counters = telemetry.get("counters") if isinstance(telemetry.get("counters"), dict) else {}
            watched = {
                "tx_frames_rejected": ("warning", "lora_tx_rejected", "LoRa frame admission rejected"),
                "send_failures": ("error", "lora_send_failed", "Meshtastic backend send failed"),
                "backend_down": ("warning", "radio_reconnect", "Meshtastic backend disconnected"),
            }
            for key, (severity, code, message) in watched.items():
                current = int(counters.get(key, 0) or 0)
                previous = self._previous_telemetry_counters.get(key, current)
                if current > previous:
                    delta = current - previous
                    alert = BridgeAlertV1(
                        severity,
                        code,
                        f"{message} {delta} time(s) since the previous observation",
                    )
                    self._set_transient(alert)
                    self.events.append("radio", severity, code, alert.message)
                    if key == "backend_down":
                        self._radio_disconnect_times.extend([time.monotonic()] * delta)
                self._previous_telemetry_counters[key] = current

            suppressed = int(counters.get("idle_peer_announces_suppressed", 0) or 0)
            previous_suppressed = self._previous_telemetry_counters.get(
                "idle_peer_announces_suppressed", suppressed
            )
            if suppressed > previous_suppressed:
                delta = suppressed - previous_suppressed
                self.events.append(
                    "radio",
                    "info",
                    "idle_peer_announces_suppressed",
                    f"Suppressed {delta} ordinary periodic announce(s) to idle peer(s); "
                    "data, proofs and path responses remained enabled",
                )
            self._previous_telemetry_counters["idle_peer_announces_suppressed"] = suppressed

            now = time.monotonic()
            while self._radio_disconnect_times and self._radio_disconnect_times[0] < now - 600:
                self._radio_disconnect_times.popleft()
            if len(self._radio_disconnect_times) >= 3:
                churn = BridgeAlertV1(
                    "warning",
                    "radio_reconnect_churn",
                    "Meshtastic backend disconnected at least three times in ten minutes",
                )
                if churn.code not in self._transient_alerts:
                    self.events.append("radio", "warning", churn.code, churn.message)
                self._set_transient(churn)

            reassembly = telemetry.get("reassembly") if isinstance(telemetry.get("reassembly"), dict) else {}
            for key, code, message in (
                ("repair_throttled", "repair_throttled", "Fragment repair was throttled"),
                ("assemblies_expired", "reassembly_expired", "Incomplete reassembly expired"),
            ):
                current = int(reassembly.get(key, 0) or 0)
                previous = self._previous_reassembly.get(key, current)
                if current > previous:
                    alert = BridgeAlertV1(
                        "warning",
                        code,
                        f"{message} {current - previous} time(s) since the previous observation",
                    )
                    self._set_transient(alert)
                    self.events.append("radio", "warning", code, alert.message)
                self._previous_reassembly[key] = current
            capped = int(reassembly.get("capped_repairs", 0) or 0)
            if capped:
                self._set_transient(
                    BridgeAlertV1(
                        "warning", "repair_capped", f"{capped} fragment repairs reached their attempt cap"
                    )
                )

            peers = {
                str(peer.get("node_id"))
                for peer in telemetry.get("peers", [])
                if isinstance(peer, dict) and peer.get("node_id")
            }
            for peer in sorted(peers - self._previous_peers):
                self.events.append("radio", "info", "peer_learned", f"Learned Meshtastic peer {peer}")
            for peer in sorted(self._previous_peers - peers):
                self.events.append(
                    "radio", "info", "peer_removed", f"Meshtastic peer {peer} is no longer active"
                )
            self._previous_peers = peers

            if collector_error != self._last_collector_error:
                if collector_error:
                    self.events.append("console", "error", "status_collector_failed", collector_error)
                elif self._last_collector_error:
                    self.events.append(
                        "console", "info", "status_collector_recovered", "Status collection recovered"
                    )
                self._last_collector_error = collector_error

    def _observe_upstreams(self, upstreams: dict[str, Any], *, source: str = "public") -> dict[str, Any]:
        wall_now = captured_at()
        monotonic_now = time.monotonic()
        enhanced: dict[str, Any] = {}
        with self._lock:
            for name, raw in upstreams.items():
                value = dict(raw) if isinstance(raw, dict) else {}
                up = bool(value.get("up"))
                counters = (int(value.get("rx", 0) or 0), int(value.get("tx", 0) or 0))
                observation_key = f"{source}:{name}"
                observed = self._upstream_observations.get(observation_key)
                if observed is None:
                    observed = {
                        "up": up,
                        "reconnects": 0,
                        "observations": 0,
                        "up_observations": 0,
                        "first_observed_at": wall_now,
                        "last_state_change_at": wall_now,
                        "last_activity_at": None,
                        "counters": counters,
                        "changes": deque(),
                    }
                    self._upstream_observations[observation_key] = observed
                elif observed["up"] != up:
                    if up:
                        observed["reconnects"] += 1
                    observed["up"] = up
                    observed["last_state_change_at"] = wall_now
                    observed["changes"].append(monotonic_now)
                    self.events.append(
                        source,
                        "info" if up else "warning",
                        f"{source}_upstream_up" if up else f"{source}_upstream_down",
                        f"{name} is {'up' if up else 'down'}",
                    )
                while observed["changes"] and observed["changes"][0] < monotonic_now - 600:
                    observed["changes"].popleft()
                if observed["counters"] != counters:
                    observed["counters"] = counters
                    observed["last_activity_at"] = wall_now
                observed["observations"] += 1
                if up:
                    observed["up_observations"] += 1
                stability = 100.0 * observed["up_observations"] / observed["observations"]
                enhanced[name] = value | {
                    "reconnects": observed["reconnects"],
                    "first_observed_at": observed["first_observed_at"],
                    "last_state_change_at": observed["last_state_change_at"],
                    "last_activity_at": observed["last_activity_at"],
                    "observed_up_percent": round(stability, 1),
                    "flapping": len(observed["changes"]) >= 3,
                    "latency_ms": None,
                }
        return enhanced

    def _observe_announces(self, telemetry: dict[str, Any]) -> None:
        observations = telemetry.get("recent_announces", [])
        if not isinstance(observations, list):
            return
        with self._lock:
            for item in observations:
                if not isinstance(item, dict):
                    continue
                key = "|".join(
                    str(item.get(name, ""))
                    for name in ("captured_at", "direction", "meshtastic_peer", "destination_hash")
                )
                if not key or key in self._seen_announce_keys:
                    continue
                self._seen_announce_keys.add(key)
                self._seen_announce_observations.append(key)
                while len(self._seen_announce_observations) > 256:
                    oldest = self._seen_announce_observations.popleft()
                    self._seen_announce_keys.discard(oldest)
                direction = str(item.get("direction", "unknown"))
                peer = str(item.get("meshtastic_peer", "unknown"))
                destination = str(item.get("destination_hash", "unknown"))
                self.events.append(
                    "rns",
                    "info",
                    "announce_to_mesh" if direction == "to_mesh" else "announce_from_mesh",
                    f"RNS announce {direction} via {peer} for {destination}",
                )

    def _set_transient(self, alert: BridgeAlertV1) -> None:
        self._transient_alerts[alert.code] = (time.monotonic() + TRANSIENT_ALERT_SECONDS, alert)

    def _active_transient_alerts(self) -> list[BridgeAlertV1]:
        now = time.monotonic()
        with self._lock:
            expired = [code for code, (until, _) in self._transient_alerts.items() if until <= now]
            for code in expired:
                self._transient_alerts.pop(code, None)
            return [alert for _, alert in self._transient_alerts.values()]

    @staticmethod
    def _deduplicate_alerts(alerts: list[BridgeAlertV1]) -> list[BridgeAlertV1]:
        result: list[BridgeAlertV1] = []
        seen: set[str] = set()
        for alert in alerts:
            if alert.code not in seen:
                result.append(alert)
                seen.add(alert.code)
        return result

    def validate(self, changes: dict[str, Any]) -> dict[str, Any]:
        unknown = sorted(set(changes) - set(CONFIG_FIELDS))
        if unknown:
            raise ValueError("unsupported configuration fields: " + ", ".join(unknown))
        secret_changes = sorted(name for name in changes if is_secret(name))
        if secret_changes:
            raise ValueError("secret fields must be edited in the protected env file")
        merged = environment_from_process()
        merged.update({name: str(value) for name, value in changes.items()})
        result = validate_gateway_environment(merged)
        old = render_env(self.active_environment()).splitlines()
        new_values = {name: result.values.get(name, "") for name in CONFIG_FIELDS}
        new = render_env(redact_environment(new_values)).splitlines()
        diff = "\n".join(difflib.unified_diff(old, new, fromfile="active", tofile="staged", lineterm=""))
        return {"valid": True, "warnings": result.warnings, "diff": diff}

    def stage(self, changes: dict[str, Any]) -> dict[str, Any]:
        validation = self.validate(changes)
        merged = environment_from_process()
        merged.update({name: str(value) for name, value in changes.items()})
        target = stage_environment(self.stage_dir, merged)
        self.events.append("console", "info", "configuration_staged", f"Staged configuration {target.name}")
        return validation | {
            "stage_id": target.name,
            "export_command": "umask 077; docker compose --env-file .env.linux-service "
            "-f compose.linux.yaml run --rm gateway-console "
            f"rns-meshtastic gateway-export --stage-file {target} > .env.pending",
            "apply_command": "uv run rns-meshtastic gateway-apply "
            "--stage-file .env.pending --target .env.linux-service "
            "--compose-file compose.linux.yaml",
        }

    def announce_lxmd(self) -> dict[str, Any]:
        value = lxmd_control_request(self.control_socket, "announce")
        with self._lock:
            self._cached_lxmd = (0.0, {})
        return value


def _capabilities() -> dict[str, Any]:
    return to_dict(
        BridgeCapabilitiesV1(
            implementation="rns-over-meshtastic-linux",
            implementation_version=__version__,
            rns_tcp_port=int(os.environ.get("RNS_TCP_LISTEN_PORT", "4242")),
            status_api_port=int(os.environ.get("RNS_CONSOLE_PORT", "8787")),
        )
    )


def _prometheus(status: dict[str, Any]) -> str:
    traffic = status["traffic"]
    lines = [
        "# HELP rns_meshtastic_bridge_up Linux bridge status collection succeeded.",
        "# TYPE rns_meshtastic_bridge_up gauge",
        f"rns_meshtastic_bridge_up {1 if status.get('running') else 0}",
    ]
    for network in ("lora", "lan", "public", "private_upstream", "propagation"):
        counter = traffic[network]
        available = 1 if counter.get("available", True) else 0
        lines.append(f'rns_meshtastic_traffic_available{{network="{network}"}} {available}')
        if available:
            for direction in ("rx", "tx"):
                lines.append(
                    "rns_meshtastic_traffic_bytes_total"
                    f'{{network="{network}",direction="{direction}"}} '
                    f"{int(counter[f'{direction}_bytes'])}"
                )
    queue = status.get("meshtastic", {}).get("queue", {})
    lines.append(f"rns_meshtastic_lora_queue_fragments {int(queue.get('fragments', 0) or 0)}")
    lines.append(f"rns_meshtastic_lora_queue_limit {int(queue.get('limit', 0) or 0)}")
    lines.append(f"rns_meshtastic_lan_clients {int(status.get('lan_client_count', 0) or 0)}")
    return "\n".join(lines) + "\n"


INDEX_HTML = (Path(__file__).with_name("static") / "gateway-console.html").read_text(encoding="utf-8")


class ConsoleHandler(BaseHTTPRequestHandler):
    server_version = "RNSMeshtasticConsole/2"

    @property
    def state(self) -> GatewayState:
        return self.server.gateway_state  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: object) -> None:
        # Five-second UI polling should not dominate operational logs. Keep
        # mutations, errors and non-routine paths visible.
        if self.command == "GET" and urlparse(self.path).path in {
            "/healthz",
            "/api/v1/status",
            "/api/v1/discovery",
            "/api/v1/events",
        }:
            return
        super().log_message(format, *args)

    def _send(
        self,
        status: int,
        body: bytes,
        content_type: str,
        *,
        headers: dict[str, str] | None = None,
    ) -> bool:
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'",
            )
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(body)
            return True
        except (BrokenPipeError, ConnectionResetError):
            # Browser navigation and VPN reconnects can abandon an in-flight
            # poll. This is not a collector or gateway failure.
            return False

    def _json(self, status: int, value: Any) -> None:
        self._send(status, json.dumps(value, sort_keys=True).encode(), "application/json")

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        if path != "/healthz" and not self._require_authentication():
            return
        try:
            if path == "/healthz":
                status = self.state.status()
                response_status = 200 if status.get("running") else 503
                self._json(response_status, {"status": "ok" if response_status == 200 else "unavailable"})
            elif path == "/":
                self._send(200, INDEX_HTML.encode(), "text/html; charset=utf-8")
            elif path == "/api/v1/capabilities":
                self._json(200, _capabilities())
            elif path == "/api/v1/status":
                self._json(200, self.state.status())
            elif path == "/api/v1/discovery":
                # A narrow endpoint for companion/operations tooling. It is
                # derived from the same safe representation as /status and
                # never contains an advertised config entry or IFAC key.
                traffic = collect_rnstatus(self.state.config_dir)
                self._json(200, self.state._discovery_status(traffic))
            elif path == "/api/v1/config":
                self._json(200, {"schema": 1, "values": self.state.public_environment()})
            elif path == "/api/v1/config/schema":
                self._json(200, config_schema())
            elif path == "/api/v1/events":
                query = parse_qs(parsed.query)
                self._json(
                    200,
                    self.state.events.read(
                        after=int(query.get("after", ["0"])[0]), limit=int(query.get("limit", ["100"])[0])
                    ),
                )
            elif path == "/metrics":
                self._send(200, _prometheus(self.state.status()).encode(), "text/plain; version=0.0.4")
            elif path == "/api/v1/lxmd/qr":
                destination = self.state.lxmd().get("destination_hash")
                if not destination:
                    self._json(404, {"error": "LXMD propagation hash is unavailable"})
                    return
                import segno

                output = io.BytesIO()
                segno.make(destination, error="m").save(output, kind="svg", scale=6, border=2)
                self._send(200, output.getvalue(), "image/svg+xml")
            else:
                self._json(404, {"error": "not found"})
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(error)})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if not self._require_authentication():
            return
        if path not in {"/api/v1/config/validate", "/api/v1/config/stage", "/api/v1/lxmd/announce"}:
            self._json(404, {"error": "not found"})
            return
        try:
            if not self._same_origin():
                self._json(403, {"error": "cross-origin mutation is forbidden"})
                return
            if self.headers.get_content_type() != "application/json":
                self._json(415, {"error": "Content-Type must be application/json"})
                return
            value = self._read_json()
            if path == "/api/v1/lxmd/announce":
                result = self.state.announce_lxmd()
                status = 429 if result.get("status") == "rate_limited" else 200
                if result.get("status") == "error":
                    status = 503
                self._json(status, result)
                return
            result = self.state.stage(value) if path.endswith("/stage") else self.state.validate(value)
            self._json(200, result)
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
            self._json(400, {"valid": False, "error": str(error)})

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 64 * 1024:
            raise ValueError("request body must be at most 64 KiB")
        value = json.loads(self.rfile.read(length) or b"{}")
        if not isinstance(value, dict):
            raise ValueError("request body must be a JSON object")
        return value

    def _same_origin(self) -> bool:
        origin = self.headers.get("Origin")
        if not origin:
            return True
        parsed = urlparse(origin)
        return parsed.scheme in {"http", "https"} and parsed.netloc == self.headers.get("Host")

    def _require_authentication(self) -> bool:
        mode = str(getattr(self.server, "console_auth_mode", "off"))
        if mode == "off":
            return True
        supplied = self.headers.get("Authorization", "")
        expected_username = str(getattr(self.server, "console_username", ""))
        expected_password = str(getattr(self.server, "console_password", ""))
        valid = False
        if supplied.startswith("Basic "):
            try:
                decoded = base64.b64decode(supplied[6:], validate=True).decode("utf-8")
                username, password = decoded.split(":", 1)
                valid = hmac.compare_digest(username, expected_username) and hmac.compare_digest(
                    password, expected_password
                )
            except (ValueError, UnicodeDecodeError):
                valid = False
        if valid:
            return True
        self._send(
            401,
            json.dumps({"error": "authentication required"}).encode(),
            "application/json",
            headers={"WWW-Authenticate": 'Basic realm="RNS Gateway Console", charset="UTF-8"'},
        )
        return False


def serve(
    bind: str,
    port: int,
    state: GatewayState,
    *,
    auth_mode: str = "off",
    username: str = "",
    password: str = "",
) -> None:
    server = ThreadingHTTPServer((bind, port), ConsoleHandler)
    server.gateway_state = state  # type: ignore[attr-defined]
    server.console_auth_mode = auth_mode  # type: ignore[attr-defined]
    server.console_username = username  # type: ignore[attr-defined]
    server.console_password = password  # type: ignore[attr-defined]
    server.serve_forever()
