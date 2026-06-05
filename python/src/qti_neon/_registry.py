"""Game packet type registry."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from ._protocol import PacketTypeEntry, PacketTypeRegistry


@dataclass(frozen=True)
class GamePacketDescriptor:
    """Metadata for a single registered game packet type."""

    packet_id: int
    """Wire type byte (must be ``>= 0x10``)."""
    name: str
    """Short human-readable name."""
    description: str
    """Longer description for debugging and tooling."""


class GamePacketRegistry:
    """Registry of application-defined game packet types.

    Registered types are advertised to connecting clients via a
    `~qti_neon._protocol.PacketTypeRegistry` packet.

    Example:

    ```python
    registry = GamePacketRegistry()
    registry.register(0x10, "POSITION", "Player position update")
    registry.register(0x11, "CHAT",     "Chat message")

    host.set_game_packet_registry(registry)
    ```
    """

    def __init__(self) -> None:
        self._descriptors: list[GamePacketDescriptor] = []

    def register(
        self,
        packet_id: int,
        name: str,
        description: str = "",
    ) -> None:
        """Register a game packet type.

        Args:
            packet_id: Unique type byte (must be ``>= 0x10``).
            name: Short human-readable name (max 64 characters).
            description: Longer description for debugging (max 256 characters).

        Raises:
            ValueError: if *packet_id* is less than ``0x10``.
        """
        if packet_id < 0x10:
            raise ValueError(f"packet_id must be >= 0x10, got 0x{packet_id:02x}")
        self._descriptors.append(GamePacketDescriptor(packet_id, name, description))

    def build_registry(self) -> PacketTypeRegistry:
        """Build the wire-format `PacketTypeRegistry` payload from all registered types."""
        entries = tuple(
            PacketTypeEntry(d.packet_id, d.name, d.description)
            for d in self._descriptors
        )
        return PacketTypeRegistry(entries)

    @property
    def is_empty(self) -> bool:
        """``True`` when no types have been registered."""
        return not self._descriptors
