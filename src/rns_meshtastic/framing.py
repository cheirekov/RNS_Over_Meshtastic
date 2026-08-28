"""Port 76 fragmentation compatible with landandair/RNS_Over_Meshtastic.

The on-air header is two bytes: an unsigned packet index followed by a signed
fragment position. A negative position marks the final fragment. Missing
fragments are requested with ``b"REQ" + header``. In the backwards-compatible
repair extension, requested position zero means "retransmit the final
fragment"; position zero remains invalid for data fragments.
"""

from __future__ import annotations

import hashlib
import struct
import threading
import time
from collections import OrderedDict, deque
from collections.abc import Callable
from dataclasses import dataclass, field

HEADER = struct.Struct("!Bb")
REQUEST_PREFIX = b"REQ"
DEFAULT_FRAGMENT_BODY = 200


class FragmentError(ValueError):
    """Raised for malformed or impossible fragment data."""


@dataclass(frozen=True, slots=True)
class Transmission:
    destination: str
    payload: bytes
    reason: str = "data"


@dataclass(slots=True)
class ReceiveResult:
    frames: list[bytes] = field(default_factory=list)
    transmissions: list[Transmission] = field(default_factory=list)


@dataclass(slots=True)
class _Assembly:
    updated_at: float
    fragments: dict[int, bytes] = field(default_factory=dict)
    final_position: int | None = None
    requested_at: dict[int, float] = field(default_factory=dict)
    request_attempts: dict[int, int] = field(default_factory=dict)


@dataclass(slots=True)
class _CachedTx:
    destination: str
    created_at: float
    fragments: dict[int, bytes]


class FragmentProtocol:
    """Thread-safe fragmentation, reassembly, NACK and retransmission cache."""

    def __init__(
        self,
        *,
        fragment_body: int = DEFAULT_FRAGMENT_BODY,
        state_ttl: float = 180.0,
        request_cooldown: float = 5.0,
        max_repair_attempts: int = 3,
        max_repair_requests_per_window: int = 12,
        repair_window: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if not 1 <= fragment_body <= 230:
            raise ValueError("fragment_body must be between 1 and 230 bytes")
        if state_ttl <= 0 or request_cooldown <= 0:
            raise ValueError("state_ttl and request_cooldown must be positive")
        if max_repair_attempts <= 0:
            raise ValueError("max_repair_attempts must be positive")
        if max_repair_requests_per_window <= 0 or repair_window <= 0:
            raise ValueError("repair request budget and window must be positive")
        self.fragment_body = fragment_body
        self.state_ttl = state_ttl
        self.request_cooldown = request_cooldown
        self.max_repair_attempts = max_repair_attempts
        self.max_repair_requests_per_window = max_repair_requests_per_window
        self.repair_window = repair_window
        self._clock = clock
        self._next_index = 0
        self._assemblies: dict[tuple[str, int], _Assembly] = {}
        self._tx_cache: OrderedDict[tuple[int, str], _CachedTx] = OrderedDict()
        self._completed: OrderedDict[tuple[str, int, bytes], float] = OrderedDict()
        self._completed_indices: OrderedDict[tuple[str, int], float] = OrderedDict()
        self._repair_request_times: deque[float] = deque()
        self.repair_requests = 0
        self.repair_throttled = 0
        self.assemblies_expired = 0
        self._lock = threading.RLock()

    def encode(self, frame: bytes, destination: str) -> list[Transmission]:
        if not frame:
            raise FragmentError("cannot encode an empty Reticulum frame")
        chunks = [
            frame[offset : offset + self.fragment_body] for offset in range(0, len(frame), self.fragment_body)
        ]
        if len(chunks) > 127:
            raise FragmentError("frame needs more than 127 legacy fragments")

        with self._lock:
            self._cleanup_locked()
            index = self._next_index
            self._next_index = (self._next_index + 1) & 0xFF
            fragments: dict[int, bytes] = {}
            transmissions: list[Transmission] = []
            for position, chunk in enumerate(chunks, start=1):
                wire_position = -position if position == len(chunks) else position
                payload = HEADER.pack(index, wire_position) + chunk
                fragments[position] = payload
                transmissions.append(Transmission(destination, payload))

            key = (index, destination)
            self._tx_cache[key] = _CachedTx(destination, self._clock(), fragments)
            self._tx_cache.move_to_end(key)
            while len(self._tx_cache) > 512:
                self._tx_cache.popitem(last=False)
            return transmissions

    def receive(self, source: str, payload: bytes, *, allow_control: bool = True) -> ReceiveResult:
        if not source:
            raise FragmentError("source must not be empty")
        with self._lock:
            self._cleanup_locked()
            if payload.startswith(REQUEST_PREFIX):
                if not allow_control:
                    return ReceiveResult()
                return self._handle_request_locked(source, payload[len(REQUEST_PREFIX) :])
            return self._handle_fragment_locked(source, payload, allow_control=allow_control)

    def cleanup(self) -> None:
        with self._lock:
            self._cleanup_locked()

    def telemetry(self) -> dict[str, int]:
        """Return bounded, secret-free protocol state for operator telemetry."""

        with self._lock:
            self._cleanup_locked()
            missing = 0
            awaiting_final = 0
            for assembly in self._assemblies.values():
                if assembly.final_position is None:
                    awaiting_final += 1
                else:
                    missing += sum(
                        1
                        for position in range(1, assembly.final_position + 1)
                        if position not in assembly.fragments
                    )
            return {
                "active_assemblies": len(self._assemblies),
                "awaiting_final": awaiting_final,
                "missing_fragments": missing,
                "cached_transmissions": len(self._tx_cache),
                "repair_requests": self.repair_requests,
                "repair_throttled": self.repair_throttled,
                "repair_budget_used": len(self._repair_request_times),
                "repair_budget_limit": self.max_repair_requests_per_window,
                "assemblies_expired": self.assemblies_expired,
                "capped_repairs": sum(
                    1
                    for assembly in self._assemblies.values()
                    for attempts in assembly.request_attempts.values()
                    if attempts >= self.max_repair_attempts
                ),
            }

    def poll_repairs(self, max_requests: int = 1, *, allow_control: bool = True) -> ReceiveResult:
        """Request stalled fragments with bounded exponential backoff."""
        if max_requests <= 0:
            return ReceiveResult()
        with self._lock:
            self._cleanup_locked()
            now = self._clock()
            result = ReceiveResult()
            for (source, index), assembly in self._assemblies.items():
                if now - assembly.updated_at < self.request_cooldown:
                    continue
                if assembly.final_position is None:
                    missing = [0]
                else:
                    missing = [
                        position
                        for position in range(1, assembly.final_position + 1)
                        if position not in assembly.fragments
                    ]
                remaining = max_requests - len(result.transmissions)
                if remaining <= 0:
                    break
                if not allow_control:
                    continue
                self._append_repair_requests_locked(
                    result, source, index, assembly, missing, now, remaining, False
                )
            return result

    def _handle_request_locked(self, source: str, metadata: bytes) -> ReceiveResult:
        if len(metadata) < HEADER.size:
            raise FragmentError("fragment metadata is truncated")
        index, position = HEADER.unpack_from(metadata)
        cached = self._tx_cache.get((index, source)) or self._tx_cache.get((index, "^all"))
        if cached is None:
            return ReceiveResult()
        requested = max(cached.fragments) if position == 0 else abs(position)
        if requested not in cached.fragments:
            return ReceiveResult()
        return ReceiveResult(
            transmissions=[Transmission(source, cached.fragments[requested], reason="retransmit")]
        )

    def _handle_fragment_locked(self, source: str, payload: bytes, *, allow_control: bool) -> ReceiveResult:
        index, wire_position = self._parse_header(payload)
        position = abs(wire_position)
        if len(payload) == HEADER.size:
            raise FragmentError("fragment has no body")

        key = (source, index)
        now = self._clock()
        if key in self._completed_indices:
            return ReceiveResult()
        assembly = self._assemblies.setdefault(key, _Assembly(updated_at=now))
        body = payload[HEADER.size :]
        made_progress = assembly.fragments.get(position) != body
        if wire_position < 0 and assembly.final_position != position:
            made_progress = True
        if made_progress:
            assembly.updated_at = now
            assembly.requested_at.pop(position, None)
            assembly.request_attempts.pop(position, None)
        assembly.fragments[position] = body
        if wire_position < 0:
            assembly.final_position = position
            assembly.requested_at.pop(0, None)
            assembly.request_attempts.pop(0, None)

        result = ReceiveResult()
        if assembly.final_position is None:
            return result

        missing = [p for p in range(1, assembly.final_position + 1) if p not in assembly.fragments]
        if missing:
            if allow_control:
                self._append_repair_requests_locked(result, source, index, assembly, missing, now, 1, True)
            return result

        frame = b"".join(assembly.fragments[p] for p in range(1, assembly.final_position + 1))
        digest = hashlib.blake2s(frame, digest_size=8).digest()
        complete_key = (source, index, digest)
        self._assemblies.pop(key, None)
        if complete_key not in self._completed:
            result.frames.append(frame)
            self._completed[complete_key] = now
            self._completed.move_to_end(complete_key)
            self._completed_indices[key] = now
            self._completed_indices.move_to_end(key)
        return result

    def _append_repair_requests_locked(
        self,
        result: ReceiveResult,
        source: str,
        index: int,
        assembly: _Assembly,
        missing: list[int],
        now: float,
        budget: int,
        immediate: bool,
    ) -> None:
        for missing_position in missing:
            if budget <= 0:
                break
            attempts = assembly.request_attempts.get(missing_position, 0)
            if attempts >= self.max_repair_attempts:
                continue
            last_request = assembly.requested_at.get(missing_position, 0.0)
            delay = self.request_cooldown * (2**attempts)
            if attempts == 0:
                due = immediate or now - assembly.updated_at >= self.request_cooldown
            else:
                due = now - last_request >= delay
            if not due:
                continue
            self._cleanup_repair_budget_locked(now)
            if len(self._repair_request_times) >= self.max_repair_requests_per_window:
                self.repair_throttled += 1
                break
            request = REQUEST_PREFIX + HEADER.pack(index, missing_position)
            result.transmissions.append(Transmission(source, request, reason="request"))
            self._repair_request_times.append(now)
            self.repair_requests += 1
            assembly.requested_at[missing_position] = now
            assembly.request_attempts[missing_position] = attempts + 1
            budget -= 1

    @staticmethod
    def _parse_header(payload: bytes) -> tuple[int, int]:
        if len(payload) < HEADER.size:
            raise FragmentError("fragment metadata is truncated")
        index, position = HEADER.unpack_from(payload)
        if position == 0:
            raise FragmentError("fragment position zero is invalid")
        return index, position

    def _cleanup_locked(self) -> None:
        now = self._clock()
        cutoff = now - self.state_ttl
        self._cleanup_repair_budget_locked(now)
        for key, assembly in list(self._assemblies.items()):
            if assembly.updated_at < cutoff:
                self._assemblies.pop(key, None)
                self.assemblies_expired += 1
        for key, cached in list(self._tx_cache.items()):
            if cached.created_at < cutoff:
                self._tx_cache.pop(key, None)
        while self._completed:
            key, completed_at = next(iter(self._completed.items()))
            if completed_at >= cutoff:
                break
            self._completed.pop(key, None)
        while self._completed_indices:
            key, completed_at = next(iter(self._completed_indices.items()))
            if completed_at >= cutoff:
                break
            self._completed_indices.pop(key, None)

    def _cleanup_repair_budget_locked(self, now: float) -> None:
        cutoff = now - self.repair_window
        while self._repair_request_times and self._repair_request_times[0] <= cutoff:
            self._repair_request_times.popleft()
