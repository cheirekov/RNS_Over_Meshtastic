"""Bounded, secret-free structured events shared by Linux sidecars."""

from __future__ import annotations

import fcntl
import json
import os
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

MAX_EVENTS = 512
MAX_BYTES = 512 * 1024
MAX_MESSAGE_LENGTH = 512
SECRET_SUFFIXES = ("PASSWORD", "PASSPHRASE", "SECRET", "TOKEN", "KEY")


def _captured_at() -> str:
    return datetime.now(UTC).isoformat()


def _redact(message: str) -> str:
    clean = " ".join(str(message).replace("\x00", " ").splitlines()).strip()
    for name, value in os.environ.items():
        if value and any(name.upper().endswith(suffix) for suffix in SECRET_SUFFIXES):
            clean = clean.replace(value, "<redacted>")
    return clean[:MAX_MESSAGE_LENGTH]


class EventJournal:
    """Append/read a small JSONL journal with a cross-process lock."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock_path = path.with_suffix(path.suffix + ".lock")

    def append(self, source: str, severity: str, code: str, message: str) -> dict[str, Any]:
        if severity not in {"info", "warning", "error"}:
            raise ValueError("event severity must be info, warning or error")
        event = {
            # Millisecond epoch values remain exact in JavaScript's Number type.
            # Collisions inside one millisecond are resolved against the last id.
            "id": int(time.time() * 1000),
            "captured_at": _captured_at(),
            "source": _redact(source)[:64],
            "severity": severity,
            "code": _redact(code)[:96],
            "message": _redact(message),
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock_path.touch(mode=0o600, exist_ok=True)
        with self.lock_path.open("r+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            events = self._read_unlocked()
            if events and event["id"] <= events[-1].get("id", 0):
                event["id"] = int(events[-1]["id"]) + 1
            events.append(event)
            self._write_bounded_unlocked(events)
            fcntl.flock(lock, fcntl.LOCK_UN)
        return event

    def read(self, *, after: int = 0, limit: int = 100) -> dict[str, Any]:
        limit = max(1, min(int(limit), MAX_EVENTS))
        if not self.path.exists():
            return {"schema": 1, "cursor": after, "events": []}
        self.lock_path.touch(mode=0o600, exist_ok=True)
        with self.lock_path.open("r+") as lock:
            fcntl.flock(lock, fcntl.LOCK_SH)
            events = [event for event in self._read_unlocked() if int(event.get("id", 0)) > after]
            fcntl.flock(lock, fcntl.LOCK_UN)
        selected = events[:limit]
        cursor = int(selected[-1]["id"]) if selected else after
        return {"schema": 1, "cursor": cursor, "events": selected}

    def _read_unlocked(self) -> list[dict[str, Any]]:
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return []
        events: list[dict[str, Any]] = []
        for line in lines[-MAX_EVENTS * 2 :]:
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict) and isinstance(value.get("id"), int):
                events.append(value)
        return events

    def _write_bounded_unlocked(self, events: list[dict[str, Any]]) -> None:
        encoded = [json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n" for event in events]
        encoded = encoded[-MAX_EVENTS:]
        while encoded and sum(len(line.encode()) for line in encoded) > MAX_BYTES:
            encoded.pop(0)
        with tempfile.NamedTemporaryFile("w", dir=self.path.parent, delete=False, encoding="utf-8") as handle:
            handle.writelines(encoded)
            temporary = Path(handle.name)
        temporary.chmod(0o600)
        temporary.replace(self.path)
