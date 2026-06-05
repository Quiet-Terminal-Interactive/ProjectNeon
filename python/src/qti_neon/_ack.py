"""ACK state machine used by NeonHost for reliable SESSION_CONFIG delivery."""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import NamedTuple

from ._protocol import NeonPacket


class _AckEntry(NamedTuple):
    packet: NeonPacket
    sent_at: float
    retries: int


class ProcessResult(NamedTuple):
    """Result of one `_AckStateMachine.process` tick."""

    needs_retry: list[int]
    """Sequence numbers whose timeout elapsed and that should be retransmitted."""
    failed: list[int]
    """Sequence numbers that have exhausted all retries."""


class _AckStateMachine:
    """Tracks outbound ``SESSION_CONFIG`` packets awaiting acknowledgement.

    Used internally by `~qti_neon.host.NeonHost`. Call `process` on each host
    loop iteration to drive retransmit logic.
    """

    def __init__(self, ack_timeout_ms: int, max_retries: int) -> None:
        self._timeout_s: float = ack_timeout_ms / 1000.0
        self._max_retries: int = max_retries
        self._pending: dict[int, _AckEntry] = {}

    def track(self, sequence: int, packet: NeonPacket) -> None:
        """Begin tracking *packet* for retransmission until ACKed."""
        self._pending[sequence] = _AckEntry(packet, time.monotonic(), 0)

    def process(self) -> ProcessResult:
        """Evaluate pending entries and return which need a retry or have failed."""
        now = time.monotonic()
        needs_retry: list[int] = []
        failed: list[int] = []
        for seq, entry in self._pending.items():
            if now - entry.sent_at >= self._timeout_s:
                if entry.retries >= self._max_retries:
                    failed.append(seq)
                else:
                    needs_retry.append(seq)
        return ProcessResult(needs_retry, failed)

    def mark_resent(self, sequence: int) -> NeonPacket | None:
        """Record a retransmission attempt for *sequence*.

        Returns the packet to retransmit, or ``None`` if *sequence* is no
        longer tracked.
        """
        entry = self._pending.get(sequence)
        if entry is None:
            return None
        self._pending[sequence] = _AckEntry(entry.packet, time.monotonic(), entry.retries + 1)
        return entry.packet

    def acknowledge(self, sequence: int) -> None:
        """Remove *sequence* from the pending set."""
        self._pending.pop(sequence, None)

    def get_packet(self, sequence: int) -> NeonPacket | None:
        """Return the tracked packet for *sequence*, or ``None``."""
        entry = self._pending.get(sequence)
        return entry.packet if entry else None

    @property
    def has_pending(self) -> bool:
        """``True`` while any packets are still awaiting acknowledgement."""
        return bool(self._pending)
