"""Tests for packet serialisation and deserialisation."""

import struct
import pytest

from qti_neon import (
    MAGIC,
    VERSION,
    HEADER_SIZE,
    PacketType,
    PacketHeader,
    NeonPacket,
    ConnectRequest,
    ConnectAccept,
    ConnectDeny,
    SessionConfig,
    PacketTypeEntry,
    PacketTypeRegistry,
    HostRegister,
    Ping,
    Pong,
    DisconnectNotice,
    Ack,
    ReconnectRequest,
    GamePacket,
)
from qti_neon._protocol import _signed16


# ---------------------------------------------------------------------------
# PacketType
# ---------------------------------------------------------------------------

class TestPacketType:
    def test_from_byte_known(self):
        assert PacketType.from_byte(0x01) is PacketType.CONNECT_REQUEST
        assert PacketType.from_byte(0x06) is PacketType.HOST_REGISTER
        assert PacketType.from_byte(0x0E) is PacketType.ACK

    def test_from_byte_game_packet(self):
        for b in range(0x10, 0x20):
            assert PacketType.from_byte(b) is PacketType.GAME_PACKET

    def test_from_byte_unknown_raises(self):
        with pytest.raises(ValueError):
            PacketType.from_byte(0x07)

    def test_is_core_packet(self):
        assert PacketType.CONNECT_REQUEST.is_core_packet()
        assert not PacketType.GAME_PACKET.is_core_packet()


# ---------------------------------------------------------------------------
# PacketHeader
# ---------------------------------------------------------------------------

class TestPacketHeader:
    def _make(self, **kw):
        defaults = dict(magic=MAGIC, version=VERSION, packet_type=0x01,
                        sequence=0, client_id=0, destination_id=1)
        defaults.update(kw)
        return PacketHeader(**defaults)

    def test_bad_magic_raises(self):
        with pytest.raises(ValueError):
            PacketHeader(0xDEAD, VERSION, 0x01, 0, 0, 1)

    def test_round_trip(self):
        h = PacketHeader.create(0x01, 42, 3, 1)
        data = h.to_bytes()
        assert len(data) == HEADER_SIZE
        h2 = PacketHeader.from_bytes(data)
        assert h == h2

    def test_little_endian_magic(self):
        h = PacketHeader.create(0x01, 0, 0, 0)
        raw = h.to_bytes()
        # magic 0x4E45 little-endian → bytes [0x45, 0x4E]
        assert raw[0] == 0x45
        assert raw[1] == 0x4E

    def test_sequence_wraps(self):
        h = PacketHeader.create(0x01, 65535, 0, 0)
        data = h.to_bytes()
        h2 = PacketHeader.from_bytes(data)
        assert h2.sequence == 65535

    def test_too_short_raises(self):
        with pytest.raises(ValueError):
            PacketHeader.from_bytes(b"\x45\x4E")


# ---------------------------------------------------------------------------
# Payload round-trips
# ---------------------------------------------------------------------------

class TestConnectRequest:
    def test_round_trip(self):
        p = ConnectRequest(client_version=1, name="alice", session_id=42, game_id=7)
        p2 = ConnectRequest.from_bytes(p.to_bytes())
        assert p == p2

    def test_name_preserved(self):
        p = ConnectRequest(1, "björn", 1, 0)
        assert ConnectRequest.from_bytes(p.to_bytes()).name == "björn"

    def test_too_short_raises(self):
        with pytest.raises(ValueError):
            ConnectRequest.from_bytes(b"\x01\x01")

    def test_name_too_long_raises(self):
        long_name = "x" * 65
        p = ConnectRequest(1, long_name, 1, 0)
        with pytest.raises(ValueError):
            ConnectRequest.from_bytes(p.to_bytes())


class TestConnectAccept:
    def test_round_trip(self):
        p = ConnectAccept(client_id=2, session_id=42, token=0x0EADBEEFC0FFEE42)
        p2 = ConnectAccept.from_bytes(p.to_bytes())
        assert p == p2

    def test_size(self):
        assert len(ConnectAccept(1, 1, 0).to_bytes()) == 13


class TestConnectDeny:
    def test_round_trip(self):
        p = ConnectDeny("Session full")
        assert ConnectDeny.from_bytes(p.to_bytes()) == p


class TestSessionConfig:
    def test_round_trip(self):
        p = SessionConfig(version=1, tick_rate=60, max_packet_size=1200)
        p2 = SessionConfig.from_bytes(p.to_bytes())
        assert p == p2

    def test_size(self):
        assert len(SessionConfig(1, 60, 1200).to_bytes()) == 5


class TestPacketTypeRegistry:
    def test_empty_round_trip(self):
        p = PacketTypeRegistry(())
        p2 = PacketTypeRegistry.from_bytes(p.to_bytes())
        assert p2.entries == ()

    def test_with_entries_round_trip(self):
        entries = (
            PacketTypeEntry(0x10, "POSITION", "Player position"),
            PacketTypeEntry(0x11, "CHAT", "Chat message"),
        )
        p = PacketTypeRegistry(entries)
        p2 = PacketTypeRegistry.from_bytes(p.to_bytes())
        assert len(p2.entries) == 2
        assert p2.entries[0].name == "POSITION"
        assert p2.entries[1].description == "Chat message"


class TestHostRegister:
    def test_round_trip(self):
        p = HostRegister(session_id=99, host_token=0xABCDEF0123456789)
        p2 = HostRegister.from_bytes(p.to_bytes())
        assert p == p2

    def test_size(self):
        assert len(HostRegister(1, 0).to_bytes()) == 12


class TestPingPong:
    def test_ping_round_trip(self):
        p = Ping(timestamp=123456789)
        assert Ping.from_bytes(p.to_bytes()) == p

    def test_pong_round_trip(self):
        p = Pong(original_timestamp=987654321)
        assert Pong.from_bytes(p.to_bytes()) == p


class TestDisconnectNotice:
    def test_round_trip(self):
        p = DisconnectNotice()
        assert p.to_bytes() == b""
        assert DisconnectNotice.from_bytes(b"") == p


class TestAck:
    def test_round_trip_single(self):
        p = Ack((42,))
        p2 = Ack.from_bytes(p.to_bytes())
        assert p2.sequences == (42,)

    def test_round_trip_multiple(self):
        p = Ack((1, 2, 3, 100))
        p2 = Ack.from_bytes(p.to_bytes())
        assert p2.sequences == (1, 2, 3, 100)


class TestReconnectRequest:
    def test_round_trip(self):
        p = ReconnectRequest(token=0x1234567890ABCDEF, session_id=42, previous_client_id=3)
        p2 = ReconnectRequest.from_bytes(p.to_bytes())
        assert p == p2

    def test_size(self):
        assert len(ReconnectRequest(0, 0, 0).to_bytes()) == 13


class TestGamePacket:
    def test_round_trip(self):
        p = GamePacket(b"\x01\x02\x03")
        assert GamePacket.from_bytes(p.to_bytes()).payload == b"\x01\x02\x03"

    def test_empty(self):
        p = GamePacket(b"")
        assert GamePacket.from_bytes(p.to_bytes()).payload == b""


# ---------------------------------------------------------------------------
# NeonPacket
# ---------------------------------------------------------------------------

class TestNeonPacket:
    def _make_packet(self, ptype=PacketType.PING, payload=None):
        if payload is None:
            payload = Ping(999)
        return NeonPacket.create(ptype, sequence=7, client_id=2, dest_id=1, payload=payload)

    def test_round_trip_ping(self):
        pkt = self._make_packet()
        data = pkt.to_bytes()
        pkt2 = NeonPacket.from_bytes(data)
        assert pkt2.header == pkt.header
        assert isinstance(pkt2.payload, Ping)
        assert pkt2.payload.timestamp == 999

    def test_round_trip_all_types(self):
        cases = [
            (PacketType.CONNECT_REQUEST, ConnectRequest(1, "bob", 1, 0)),
            (PacketType.CONNECT_ACCEPT, ConnectAccept(2, 1, 12345)),
            (PacketType.CONNECT_DENY, ConnectDeny("Full")),
            (PacketType.SESSION_CONFIG, SessionConfig(1, 60, 1200)),
            (PacketType.PACKET_TYPE_REGISTRY, PacketTypeRegistry(())),
            (PacketType.HOST_REGISTER, HostRegister(1, -1)),
            (PacketType.PING, Ping(0)),
            (PacketType.PONG, Pong(0)),
            (PacketType.DISCONNECT_NOTICE, DisconnectNotice()),
            (PacketType.ACK, Ack((5,))),
            (PacketType.RECONNECT_REQUEST, ReconnectRequest(99, 1, 3)),
            (PacketType.GAME_PACKET, GamePacket(b"hello")),
        ]
        for ptype, payload in cases:
            pkt = NeonPacket.create(ptype, 0, 0, 0, payload)
            data = pkt.to_bytes()
            pkt2 = NeonPacket.from_bytes(data)
            assert PacketType.from_byte(pkt2.header.packet_type) == ptype, f"failed for {ptype}"

    def test_too_short_raises(self):
        with pytest.raises(ValueError):
            NeonPacket.from_bytes(b"\x45\x4E\x01\x01")

    def test_bad_magic_raises(self):
        bad = b"\x00\x00\x01\x01\x00\x00\x00\x00"
        with pytest.raises(ValueError):
            NeonPacket.from_bytes(bad)

    def test_total_length(self):
        pkt = NeonPacket.create(PacketType.PING, 0, 0, 0, Ping(0))
        assert len(pkt.to_bytes()) == HEADER_SIZE + 8  # 8-byte Ping payload


# ---------------------------------------------------------------------------
# _signed16 helper
# ---------------------------------------------------------------------------

class TestSigned16:
    def test_positive(self):
        assert _signed16(10) == 10

    def test_max_positive(self):
        assert _signed16(32767) == 32767

    def test_wraps_to_negative(self):
        assert _signed16(32768) == -32768

    def test_minus_one(self):
        assert _signed16(65535) == -1

    def test_wrap_around_detection(self):
        # Sequence 1 after 65535 is NOT a duplicate (forward progress across wrap)
        assert _signed16(1 - 65535) > 0
        # Sequence 65534 after 0 is backward (two steps behind) → treated as duplicate
        assert _signed16(65534 - 0) < 0
        # Sequence 0 after 1 is backward → duplicate
        assert _signed16(0 - 1) < 0
        # Sequence 2 after 0 is forward (skipped one, but not backward)
        assert _signed16(2 - 0) > 0
