"""Unprivileged, read-mostly Linux Gateway Console HTTP sidecar."""

from __future__ import annotations

import difflib
import io
import json
import os
import re
import subprocess
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from rns_meshtastic import __version__
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
from rns_meshtastic.gateway_config import (
    environment_from_process,
    redact_environment,
    render_env,
    stage_environment,
    validate_gateway_environment,
)
from rns_meshtastic.traffic_report import collect_rnstatus

CONFIG_FIELDS = (
    "MESHTASTIC_TCP_HOST",
    "MESHTASTIC_TCP_PORT",
    "MESHTASTIC_CHANNEL_INDEX",
    "MESHTASTIC_HOP_LIMIT",
    "MESHTASTIC_MQTT_FORWARDING_POLICY",
    "RNS_MESH_MODE",
    "RNS_GATEWAY_ROLE",
    "RNS_GATEWAY_NODE",
    "RNS_ALLOWED_NODES",
    "RNS_MAX_PEERS",
    "RNS_LORA_POLICY",
    "RNS_RADIO_TX_INTERVAL",
    "RNS_RADIO_QUEUE_FRAGMENTS",
    "RNS_REPAIR_REQUEST_BUDGET",
    "RNS_REPAIR_BUDGET_WINDOW",
    "RNS_PUBLIC_UPSTREAMS",
    "RNS_LAN_PUBLIC_VISIBILITY",
    "RNS_PUBLIC_DISCOVERY",
    "RNS_DISCOVERY_SOURCES",
    "RNS_DISCOVERY_MAX",
    "RNS_TCP_PUBLISH_IP",
    "RNS_TCP_LISTEN_PORT",
    "LXMD_NODE_NAME",
    "LXMD_DISPLAY_NAME",
    "LXMD_ANNOUNCE_INTERVAL",
    "LXMD_STORAGE_LIMIT_MB",
    "LXMD_MESSAGE_MAX_KB",
    "LXMD_SYNC_MAX_KB",
    "LXMD_AUTH_REQUIRED",
)
HASH = re.compile(r"<([0-9a-fA-F]{32})>")
STORE = re.compile(
    r"Messagestore contains (\d+) messages, ([^(]+) \(([^ ]+) utilised of ([^)]+)\)"
)


def _counter(value: dict[str, Any]) -> TrafficCounterV1:
    return TrafficCounterV1(
        rx_bytes=int(value.get("rx", 0) or 0),
        tx_bytes=int(value.get("tx", 0) or 0),
        rx_bps=float(value.get("rxs", 0) or 0),
        tx_bps=float(value.get("txs", 0) or 0),
    )


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def collect_discovery(config_dir: Path) -> list[dict[str, Any]]:
    result = subprocess.run(
        ["rnstatus", "--config", str(config_dir), "--discovered", "--json"],
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
            return [item for item in value if isinstance(item, dict)]
        if isinstance(value, dict):
            candidates = value.get("discovered_interfaces") or value.get("interfaces")
            if isinstance(candidates, list):
                return [item for item in candidates if isinstance(item, dict)]
    return []


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


def collect_lxmd_status(config_dir: Path, rns_config_dir: Path) -> dict[str, Any]:
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
        "up": result.returncode == 0 and destination is not None,
        "destination_hash": destination.group(1).lower() if destination else None,
        "message_count": int(store.group(1)) if store else None,
        "store_used": store.group(2).strip() if store else None,
        "store_utilisation": store.group(3) if store else None,
        "store_limit": store.group(4).strip() if store else None,
        "summary": output[-4000:] if output else (result.stderr.strip()[-1000:] or "unavailable"),
    }


class GatewayState:
    def __init__(self, config_dir: Path, lxmd_dir: Path, stage_dir: Path) -> None:
        self.config_dir = config_dir
        self.lxmd_dir = lxmd_dir
        self.stage_dir = stage_dir
        self._lock = threading.Lock()
        self._cached_lxmd: tuple[float, dict[str, Any]] = (0.0, {})
        self._upstream_observations: dict[str, dict[str, Any]] = {}

    def active_environment(self) -> dict[str, str]:
        process = environment_from_process()
        return {name: process.get(name, "") for name in CONFIG_FIELDS}

    def lxmd(self) -> dict[str, Any]:
        with self._lock:
            when, value = self._cached_lxmd
            if time.monotonic() - when < 30:
                return value
            value = collect_lxmd_status(self.lxmd_dir, self.config_dir)
            self._cached_lxmd = (time.monotonic(), value)
            return value

    def status(self) -> dict[str, Any]:
        traffic = collect_rnstatus(self.config_dir)
        telemetry = _load_json(self.config_dir / "meshtastic-telemetry.json")
        lxmd = self.lxmd()
        routes = collect_routes(self.config_dir)
        queue = telemetry.get("queue") if isinstance(telemetry.get("queue"), dict) else {}
        queue_used = int(queue.get("fragments", 0) or 0)
        queue_limit = int(queue.get("limit", 0) or 0)
        alerts: list[BridgeAlertV1] = []
        if queue_limit and queue_used / queue_limit >= 0.75:
            alerts.append(BridgeAlertV1("warning", "lora_queue_high", "LoRa queue is at least 75% full"))
        for name, upstream in traffic.get("public_interfaces", {}).items():
            if not upstream.get("up"):
                alerts.append(BridgeAlertV1("warning", "public_upstream_down", f"{name} is down"))
        if telemetry and not telemetry.get("online"):
            alerts.append(BridgeAlertV1("error", "meshtastic_offline", "Meshtastic transport is offline"))

        peers_list: list[BridgePeerRouteV1] = []
        for peer in telemetry.get("peers", []):
            if not isinstance(peer, dict):
                continue
            node_id = str(peer.get("node_id", "unknown"))
            route_count = sum(node_id in str(route.get("interface", "")) for route in routes)
            peers_list.append(
                BridgePeerRouteV1(
                    peer=node_id,
                    routes=route_count,
                    source="meshtastic",
                )
            )
        peers = tuple(peers_list)
        status = BridgeStatusV1(
            captured_at=captured_at(),
            running=True,
            implementation="rns-over-meshtastic-linux",
            implementation_version=__version__,
            transport_id=traffic.get("transport_id"),
            uptime_seconds=float(traffic.get("transport_uptime", 0) or 0),
            radio_state="up" if telemetry.get("online") else "unknown/down",
            rns_state="up",
            lxmd_state="up" if lxmd.get("up") else "unknown/down",
            policy_profile=os.environ.get("RNS_LORA_POLICY", "conservative"),
            topology=str(telemetry.get("mesh_mode") or os.environ.get("RNS_MESH_MODE", "unknown")),
            peers=peers,
            alerts=tuple(alerts),
        )
        snapshot = BridgeTrafficSnapshotV1(
            captured_at=status.captured_at,
            lora=_counter(traffic["lora"]),
            lan=_counter(traffic["private_tcp"]),
            public=_counter(traffic["public"]),
        )
        return to_dict(status) | {
            "traffic": to_dict(snapshot),
            "meshtastic": telemetry,
            "public_interfaces": self._observe_upstreams(
                traffic.get("public_interfaces", {})
            ),
            "lxmd": lxmd,
            "discovery": collect_discovery(self.config_dir),
            "routes": routes,
        }

    def _observe_upstreams(self, upstreams: dict[str, Any]) -> dict[str, Any]:
        now = captured_at()
        enhanced: dict[str, Any] = {}
        with self._lock:
            for name, raw in upstreams.items():
                value = dict(raw) if isinstance(raw, dict) else {}
                up = bool(value.get("up"))
                counters = (int(value.get("rx", 0) or 0), int(value.get("tx", 0) or 0))
                observed = self._upstream_observations.get(name)
                if observed is None:
                    observed = {
                        "up": up,
                        "reconnects": 0,
                        "observations": 0,
                        "up_observations": 0,
                        "first_observed_at": now,
                        "last_state_change_at": now,
                        "last_activity_at": None,
                        "counters": counters,
                    }
                    self._upstream_observations[name] = observed
                elif observed["up"] != up:
                    if up:
                        observed["reconnects"] += 1
                    observed["up"] = up
                    observed["last_state_change_at"] = now
                if observed["counters"] != counters:
                    observed["counters"] = counters
                    observed["last_activity_at"] = now
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
                    "latency_ms": None,
                }
        return enhanced

    def validate(self, changes: dict[str, Any]) -> dict[str, Any]:
        unknown = sorted(set(changes) - set(CONFIG_FIELDS))
        if unknown:
            raise ValueError("unsupported configuration fields: " + ", ".join(unknown))
        merged = environment_from_process()
        merged.update({name: str(value) for name, value in changes.items()})
        result = validate_gateway_environment(merged)
        old = render_env(redact_environment(self.active_environment())).splitlines()
        new_values = {name: result.values.get(name, "") for name in CONFIG_FIELDS}
        new = render_env(redact_environment(new_values)).splitlines()
        diff = "\n".join(difflib.unified_diff(old, new, fromfile="active", tofile="staged", lineterm=""))
        return {"valid": True, "warnings": result.warnings, "diff": diff}

    def stage(self, changes: dict[str, Any]) -> dict[str, Any]:
        validation = self.validate(changes)
        merged = environment_from_process()
        merged.update({name: str(value) for name, value in changes.items()})
        target = stage_environment(self.stage_dir, merged)
        return validation | {
            "stage_id": target.name,
            "export_command": (
                "umask 077; docker compose --env-file .env.linux-service "
                "-f compose.linux.yaml run --rm gateway-console "
                f"rns-meshtastic gateway-export --stage-file {target} > .env.pending"
            ),
            "apply_command": (
                "uv run rns-meshtastic gateway-apply --stage-file .env.pending "
                "--target .env.linux-service --compose-file compose.linux.yaml"
            ),
        }


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
        "rns_meshtastic_bridge_up 1",
    ]
    for network in ("lora", "lan", "public", "propagation"):
        counter = traffic[network]
        for direction in ("rx", "tx"):
            lines.append(
                f'rns_meshtastic_traffic_bytes_total{{network="{network}",direction="{direction}"}} '
                f'{int(counter[f"{direction}_bytes"])}'
            )
    queue = status.get("meshtastic", {}).get("queue", {})
    lines.append(f"rns_meshtastic_lora_queue_fragments {int(queue.get('fragments', 0) or 0)}")
    lines.append(f"rns_meshtastic_lora_queue_limit {int(queue.get('limit', 0) or 0)}")
    return "\n".join(lines) + "\n"


INDEX_HTML = (Path(__file__).with_name("static") / "gateway-console.html").read_text(
    encoding="utf-8"
)


class ConsoleHandler(BaseHTTPRequestHandler):
    server_version = "RNSMeshtasticConsole/1"

    @property
    def state(self) -> GatewayState:
        return self.server.gateway_state  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: object) -> None:
        # Paths and status only; request bodies (which may contain staged
        # secrets in future schema versions) are deliberately never logged.
        super().log_message(format, *args)

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'",
        )
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, value: Any) -> None:
        self._send(status, json.dumps(value, sort_keys=True).encode(), "application/json")

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            if path == "/":
                self._send(200, INDEX_HTML.encode(), "text/html; charset=utf-8")
            elif path == "/api/v1/capabilities":
                self._json(200, _capabilities())
            elif path in {"/api/v1/status", "/healthz"}:
                self._json(200, self.state.status())
            elif path == "/api/v1/config":
                self._json(200, {"schema": 1, "values": redact_environment(self.state.active_environment())})
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
        except Exception as error:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(error)})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path not in {"/api/v1/config/validate", "/api/v1/config/stage"}:
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 64 * 1024:
                raise ValueError("request body must be at most 64 KiB")
            value = json.loads(self.rfile.read(length))
            if not isinstance(value, dict):
                raise ValueError("request body must be a JSON object")
            result = self.state.stage(value) if path.endswith("/stage") else self.state.validate(value)
            self._json(200, result)
        except (ValueError, json.JSONDecodeError) as error:
            self._json(400, {"valid": False, "error": str(error)})


def serve(bind: str, port: int, state: GatewayState) -> None:
    server = ThreadingHTTPServer((bind, port), ConsoleHandler)
    server.gateway_state = state  # type: ignore[attr-defined]
    server.serve_forever()
