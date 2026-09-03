"""Bounded file queue between the unprivileged Console and a host apply agent."""

from __future__ import annotations

import fcntl
import json
import os
import re
import secrets
import tempfile
import time
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

STAGE_ID = re.compile(r"gateway-\d{8}T\d{6}\.\d{6}Z\.env\Z")
REQUEST_ID = re.compile(r"[0-9a-f]{16}\Z")
AGENT_FRESH_SECONDS = 15.0


def _now() -> str:
    return datetime.now(UTC).isoformat()


def _read(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        json.dump(value, handle, separators=(",", ":"), sort_keys=True)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.chmod(0o600)
    temporary.replace(path)


@contextmanager
def _locked(directory: Path) -> Iterator[None]:
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / "queue.lock"
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def validate_stage_id(stage_id: str) -> str:
    value = str(stage_id)
    if STAGE_ID.fullmatch(value) is None:
        raise ValueError("invalid stage_id")
    return value


def agent_status(directory: Path) -> dict[str, Any]:
    heartbeat = _read(directory / "heartbeat.json")
    try:
        age = max(0.0, time.time() - float(heartbeat.get("unix_time", 0)))
    except (TypeError, ValueError):
        age = AGENT_FRESH_SECONDS + 1
    status = _read(directory / "status.json")
    return {
        "available": bool(heartbeat) and age <= AGENT_FRESH_SECONDS,
        "heartbeat_age_seconds": round(age, 1) if heartbeat else None,
        "agent_id": heartbeat.get("agent_id"),
        "state": status.get("state", "idle"),
        "request_id": status.get("request_id"),
        "stage_id": status.get("stage_id"),
        "message": status.get("message"),
        "updated_at": status.get("updated_at"),
        "backup": status.get("backup"),
    }


def heartbeat(directory: Path, agent_id: str) -> None:
    if REQUEST_ID.fullmatch(agent_id) is None:
        raise ValueError("invalid agent_id")
    _write(
        directory / "heartbeat.json",
        {"schema": 1, "agent_id": agent_id, "unix_time": time.time(), "updated_at": _now()},
    )


def queue_request(directory: Path, stage_dir: Path, stage_id: str) -> dict[str, Any]:
    stage_id = validate_stage_id(stage_id)
    if not (stage_dir / stage_id).is_file():
        raise ValueError("staged configuration no longer exists")
    if not agent_status(directory)["available"]:
        raise RuntimeError("host apply agent is offline; run scripts/gateway-apply-agent install")
    with _locked(directory):
        if (directory / "pending.json").exists() or (directory / "active.json").exists():
            raise RuntimeError("another configuration apply is already queued or running")
        request = {
            "schema": 1,
            "request_id": secrets.token_hex(8),
            "stage_id": stage_id,
            "created_at": _now(),
        }
        _write(directory / "pending.json", request)
        _write(directory / "status.json", request | {"state": "queued", "updated_at": _now()})
        return request | {"state": "queued"}


def claim_request(directory: Path, agent_id: str) -> dict[str, Any] | None:
    heartbeat(directory, agent_id)
    with _locked(directory):
        pending = directory / "pending.json"
        if not pending.is_file() or (directory / "active.json").exists():
            return None
        request = _read(pending)
        validate_stage_id(str(request.get("stage_id", "")))
        if REQUEST_ID.fullmatch(str(request.get("request_id", ""))) is None:
            pending.unlink(missing_ok=True)
            raise ValueError("invalid queued request")
        active = request | {"agent_id": agent_id, "started_at": _now()}
        _write(directory / "active.json", active)
        pending.unlink()
        _write(directory / "status.json", active | {"state": "applying", "updated_at": _now()})
        return active


def complete_request(
    directory: Path,
    request_id: str,
    *,
    succeeded: bool,
    message: str,
    backup: str | None = None,
) -> dict[str, Any]:
    if REQUEST_ID.fullmatch(request_id) is None:
        raise ValueError("invalid request_id")
    safe_message = str(message).replace("\n", " ")[:500]
    with _locked(directory):
        active_path = directory / "active.json"
        active = _read(active_path)
        if active.get("request_id") != request_id:
            raise ValueError("request is not active")
        result = active | {
            "state": "succeeded" if succeeded else "failed",
            "message": safe_message,
            "updated_at": _now(),
        }
        if backup:
            result["backup"] = Path(backup).name
        _write(directory / "status.json", result)
        active_path.unlink(missing_ok=True)
        return result
