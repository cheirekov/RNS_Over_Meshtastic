"""Backend contract used by the Reticulum interface."""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Callable

PacketCallback = Callable[[str, str, bytes], None]
StateCallback = Callable[[bool, str | None], None]


class TransportBackend(ABC):
    local_node_id: str | None = None

    @abstractmethod
    def start(self, packet_callback: PacketCallback, state_callback: StateCallback) -> None: ...

    @abstractmethod
    def send(self, payload: bytes, destination: str) -> None: ...

    @abstractmethod
    def close(self) -> None: ...
