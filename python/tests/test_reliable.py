"""Tests for ReliablePacketManager in isolation."""

import time
import threading
import pytest

from qti_neon import NeonConfig, NeonPacket, PacketType, Ping, Ack
from qti_neon._reliable import ReliablePacketManager
from qti_neon._protocol import _signed16


class _FakeSocket:
    """Minimal socket stub that records sends and provides configurable receives."""

    def __init__(self):
        self.sent: list[tuple[NeonPacket, tuple]] = []
        self._lock = threading.Lock()

    def send_packet(self, packet: NeonPacket, addr: tuple) -> None:
        with self._lock:
            self.sent.append((packet, addr))

    def sent_count(self) -> int:
        with self._lock:
            return len(self.sent)

    @property
    def is_closed(self) -> bool:
        return False


class TestReliablePacketManager:
    def _make(self, timeout_ms=200, max_retries=3):
        sock = _FakeSocket()
        cfg = NeonConfig(reliable_packet_timeout_ms=timeout_ms, reliable_packet_max_retries=max_retries)
        mgr = ReliablePacketManager(sock, ("127.0.0.1", 9999), client_id=2, config=cfg)
        return mgr, sock

    def _make_packet(self, seq=0):
        return NeonPacket.create(PacketType.PING, seq, 2, 1, Ping(0))

    def test_send_reliable_records_pending(self):
        mgr, sock = self._make()
        pkt = self._make_packet(seq=5)
        mgr.send_reliable(pkt)
        assert sock.sent_count() == 1
        assert mgr.has_pending

    def test_acknowledge_clears_pending(self):
        mgr, sock = self._make()
        pkt = self._make_packet(seq=5)
        mgr.send_reliable(pkt)
        mgr.acknowledge(5)
        assert not mgr.has_pending

    def test_retransmit_on_timeout(self):
        mgr, sock = self._make(timeout_ms=50)
        pkt = self._make_packet(seq=1)
        mgr.send_reliable(pkt)
        assert sock.sent_count() == 1
        time.sleep(0.08)
        mgr.process_retransmissions()
        assert sock.sent_count() == 2  # retransmitted once

    def test_delivery_failed_callback(self):
        failed: list[int] = []
        mgr, sock = self._make(timeout_ms=20, max_retries=1)
        mgr.set_on_delivery_failed(failed.append)

        pkt = self._make_packet(seq=7)
        mgr.send_reliable(pkt)

        # First timeout → retry
        time.sleep(0.025)
        mgr.process_retransmissions()
        # Second timeout → failure
        time.sleep(0.025)
        mgr.process_retransmissions()

        assert 7 in failed
        assert not mgr.has_pending

    def test_no_retransmit_after_ack(self):
        mgr, sock = self._make(timeout_ms=30)
        pkt = self._make_packet(seq=3)
        mgr.send_reliable(pkt)
        mgr.acknowledge(3)
        time.sleep(0.05)
        mgr.process_retransmissions()
        assert sock.sent_count() == 1  # only the original send

    def test_is_duplicate_detects_duplicate(self):
        mgr, sock = self._make()
        assert not mgr.is_duplicate(sender_id=2, sequence=10)
        assert mgr.is_duplicate(sender_id=2, sequence=10)   # same seq again
        assert mgr.is_duplicate(sender_id=2, sequence=9)    # older seq

    def test_is_duplicate_different_senders_independent(self):
        mgr, sock = self._make()
        assert not mgr.is_duplicate(sender_id=2, sequence=5)
        assert not mgr.is_duplicate(sender_id=3, sequence=5)

    def test_is_duplicate_wrap_around(self):
        mgr, sock = self._make()
        # Move last-seen to near the wrap
        mgr.is_duplicate(sender_id=2, sequence=65530)
        # Sequence 1 after wrap-around is NOT a duplicate
        assert not mgr.is_duplicate(sender_id=2, sequence=1)

    def test_send_ack_for(self):
        mgr, sock = self._make()
        mgr.send_ack_for(42)
        assert sock.sent_count() == 1
        pkt, _ = sock.sent[-1]
        assert PacketType.from_byte(pkt.header.packet_type) is PacketType.ACK
        assert isinstance(pkt.payload, Ack)
        assert 42 in pkt.payload.sequences
