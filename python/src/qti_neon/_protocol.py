"""Wire-format types for the Neon protocol.

All multi-byte fields are **little-endian**. The 8-byte header is followed by
a variable-length payload whose format depends on the packet type.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from enum import IntEnum
from typing import ClassVar

MAGIC: int = 0x4E45
"""Wire magic bytes ``0x4E 0x45`` ("NE")."""

VERSION: int = 0x01
"""Current protocol version byte."""

HEADER_SIZE: int = 8
"""Fixed byte length of every packet header."""

_MAX_NAME_LENGTH: int = 64
_MAX_DESCRIPTION_LENGTH: int = 256
_MAX_PACKET_COUNT: int = 100

# Header struct: magic(H) version(B) type(B) sequence(H) clientId(B) destId(B)
_HEADER_STRUCT = struct.Struct("<HBBHBB")


def _sanitize(s: str) -> str:
    return "".join(c for c in s if 32 <= ord(c) != 127).strip()


def _signed16(n: int) -> int:
    """Interpret *n* (0–65535) as a signed 16-bit integer."""
    n &= 0xFFFF
    return n - 0x10000 if n >= 0x8000 else n


# ---------------------------------------------------------------------------
# PacketType
# ---------------------------------------------------------------------------

class PacketType(IntEnum):
    """Protocol packet types.

    Values ``0x10`` and above are treated as `GAME_PACKET` and are
    application-defined.
    """

    CONNECT_REQUEST = 0x01
    """Client requesting to join a session."""
    CONNECT_ACCEPT = 0x02
    """Relay or host accepting a connection request."""
    CONNECT_DENY = 0x03
    """Relay or host denying a connection request."""
    SESSION_CONFIG = 0x04
    """Host delivering tick rate and max packet size. Delivered reliably."""
    PACKET_TYPE_REGISTRY = 0x05
    """Host advertising registered game packet types."""
    HOST_REGISTER = 0x06
    """Host registering a new session with the relay."""
    PING = 0x0B
    """Keepalive request."""
    PONG = 0x0C
    """Response to a `PING`."""
    DISCONNECT_NOTICE = 0x0D
    """Notification that a peer has disconnected."""
    ACK = 0x0E
    """Acknowledgement of reliably-delivered packets."""
    RECONNECT_REQUEST = 0x0F
    """Client requesting to rejoin a session with a previously issued token."""
    GAME_PACKET = 0x10
    """Application-defined game packet; any type byte ``>= 0x10`` maps here."""

    @classmethod
    def from_byte(cls, b: int) -> "PacketType":
        """Return the `PacketType` for wire byte *b*.

        Any value ``>= 0x10`` returns `GAME_PACKET`.

        Raises:
            ValueError: for reserved values with no mapping.
        """
        if b >= 0x10:
            return cls.GAME_PACKET
        try:
            return cls(b)
        except ValueError:
            raise ValueError(f"Unknown packet type: 0x{b:02x}")

    def is_core_packet(self) -> bool:
        """Return ``True`` for protocol-defined types, ``False`` for `GAME_PACKET`."""
        return self is not PacketType.GAME_PACKET


# ---------------------------------------------------------------------------
# PacketHeader
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class PacketHeader:
    """Immutable 8-byte little-endian packet header.

    Layout: ``magic(2) version(1) packet_type(1) sequence(2) client_id(1) destination_id(1)``.

    The ``magic`` field must equal ``0x4E45`` ("NE"); packets with any other
    value are rejected during deserialisation.
    """

    magic: int
    """Wire magic; must be ``0x4E45``."""
    version: int
    """Protocol version; currently ``0x01``."""
    packet_type: int
    """Packet type byte."""
    sequence: int
    """Unsigned 16-bit sequence number (0–65535, wraps 65535 → 0)."""
    client_id: int
    """Sender client ID (0–255)."""
    destination_id: int
    """Destination client ID (0 = broadcast, 1 = host, 2–254 = client)."""

    MAGIC: ClassVar[int] = MAGIC
    VERSION: ClassVar[int] = VERSION
    SIZE: ClassVar[int] = HEADER_SIZE

    def __post_init__(self) -> None:
        if self.magic != MAGIC:
            raise ValueError(f"Invalid magic: 0x{self.magic:04x}")

    @classmethod
    def create(
        cls,
        packet_type: int,
        sequence: int,
        client_id: int,
        destination_id: int,
    ) -> "PacketHeader":
        """Create a header with the canonical magic and current version."""
        return cls(MAGIC, VERSION, packet_type, sequence & 0xFFFF, client_id & 0xFF, destination_id & 0xFF)

    def to_bytes(self) -> bytes:
        """Serialise to 8 bytes, little-endian."""
        return _HEADER_STRUCT.pack(
            self.magic,
            self.version,
            self.packet_type,
            self.sequence,
            self.client_id,
            self.destination_id,
        )

    @classmethod
    def from_bytes(cls, data: bytes | bytearray) -> "PacketHeader":
        """Parse from the first 8 bytes of *data*.

        Raises:
            ValueError: if *data* is too short or the magic is invalid.
        """
        if len(data) < HEADER_SIZE:
            raise ValueError(f"Buffer too short for header: {len(data)}")
        magic, version, packet_type, sequence, client_id, dest_id = _HEADER_STRUCT.unpack_from(data)
        return cls(magic, version, packet_type, sequence, client_id, dest_id)


# ---------------------------------------------------------------------------
# Payload types
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ConnectRequest:
    """Payload for `PacketType.CONNECT_REQUEST`.

    Sent by a client to request joining a session.
    """

    client_version: int
    """Protocol version the client is running."""
    name: str
    """Player display name (1–64 printable ASCII characters)."""
    session_id: int
    """Session to join."""
    game_id: int
    """Application-defined game identifier."""

    def to_bytes(self) -> bytes:
        name_bytes = self.name.encode("utf-8")
        buf = struct.pack(
            f"<BH{len(name_bytes)}sii",
            self.client_version,
            len(name_bytes),
            name_bytes,
            self.session_id,
            self.game_id,
        )
        return buf

    @classmethod
    def from_bytes(cls, data: bytes) -> "ConnectRequest":
        if len(data) < 3:
            raise ValueError("ConnectRequest too short")
        version, name_len = struct.unpack_from("<BH", data)
        if name_len < 1 or name_len > _MAX_NAME_LENGTH:
            raise ValueError(f"Invalid name length: {name_len}")
        offset = 3
        if len(data) < offset + name_len + 8:
            raise ValueError("ConnectRequest truncated")
        name = data[offset : offset + name_len].decode("utf-8")
        session_id, game_id = struct.unpack_from("<ii", data, offset + name_len)
        return cls(version, name, session_id, game_id)


@dataclass(frozen=True)
class ConnectAccept:
    """Payload for `PacketType.CONNECT_ACCEPT`.

    Sent by the host to confirm a connection and assign a client ID and token.
    Also sent by the relay to the host on successful `HOST_REGISTER`
    (``client_id == 1``).
    """

    client_id: int
    """Assigned client ID (1 = host acknowledged, 2–254 = player)."""
    session_id: int
    """Session this acceptance belongs to."""
    token: int
    """64-bit session token used for reconnect."""

    _STRUCT: ClassVar[struct.Struct] = struct.Struct("<BiQ")

    def to_bytes(self) -> bytes:
        return self._STRUCT.pack(self.client_id, self.session_id, self.token & 0xFFFFFFFFFFFFFFFF)

    @classmethod
    def from_bytes(cls, data: bytes) -> "ConnectAccept":
        if len(data) < 13:
            raise ValueError(f"ConnectAccept too short: {len(data)}")
        client_id, session_id, token = cls._STRUCT.unpack_from(data)
        return cls(client_id, session_id, token)


@dataclass(frozen=True)
class ConnectDeny:
    """Payload for `PacketType.CONNECT_DENY`.

    Sent when a connection or reconnect request is rejected.
    """

    reason: str
    """Human-readable denial reason."""

    def to_bytes(self) -> bytes:
        reason_bytes = self.reason.encode("utf-8")
        return struct.pack(f"<H{len(reason_bytes)}s", len(reason_bytes), reason_bytes)

    @classmethod
    def from_bytes(cls, data: bytes) -> "ConnectDeny":
        if len(data) < 2:
            raise ValueError("ConnectDeny too short")
        (reason_len,) = struct.unpack_from("<H", data)
        if reason_len < 1 or reason_len > _MAX_DESCRIPTION_LENGTH:
            raise ValueError(f"Invalid reason length: {reason_len}")
        if len(data) < 2 + reason_len:
            raise ValueError("ConnectDeny truncated")
        reason = data[2 : 2 + reason_len].decode("utf-8")
        return cls(reason)


@dataclass(frozen=True)
class SessionConfig:
    """Payload for `PacketType.SESSION_CONFIG`.

    Sent reliably by the host to each connecting client.
    """

    version: int
    """Protocol version the host is running."""
    tick_rate: int
    """Advertised game tick rate in ticks per second."""
    max_packet_size: int
    """Advertised maximum game packet payload size in bytes."""

    _STRUCT: ClassVar[struct.Struct] = struct.Struct("<Bhh")

    def to_bytes(self) -> bytes:
        return self._STRUCT.pack(self.version, self.tick_rate, self.max_packet_size)

    @classmethod
    def from_bytes(cls, data: bytes) -> "SessionConfig":
        if len(data) < 5:
            raise ValueError(f"SessionConfig too short: {len(data)}")
        version, tick_rate, max_packet_size = cls._STRUCT.unpack_from(data)
        return cls(version, tick_rate, max_packet_size)


@dataclass(frozen=True)
class PacketTypeEntry:
    """A single entry in a `PacketTypeRegistry`."""

    packet_id: int
    """Application-defined type byte (``>= 0x10``)."""
    name: str
    """Short human-readable name."""
    description: str
    """Longer description for debugging and tooling."""

    def to_bytes(self) -> bytes:
        name_bytes = self.name.encode("utf-8")
        desc_bytes = self.description.encode("utf-8")
        return struct.pack(
            f"<BB{len(name_bytes)}sB{len(desc_bytes)}s",
            self.packet_id,
            len(name_bytes),
            name_bytes,
            len(desc_bytes),
            desc_bytes,
        )

    @classmethod
    def _from_buffer(cls, data: bytes, offset: int) -> tuple["PacketTypeEntry", int]:
        if len(data) - offset < 3:
            raise ValueError("PacketTypeEntry too short")
        packet_id = data[offset]
        name_len = data[offset + 1]
        if name_len > _MAX_NAME_LENGTH:
            raise ValueError(f"PacketTypeEntry name too long: {name_len}")
        offset += 2
        if len(data) - offset < name_len:
            raise ValueError("PacketTypeEntry name truncated")
        name = data[offset : offset + name_len].decode("utf-8")
        offset += name_len
        if len(data) - offset < 1:
            raise ValueError("PacketTypeEntry truncated at descLen")
        desc_len = data[offset]
        offset += 1
        if len(data) - offset < desc_len:
            raise ValueError("PacketTypeEntry description truncated")
        desc = data[offset : offset + desc_len].decode("utf-8")
        return cls(packet_id, name, desc), offset + desc_len


@dataclass(frozen=True)
class PacketTypeRegistry:
    """Payload for `PacketType.PACKET_TYPE_REGISTRY`.

    Sent by the host to advertise application-defined packet types.
    """

    entries: tuple[PacketTypeEntry, ...]
    """Registered packet type entries."""

    def to_bytes(self) -> bytes:
        encoded = [e.to_bytes() for e in self.entries]
        header = struct.pack("<H", len(self.entries))
        return header + b"".join(encoded)

    @classmethod
    def from_bytes(cls, data: bytes) -> "PacketTypeRegistry":
        if len(data) < 2:
            raise ValueError("PacketTypeRegistry too short")
        (count,) = struct.unpack_from("<H", data)
        if count > _MAX_PACKET_COUNT:
            raise ValueError(f"Too many packet type entries: {count}")
        entries: list[PacketTypeEntry] = []
        offset = 2
        for _ in range(count):
            entry, offset = PacketTypeEntry._from_buffer(data, offset)
            entries.append(entry)
        return cls(tuple(entries))


@dataclass(frozen=True)
class HostRegister:
    """Payload for `PacketType.HOST_REGISTER`.

    Sent by the host to register a new session with the relay.
    """

    session_id: int
    """Unique session identifier."""
    host_token: int
    """64-bit host authentication token."""

    _STRUCT: ClassVar[struct.Struct] = struct.Struct("<iQ")

    def to_bytes(self) -> bytes:
        return self._STRUCT.pack(self.session_id, self.host_token & 0xFFFFFFFFFFFFFFFF)

    @classmethod
    def from_bytes(cls, data: bytes) -> "HostRegister":
        if len(data) < 12:
            raise ValueError(f"HostRegister too short: {len(data)}")
        session_id, host_token = cls._STRUCT.unpack_from(data)
        return cls(session_id, host_token)


@dataclass(frozen=True)
class Ping:
    """Payload for `PacketType.PING`.

    Carries the sender's timestamp so the receiver can echo it back.
    """

    timestamp: int
    """Sender wall-clock timestamp in milliseconds."""

    _STRUCT: ClassVar[struct.Struct] = struct.Struct("<Q")

    def to_bytes(self) -> bytes:
        return self._STRUCT.pack(self.timestamp & 0xFFFFFFFFFFFFFFFF)

    @classmethod
    def from_bytes(cls, data: bytes) -> "Ping":
        if len(data) < 8:
            raise ValueError("Ping too short")
        (ts,) = cls._STRUCT.unpack_from(data)
        return cls(ts)


@dataclass(frozen=True)
class Pong:
    """Payload for `PacketType.PONG`.

    Echoes the `Ping.timestamp` so the sender can compute round-trip time.
    """

    original_timestamp: int
    """The `Ping.timestamp` being echoed."""

    _STRUCT: ClassVar[struct.Struct] = struct.Struct("<Q")

    def to_bytes(self) -> bytes:
        return self._STRUCT.pack(self.original_timestamp & 0xFFFFFFFFFFFFFFFF)

    @classmethod
    def from_bytes(cls, data: bytes) -> "Pong":
        if len(data) < 8:
            raise ValueError("Pong too short")
        (ts,) = cls._STRUCT.unpack_from(data)
        return cls(ts)


@dataclass(frozen=True)
class DisconnectNotice:
    """Payload for `PacketType.DISCONNECT_NOTICE`.

    Empty payload; the sender is identified by the header ``client_id``.
    """

    def to_bytes(self) -> bytes:
        return b""

    @classmethod
    def from_bytes(cls, data: bytes) -> "DisconnectNotice":
        return cls()


@dataclass(frozen=True)
class Ack:
    """Payload for `PacketType.ACK`.

    Acknowledges one or more reliably-delivered packets by sequence number.
    """

    sequences: tuple[int, ...]
    """Sequence numbers being acknowledged."""

    def to_bytes(self) -> bytes:
        return struct.pack(f"<H{len(self.sequences)}H", len(self.sequences), *self.sequences)

    @classmethod
    def from_bytes(cls, data: bytes) -> "Ack":
        if len(data) < 2:
            raise ValueError("Ack too short")
        (count,) = struct.unpack_from("<H", data)
        if count > _MAX_PACKET_COUNT:
            raise ValueError(f"Too many ack sequences: {count}")
        if len(data) < 2 + count * 2:
            raise ValueError("Ack truncated")
        seqs = struct.unpack_from(f"<{count}H", data, 2)
        return cls(tuple(seqs))


@dataclass(frozen=True)
class ReconnectRequest:
    """Payload for `PacketType.RECONNECT_REQUEST`.

    Sent by a client to rejoin a session using a previously issued token.
    """

    token: int
    """Session token issued by the host during the original connection."""
    session_id: int
    """Session to rejoin."""
    previous_client_id: int
    """Client ID assigned during the original connection."""

    _STRUCT: ClassVar[struct.Struct] = struct.Struct("<QiB")

    def to_bytes(self) -> bytes:
        return self._STRUCT.pack(self.token & 0xFFFFFFFFFFFFFFFF, self.session_id, self.previous_client_id)

    @classmethod
    def from_bytes(cls, data: bytes) -> "ReconnectRequest":
        if len(data) < 13:
            raise ValueError(f"ReconnectRequest too short: {len(data)}")
        token, session_id, prev_id = cls._STRUCT.unpack_from(data)
        return cls(token, session_id, prev_id)


@dataclass(frozen=True)
class GamePacket:
    """Payload for any `PacketType.GAME_PACKET` (type byte ``>= 0x10``).

    The payload is opaque raw bytes; interpretation is entirely
    application-defined.
    """

    payload: bytes
    """Raw application payload bytes."""

    def to_bytes(self) -> bytes:
        return self.payload

    @classmethod
    def from_bytes(cls, data: bytes) -> "GamePacket":
        return cls(bytes(data))


# Union of all payload types for type annotations.
AnyPayload = (
    ConnectRequest
    | ConnectAccept
    | ConnectDeny
    | SessionConfig
    | PacketTypeRegistry
    | HostRegister
    | Ping
    | Pong
    | DisconnectNotice
    | Ack
    | ReconnectRequest
    | GamePacket
)


# ---------------------------------------------------------------------------
# NeonPacket
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class NeonPacket:
    """A fully parsed Neon protocol packet.

    Consists of a fixed 8-byte `PacketHeader` followed by a variable-length
    payload.
    """

    header: PacketHeader
    """Parsed packet header."""
    payload: AnyPayload
    """Parsed payload, typed by the header's ``packet_type``."""

    def to_bytes(self) -> bytes:
        """Serialise the complete packet to bytes."""
        return self.header.to_bytes() + self.payload.to_bytes()

    @classmethod
    def from_bytes(cls, data: bytes | bytearray) -> "NeonPacket":
        """Parse a complete packet from *data*.

        Raises:
            ValueError: if the data is too short, the magic is invalid, or a
                payload field is malformed.
        """
        if len(data) < HEADER_SIZE:
            raise ValueError(f"Packet too short: {len(data)}")
        header = PacketHeader.from_bytes(data)
        payload_bytes = bytes(data[HEADER_SIZE:])
        ptype = PacketType.from_byte(header.packet_type)
        payload: AnyPayload
        match ptype:
            case PacketType.CONNECT_REQUEST:
                payload = ConnectRequest.from_bytes(payload_bytes)
            case PacketType.CONNECT_ACCEPT:
                payload = ConnectAccept.from_bytes(payload_bytes)
            case PacketType.CONNECT_DENY:
                payload = ConnectDeny.from_bytes(payload_bytes)
            case PacketType.SESSION_CONFIG:
                payload = SessionConfig.from_bytes(payload_bytes)
            case PacketType.PACKET_TYPE_REGISTRY:
                payload = PacketTypeRegistry.from_bytes(payload_bytes)
            case PacketType.HOST_REGISTER:
                payload = HostRegister.from_bytes(payload_bytes)
            case PacketType.PING:
                payload = Ping.from_bytes(payload_bytes)
            case PacketType.PONG:
                payload = Pong.from_bytes(payload_bytes)
            case PacketType.DISCONNECT_NOTICE:
                payload = DisconnectNotice.from_bytes(payload_bytes)
            case PacketType.ACK:
                payload = Ack.from_bytes(payload_bytes)
            case PacketType.RECONNECT_REQUEST:
                payload = ReconnectRequest.from_bytes(payload_bytes)
            case PacketType.GAME_PACKET:
                payload = GamePacket.from_bytes(payload_bytes)
            case _:
                raise ValueError(f"Unhandled packet type: {ptype}")
        return cls(header, payload)

    @classmethod
    def create(
        cls,
        packet_type: PacketType,
        sequence: int,
        client_id: int,
        dest_id: int,
        payload: AnyPayload,
    ) -> "NeonPacket":
        """Convenience constructor that fills the header magic and version."""
        return cls(
            PacketHeader.create(packet_type.value, sequence, client_id, dest_id),
            payload,
        )
