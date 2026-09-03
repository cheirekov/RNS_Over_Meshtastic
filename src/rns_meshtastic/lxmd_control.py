"""Narrow local control plane for the managed LXMD propagation process."""

from __future__ import annotations

import json
import socket
import socketserver
import tempfile
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from rns_meshtastic.event_journal import EventJournal

DEFAULT_SOCKET = Path("/data/rns/lxmd-control.sock")
DEFAULT_STATE = Path("/data/lxmd/manual-announce-state.json")
DEFAULT_EVENTS = Path("/data/rns/gateway-events.jsonl")
MAX_REQUEST_BYTES = 4096


def _hex(value: Any) -> str | None:
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, str):
        return value.lower().removeprefix("<").removesuffix(">")
    return None


def _atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        json.dump(value, handle, sort_keys=True)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.chmod(0o600)
    temporary.replace(path)


class LXMDController:
    def __init__(
        self,
        router_provider: Callable[[], Any],
        *,
        cooldown_seconds: int,
        state_file: Path,
        events: EventJournal,
        utility_module: Any | None = None,
    ) -> None:
        if cooldown_seconds < 300:
            raise ValueError("manual LXMD announce cooldown must be at least 300 seconds")
        self.router_provider = router_provider
        self.cooldown_seconds = cooldown_seconds
        self.state_file = state_file
        self.events = events
        self.utility_module = utility_module
        self._lock = threading.Lock()
        self._last_traffic_sample: tuple[float, int, int] | None = None

    def handle(self, request: dict[str, Any]) -> dict[str, Any]:
        command = request.get("command")
        if command == "status":
            return self.status()
        if command == "announce":
            return self.announce()
        raise ValueError("unsupported LXMD control command")

    def status(self) -> dict[str, Any]:
        router = self.router_provider()
        if router is None or not getattr(router, "propagation_node", False):
            return {"schema": 1, "up": False, "summary": "LXMD is starting"}
        stats = router.compile_stats()
        if not isinstance(stats, dict):
            return {"schema": 1, "up": False, "summary": "LXMD statistics unavailable"}
        peers = stats.get("peers") if isinstance(stats.get("peers"), dict) else {}
        peer_values = [value for value in peers.values() if isinstance(value, dict)]
        rx_bytes = int(stats.get("unpeered_propagation_rx_bytes", 0) or 0) + sum(
            int(peer.get("rx_bytes", 0) or 0) for peer in peer_values
        )
        tx_bytes = sum(int(peer.get("tx_bytes", 0) or 0) for peer in peer_values)
        sampled_at = time.monotonic()
        rx_bps = tx_bps = 0.0
        if self._last_traffic_sample is not None:
            previous_at, previous_rx, previous_tx = self._last_traffic_sample
            elapsed = sampled_at - previous_at
            if elapsed > 0:
                rx_bps = max(0.0, (rx_bytes - previous_rx) * 8.0 / elapsed)
                tx_bps = max(0.0, (tx_bytes - previous_tx) * 8.0 / elapsed)
        self._last_traffic_sample = (sampled_at, rx_bytes, tx_bytes)
        store = stats.get("messagestore") if isinstance(stats.get("messagestore"), dict) else {}
        store_bytes = int(store.get("bytes", 0) or 0)
        store_limit = int(store.get("limit", 0) or 0)
        clients = stats.get("clients") if isinstance(stats.get("clients"), dict) else {}
        peer_details = []
        for peer_hash, raw in peers.items():
            if not isinstance(raw, dict):
                continue
            messages = raw.get("messages") if isinstance(raw.get("messages"), dict) else {}
            peer_details.append(
                {
                    "identity_hash": _hex(peer_hash),
                    "name": str(raw.get("name") or "unnamed peer")[:128],
                    "type": str(raw.get("type") or "unknown"),
                    "alive": bool(raw.get("alive")),
                    "last_heard_at": int(raw.get("last_heard", 0) or 0) or None,
                    "network_distance": raw.get("network_distance"),
                    "rx_bytes": int(raw.get("rx_bytes", 0) or 0),
                    "tx_bytes": int(raw.get("tx_bytes", 0) or 0),
                    "acceptance_rate": raw.get("acceptance_rate"),
                    "messages": {
                        key: int(messages.get(key, 0) or 0)
                        for key in ("offered", "outgoing", "incoming", "unhandled")
                    },
                }
            )
        peer_details.sort(key=lambda value: (not value["alive"], value["name"], value["identity_hash"] or ""))
        last_manual = self._last_manual_announce()
        return {
            "schema": 1,
            "up": True,
            "destination_hash": _hex(stats.get("destination_hash")),
            "identity_hash": _hex(stats.get("identity_hash")),
            "uptime_seconds": float(stats.get("uptime", 0) or 0),
            "message_count": int(store.get("count", 0) or 0),
            "store_bytes": store_bytes,
            "store_limit_bytes": store_limit,
            "store_utilisation_percent": (
                round(100.0 * store_bytes / store_limit, 1) if store_limit else None
            ),
            "peer_count": int(stats.get("total_peers", 0) or 0),
            "peering": {
                "total": int(stats.get("total_peers", 0) or 0),
                "active": sum(1 for peer in peer_details if peer["alive"]),
                "static": int(stats.get("static_peers", 0) or 0),
                "discovered": int(stats.get("discovered_peers", 0) or 0),
                "maximum": stats.get("max_peers"),
                "peers": peer_details,
            },
            "client_activity": {
                "messages_received": int(
                    clients.get("client_propagation_messages_received", 0) or 0
                ),
                "messages_served": int(
                    clients.get("client_propagation_messages_served", 0) or 0
                ),
                "unique_clients_available": False,
                "source": "lxmd.compile_stats.clients",
            },
            "traffic": {
                "rx_bytes": rx_bytes,
                "tx_bytes": tx_bytes,
                "rx_bps": round(rx_bps, 3),
                "tx_bps": round(tx_bps, 3),
                "available": True,
                "source": "lxmd.compile_stats.peer_bytes",
            },
            "last_manual_announce_at": last_manual or None,
            "next_manual_announce_at": (last_manual + self.cooldown_seconds if last_manual else None),
            "manual_announce_cooldown_seconds": self.cooldown_seconds,
        }

    def announce(self) -> dict[str, Any]:
        with self._lock:
            router = self.router_provider()
            if router is None or not getattr(router, "propagation_node", False):
                raise RuntimeError("LXMD propagation node is not ready")
            now = time.time()
            last = self._last_manual_announce()
            next_allowed = last + self.cooldown_seconds
            if last and now < next_allowed:
                raise LXMDAnnounceRateLimited(next_allowed)
            router.announce_propagation_node()
            if self.utility_module is not None:
                self.utility_module.last_node_announce = now
            _atomic_json(self.state_file, {"last_manual_announce_at": now})
            self.events.append(
                "lxmd", "info", "lxmd_announce_scheduled", "Propagation-node announce scheduled"
            )
            return {
                "schema": 1,
                "announced_at": now,
                "next_allowed_at": now + self.cooldown_seconds,
            }

    def _last_manual_announce(self) -> float:
        try:
            value = json.loads(self.state_file.read_text(encoding="utf-8"))
            return float(value.get("last_manual_announce_at", 0) or 0)
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return 0.0


class LXMDAnnounceRateLimited(RuntimeError):
    def __init__(self, next_allowed_at: float) -> None:
        super().__init__("manual LXMD announce is in cooldown")
        self.next_allowed_at = next_allowed_at


class _ControlHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        try:
            raw = self.rfile.readline(MAX_REQUEST_BYTES + 1)
            if len(raw) > MAX_REQUEST_BYTES:
                raise ValueError("LXMD control request is too large")
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError("LXMD control request must be a JSON object")
            response = self.server.controller.handle(value)  # type: ignore[attr-defined]
            status = "ok"
        except LXMDAnnounceRateLimited as error:
            status = "rate_limited"
            response = {"error": str(error), "next_allowed_at": error.next_allowed_at}
        except (ValueError, RuntimeError, json.JSONDecodeError) as error:
            status = "error"
            response = {"error": str(error)}
        self.wfile.write(json.dumps({"status": status, **response}, sort_keys=True).encode() + b"\n")


class _ControlServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


def request(socket_path: Path, command: str, *, timeout: float = 3.0) -> dict[str, Any]:
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(timeout)
        client.connect(str(socket_path))
        client.sendall(json.dumps({"command": command}).encode() + b"\n")
        response = b""
        while not response.endswith(b"\n") and len(response) <= 64 * 1024:
            chunk = client.recv(4096)
            if not chunk:
                break
            response += chunk
    value = json.loads(response)
    if not isinstance(value, dict):
        raise RuntimeError("invalid LXMD control response")
    return value


def run_managed_lxmd(
    *,
    config_dir: Path,
    rns_config_dir: Path,
    socket_path: Path = DEFAULT_SOCKET,
    state_file: Path = DEFAULT_STATE,
    event_file: Path = DEFAULT_EVENTS,
    cooldown_seconds: int = 900,
    verbosity: int = 1,
) -> None:
    import LXMF.Utilities.lxmd as utility

    socket_path.parent.mkdir(parents=True, exist_ok=True)
    socket_path.unlink(missing_ok=True)
    controller = LXMDController(
        lambda: getattr(utility, "message_router", None),
        cooldown_seconds=cooldown_seconds,
        state_file=state_file,
        events=EventJournal(event_file),
        utility_module=utility,
    )
    server = _ControlServer(str(socket_path), _ControlHandler)
    server.controller = controller  # type: ignore[attr-defined]
    socket_path.chmod(0o600)
    thread = threading.Thread(target=server.serve_forever, name="lxmd-control", daemon=True)
    thread.start()
    try:
        utility.program_setup(
            configdir=str(config_dir),
            rnsconfigdir=str(rns_config_dir),
            run_pn=True,
            verbosity=verbosity,
            service=True,
        )
    finally:
        server.shutdown()
        server.server_close()
        socket_path.unlink(missing_ok=True)
