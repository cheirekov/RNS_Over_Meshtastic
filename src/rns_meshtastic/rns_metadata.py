"""Content-free Reticulum frame metadata for bridge observability.

This parser deliberately stops at the fixed RNS header. It never exposes or
decodes application payloads, identities or announce app-data.
"""

from __future__ import annotations

from dataclasses import dataclass

TRUNCATED_HASH_BYTES = 16
ANNOUNCE_PACKET_TYPE = 1


@dataclass(frozen=True, slots=True)
class RnsFrameMetadata:
    packet_type: int
    destination_type: int
    destination_hash: str
    context: int

    @property
    def is_announce(self) -> bool:
        return self.packet_type == ANNOUNCE_PACKET_TYPE


def is_opaque_ifac(frame: bytes) -> bool:
    return bool(frame) and bool(frame[0] & 0x80)


def parse_rns_frame(frame: bytes) -> RnsFrameMetadata | None:
    value = bytes(frame)
    if len(value) < 19 or is_opaque_ifac(value):
        return None
    flags = value[0]
    header_type = (flags & 0x40) >> 6
    destination_offset = 2 if header_type == 0 else 2 + TRUNCATED_HASH_BYTES
    context_offset = destination_offset + TRUNCATED_HASH_BYTES
    if len(value) <= context_offset:
        return None
    return RnsFrameMetadata(
        packet_type=flags & 0x03,
        destination_type=(flags & 0x0C) >> 2,
        destination_hash=value[destination_offset:context_offset].hex(),
        context=value[context_offset],
    )
