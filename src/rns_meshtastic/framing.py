"""Port 76 fragmentation compatible with landandair/RNS_Over_Meshtastic.

The on-air header is two bytes: an unsigned packet index followed by a signed
fragment position. A negative position marks the final fragment. Missing
fragments are requested with ``b"REQ" + header``.
"""

from __future__ import annotations

import hashlib
import struct
import threading
import time
from collections import OrderedDict
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
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if not 1 <= fragment_body <= 230:
            raise ValueError("fragment_body must be between 1 and 230 bytes")
        if state_ttl <= 0 or request_cooldown <= 0:
            raise ValueError("state_ttl and request_cooldown must be positive")
        self.fragment_body = fragment_body
        self.state_ttl = state_ttl
        self.request_cooldown = request_cooldown
        self._clock = clock
        self._next_index = 0
        self._assemblies: dict[tuple[str, int], _Assembly] = {}
        self._tx_cache: OrderedDict[tuple[int, str], _CachedTx] = OrderedDict()
        self._completed: OrderedDict[tuple[str, int, bytes], float] = OrderedDict()
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

    def receive(self, source: str, payload: bytes) -> ReceiveResult:
        if not source:
            raise FragmentError("source must not be empty")
        with self._lock:
            self._cleanup_locked()
            if payload.startswith(REQUEST_PREFIX):
                return self._handle_request_locked(source, payload[len(REQUEST_PREFIX) :])
            return self._handle_fragment_locked(source, payload)

    def cleanup(self) -> None:
        with self._lock:
            self._cleanup_locked()

    def _handle_request_locked(self, source: str, metadata: bytes) -> ReceiveResult:
        index, position = self._parse_header(metadata)
        requested = abs(position)
        cached = self._tx_cache.get((index, source)) or self._tx_cache.get((index, "^all"))
        if cached is None or requested not in cached.fragments:
            return ReceiveResult()
        return ReceiveResult(
            transmissions=[Transmission(source, cached.fragments[requested], reason="retransmit")]
        )

    def _handle_fragment_locked(self, source: str, payload: bytes) -> ReceiveResult:
        index, wire_position = self._parse_header(payload)
        position = abs(wire_position)
        if len(payload) == HEADER.size:
            raise FragmentError("fragment has no body")

        key = (source, index)
        now = self._clock()
        assembly = self._assemblies.setdefault(key, _Assembly(updated_at=now))
        assembly.updated_at = now
        assembly.fragments[position] = payload[HEADER.size :]
        if wire_position < 0:
            assembly.final_position = position

        result = ReceiveResult()
        if assembly.final_position is None:
            return result

        missing = [p for p in range(1, assembly.final_position + 1) if p not in assembly.fragments]
        if missing:
            for missing_position in missing:
                last_request = assembly.requested_at.get(missing_position, 0.0)
                if now - last_request >= self.request_cooldown:
                    request = REQUEST_PREFIX + HEADER.pack(index, missing_position)
                    result.transmissions.append(Transmission(source, request, reason="request"))
                    assembly.requested_at[missing_position] = now
            return result

        frame = b"".join(assembly.fragments[p] for p in range(1, assembly.final_position + 1))
        digest = hashlib.blake2s(frame, digest_size=8).digest()
        complete_key = (source, index, digest)
        self._assemblies.pop(key, None)
        if complete_key not in self._completed:
            result.frames.append(frame)
            self._completed[complete_key] = now
            self._completed.move_to_end(complete_key)
        return result

    @staticmethod
    def _parse_header(payload: bytes) -> tuple[int, int]:
        if len(payload) < HEADER.size:
            raise FragmentError("fragment metadata is truncated")
        index, position = HEADER.unpack_from(payload)
        if position == 0:
            raise FragmentError("fragment position zero is invalid")
        return index, position

    def _cleanup_locked(self) -> None:
        cutoff = self._clock() - self.state_ttl
        for key, assembly in list(self._assemblies.items()):
            if assembly.updated_at < cutoff:
                self._assemblies.pop(key, None)
        for key, cached in list(self._tx_cache.items()):
            if cached.created_at < cutoff:
                self._tx_cache.pop(key, None)
        while self._completed:
            key, completed_at = next(iter(self._completed.items()))
            if completed_at >= cutoff:
                break
            self._completed.pop(key, None)
