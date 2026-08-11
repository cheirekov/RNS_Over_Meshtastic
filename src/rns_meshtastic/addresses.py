"""Meshtastic NodeNum parsing and formatting."""

from __future__ import annotations

import re

BROADCAST_NUM = 0xFFFFFFFF
BROADCAST_ID = "^all"
_NODE_ID_RE = re.compile(r"^![0-9a-fA-F]{8}$")


def parse_node_id(value: str | int) -> int:
    """Return an unsigned 32-bit Meshtastic NodeNum."""
    if isinstance(value, int):
        number = value
    else:
        text = value.strip()
        if text == BROADCAST_ID:
            return BROADCAST_NUM
        if _NODE_ID_RE.fullmatch(text):
            number = int(text[1:], 16)
        else:
            try:
                number = int(text, 0)
            except ValueError as exc:
                raise ValueError(f"invalid Meshtastic node ID {value!r}; expected !aabbcc11") from exc
    if not 0 <= number <= 0xFFFFFFFF:
        raise ValueError(f"Meshtastic NodeNum is outside uint32: {number}")
    return number


def format_node_id(value: str | int) -> str:
    number = parse_node_id(value)
    if number == BROADCAST_NUM:
        return BROADCAST_ID
    return f"!{number:08x}"
