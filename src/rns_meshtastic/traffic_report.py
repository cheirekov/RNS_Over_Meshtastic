from __future__ import annotations

import json
import subprocess
import tempfile
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
RADIO_PARENT_TYPE = "RNSMeshtasticInterface"
RADIO_PEER_TYPE = "MeshtasticPeerInterface"
PUBLIC_NAME_PREFIX = "Public boundary "
PRIVATE_TCP_NAME = "LAN VPN Reticulum clients"


@dataclass(frozen=True)
class TrafficCounter:
    rx: int = 0
    tx: int = 0
    rxs: float = 0.0
    txs: float = 0.0

    def add(self, other: TrafficCounter) -> TrafficCounter:
        return TrafficCounter(
            rx=self.rx + other.rx,
            tx=self.tx + other.tx,
            rxs=self.rxs + other.rxs,
            txs=self.txs + other.txs,
        )

    def as_dict(self) -> dict[str, int | float]:
        return {"rx": self.rx, "tx": self.tx, "rxs": self.rxs, "txs": self.txs}


def _counter(interface: Mapping[str, Any]) -> TrafficCounter:
    return TrafficCounter(
        rx=int(interface.get("rxb", 0) or 0),
        tx=int(interface.get("txb", 0) or 0),
        rxs=float(interface.get("rxs", 0) or 0),
        txs=float(interface.get("txs", 0) or 0),
    )


def summarise_rnstatus(stats: Mapping[str, Any]) -> dict[str, Any]:
    interfaces = stats.get("interfaces")
    if not isinstance(interfaces, list):
        raise ValueError("rnstatus JSON does not contain an interfaces list")

    radio_parents = [item for item in interfaces if item.get("type") == RADIO_PARENT_TYPE]
    parent_hashes = {item.get("hash") for item in radio_parents}
    parent_names = {item.get("name") for item in radio_parents}
    radio_peers = [
        item
        for item in interfaces
        if item.get("type") == RADIO_PEER_TYPE
        and (
            item.get("parent_interface_hash") in parent_hashes
            or item.get("parent_interface_name") in parent_names
        )
    ]

    # The physical parent already counts every outbound frame. In multi-peer
    # mode inbound frames are accounted on the dynamic peers instead, while
    # broadcast/fixed-client mode accounts them on the parent. This formula
    # intentionally avoids counting peer TX a second time.
    radio = TrafficCounter()
    for item in radio_parents:
        counter = _counter(item)
        radio = radio.add(TrafficCounter(rx=counter.rx, tx=counter.tx, rxs=counter.rxs, txs=counter.txs))
    for item in radio_peers:
        counter = _counter(item)
        radio = radio.add(TrafficCounter(rx=counter.rx, rxs=counter.rxs))

    public: dict[str, dict[str, int | float | bool]] = {}
    public_total = TrafficCounter()
    private_total = TrafficCounter()
    private_client_count = 0
    for item in interfaces:
        short_name = str(item.get("short_name") or "")
        if short_name.startswith(PUBLIC_NAME_PREFIX):
            counter = _counter(item)
            public_total = public_total.add(counter)
            public[short_name] = counter.as_dict() | {"up": bool(item.get("status"))}
        elif short_name == PRIVATE_TCP_NAME and not item.get("parent_interface_name"):
            private_total = private_total.add(_counter(item))
        elif item.get("parent_interface_name") == PRIVATE_TCP_NAME:
            private_client_count += 1

    return {
        "schema": SCHEMA_VERSION,
        "captured_at": datetime.now(UTC).isoformat(),
        "transport_id": stats.get("transport_id"),
        "transport_uptime": float(stats.get("transport_uptime", 0) or 0),
        "lora": radio.as_dict(),
        "public": public_total.as_dict(),
        "private_tcp": private_total.as_dict(),
        "public_interfaces": public,
        "radio_parent_count": len(radio_parents),
        "radio_peer_count": len(radio_peers),
        "private_tcp_client_count": private_client_count,
    }


def collect_rnstatus(config_dir: Path) -> dict[str, Any]:
    result = subprocess.run(
        ["rnstatus", "--config", str(config_dir), "--all", "--json"],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise RuntimeError(f"rnstatus failed: {detail}")
    for line in reversed(result.stdout.splitlines()):
        try:
            decoded = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(decoded, dict) and "interfaces" in decoded:
            return summarise_rnstatus(decoded)
    raise RuntimeError("rnstatus did not return a JSON status object")


def save_snapshot(path: Path, snapshot: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        json.dump(snapshot, handle, sort_keys=True)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.chmod(0o600)
    temporary.replace(path)


def load_snapshot(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid traffic baseline {path}: {error}") from error
    if not isinstance(value, dict) or value.get("schema") != SCHEMA_VERSION:
        raise ValueError(f"unsupported traffic baseline schema in {path}")
    return value


def _human_bytes(value: int) -> str:
    units = ("B", "KiB", "MiB", "GiB", "TiB")
    amount = float(value)
    unit = units[0]
    for unit in units:
        if abs(amount) < 1024 or unit == units[-1]:
            break
        amount /= 1024
    if unit == "B":
        return f"{int(amount)} B"
    return f"{amount:.2f} {unit}"


def _elapsed_seconds(current: Mapping[str, Any], baseline: Mapping[str, Any]) -> float | None:
    try:
        current_time = datetime.fromisoformat(str(current["captured_at"]))
        baseline_time = datetime.fromisoformat(str(baseline["captured_at"]))
    except (KeyError, ValueError):
        return None
    return max(0.0, (current_time - baseline_time).total_seconds())


def _format_counter(
    label: str,
    current: Mapping[str, Any],
    baseline: Mapping[str, Any] | None,
    elapsed: float | None,
) -> list[str]:
    lines = [f"{label}:"]
    for key, direction in (("rx", "in"), ("tx", "out")):
        total = int(current.get(key, 0) or 0)
        suffix = ""
        if baseline is not None:
            previous = int(baseline.get(key, 0) or 0)
            delta = max(0, total - previous)
            average = (delta * 8 / elapsed) if elapsed and elapsed > 0 else 0.0
            suffix = f"; window +{_human_bytes(delta)} ({average:.1f} bps average)"
        lines.append(f"  {direction}: {_human_bytes(total)} cumulative{suffix}")
    lines.append(
        f"  now: ↓{float(current.get('rxs', 0) or 0):.1f} bps ↑{float(current.get('txs', 0) or 0):.1f} bps"
    )
    return lines


def format_report(current: Mapping[str, Any], baseline: Mapping[str, Any] | None) -> str:
    comparable = bool(
        baseline
        and baseline.get("transport_id") == current.get("transport_id")
        and float(baseline.get("transport_uptime", 0) or 0) <= float(current.get("transport_uptime", 0) or 0)
    )
    elapsed = _elapsed_seconds(current, baseline) if comparable and baseline else None
    lines = [
        "Reticulum public-boundary traffic report",
        f"captured_at: {current['captured_at']}",
        f"transport: {current.get('transport_id') or 'unknown'}; "
        f"uptime {float(current.get('transport_uptime', 0) or 0):.0f}s",
    ]
    if baseline is None:
        lines.append("window: no baseline (cumulative counters only)")
    elif comparable and elapsed is not None:
        lines.append(f"window: {elapsed:.0f}s since {baseline.get('captured_at')}")
    else:
        lines.append("window: baseline belongs to a different/restarted Transport; reset it")

    lines.extend(
        _format_counter(
            "LoRa RNS payload",
            current["lora"],
            baseline.get("lora") if comparable else None,
            elapsed,
        )
    )
    lines.extend(
        _format_counter(
            "Public boundary aggregate",
            current["public"],
            baseline.get("public") if comparable else None,
            elapsed,
        )
    )
    lines.extend(
        _format_counter(
            "Private TCP listener",
            current["private_tcp"],
            baseline.get("private_tcp") if comparable else None,
            elapsed,
        )
    )
    lines.append(
        f"radio topology: {current.get('radio_parent_count', 0)} parent(s), "
        f"{current.get('radio_peer_count', 0)} dynamic peer(s)"
    )
    public_interfaces = current.get("public_interfaces", {})
    if public_interfaces:
        lines.append("public upstreams:")
        for name, counter in sorted(public_interfaces.items()):
            status = "up" if counter.get("up") else "down"
            lines.append(
                f"  {name}: {status}, in {_human_bytes(int(counter.get('rx', 0) or 0))}, "
                f"out {_human_bytes(int(counter.get('tx', 0) or 0))}"
            )
    else:
        lines.append("public upstreams: none visible")
    lines.append(
        "note: LoRa values are Reticulum payload bytes accepted by the interface, "
        "not RF airtime or Meshtastic framing overhead"
    )
    return "\n".join(lines)


def traffic_report(
    config_dir: Path,
    baseline_file: Path,
    *,
    save_baseline_only: bool = False,
) -> str:
    current = collect_rnstatus(config_dir)
    if save_baseline_only:
        save_snapshot(baseline_file, current)
        return (
            f"traffic baseline saved: {baseline_file}\n"
            f"captured_at: {current['captured_at']}\n"
            "Start the test now, then run the traffic report."
        )
    baseline = load_snapshot(baseline_file)
    return format_report(current, baseline)
