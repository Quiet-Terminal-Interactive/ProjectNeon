"""Opt-in reliable delivery for game packets."""

from __future__ import annotations

import time
import threading
from typing import Callable, NamedTuple

from ._protocol import NeonPacket, PacketType, Ack
from ._protocol import _signed16


class _PendingEntry(NamedTuple):
    packet: NeonPacket
    sent_at: float
    retries: int


class ReliablePacketManager:
    """Opt-in reliable delivery with retransmit and duplicate detection.

    Tracks outbound packets waiting for ACK, retransmits on timeout, and
    deduplicates inbound packets per sender using signed 16-bit sequence
    arithmetic — correctly handling the 65535 → 0 wrap-around.

    Call `process_retransmissions` periodically (e.g. from the host processing
    loop) to drive retransmit and failure logic.

    Example:

    ```python
    mgr = ReliablePacketManager(socket, relay_addr, client_id=2, config=cfg)
    mgr.send_reliable(packet)

    # In the processing loop:
    mgr.process_retransmissions()

    # On receiving an ACK:
    for seq in ack_payload.sequences:
        mgr.acknowledge(seq)
    ```
    """

    def __init__(
        self,
        socket: "_NeonSocket",
        destination: tuple[str, int],
        client_id: int,
        config: "_NeonConfig",
    ) -> None:
        from ._socket import _NeonSocket
        from ._config import NeonConfig as _NeonConfig
        self._socket = socket
        self._destination = destination
        self._client_id = client_id
        self._timeout_s: float = config.reliable_packet_timeout_ms / 1000.0
        self._max_retries: int = config.reliable_packet_max_retries

        self._lock = threading.Lock()
        self._pending: dict[int, _PendingEntry] = {}
        self._last_received_seq: dict[int, int] = {}
        self._ack_sequence: int = 0
        self._on_delivery_failed: Callable[[int], None] | None = None

    def send_reliable(self, packet: NeonPacket) -> None:
        """Send *packet* and track it for retransmission until ACKed.

        Raises:
            OSError: if the initial send fails.
        """
        seq = packet.header.sequence
        self._socket.send_packet(packet, self._destination)
        with self._lock:
            self._pending[seq] = _PendingEntry(packet, time.monotonic(), 0)

    def acknowledge(self, sequence: int) -> None:
        """Mark *sequence* as acknowledged, removing it from the retransmit queue."""
        with self._lock:
            self._pending.pop(sequence, None)

    def process_retransmissions(self) -> None:
        """Check all pending packets for timeout; retransmit or fail as appropriate.

        Raises:
            OSError: if a retransmit send fails.
        """
        now = time.monotonic()
        with self._lock:
            to_retry: list[int] = []
            to_fail: list[int] = []
            for seq, entry in self._pending.items():
                if now - entry.sent_at >= self._timeout_s:
                    if entry.retries >= self._max_retries:
                        to_fail.append(seq)
                    else:
                        to_retry.append(seq)

            for seq in to_fail:
                del self._pending[seq]
                if self._on_delivery_failed:
                    self._on_delivery_failed(seq)

            for seq in to_retry:
                entry = self._pending[seq]
                self._pending[seq] = _PendingEntry(entry.packet, now, entry.retries + 1)
                self._socket.send_packet(entry.packet, self._destination)

    def is_duplicate(self, sender_id: int, sequence: int) -> bool:
        """Return ``True`` if *sequence* from *sender_id* is a duplicate.

        Uses signed 16-bit subtraction to handle the 65535 → 0 wrap-around.
        Updates the last-seen sequence for the sender on non-duplicate packets.
        """
        last = self._last_received_seq.get(sender_id)
        if last is not None and _signed16(sequence - last) <= 0:
            return True
        self._last_received_seq[sender_id] = sequence
        return False

    def send_ack_for(self, sequence: int) -> None:
        """Send an `Ack` for *sequence*.

        Raises:
            OSError: if the send fails.
        """
        seq = self._ack_sequence & 0xFFFF
        self._ack_sequence += 1
        pkt = NeonPacket.create(
            PacketType.ACK,
            seq,
            self._client_id,
            0,
            Ack((sequence,)),
        )
        self._socket.send_packet(pkt, self._destination)

    @property
    def has_pending(self) -> bool:
        """``True`` while any packets are still awaiting acknowledgement."""
        with self._lock:
            return bool(self._pending)

    def set_on_delivery_failed(self, callback: Callable[[int], None] | None) -> None:
        """Set a callback fired with the sequence number when delivery fails.

        The callback is invoked from the thread calling
        `process_retransmissions`.
        """
        self._on_delivery_failed = callback


# Avoid circular import — annotated type only
from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from ._socket import _NeonSocket
    from ._config import NeonConfig as _NeonConfig
