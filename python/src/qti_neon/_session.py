"""Relay-internal session and peer address management."""

from __future__ import annotations

import time
from typing import Iterator


class _Session:
    __slots__ = ("peers", "last_activity")

    def __init__(self) -> None:
        self.peers: dict[int, tuple[str, int]] = {}
        self.last_activity: float = time.monotonic()


class _SessionManager:
    """Stores host and client address mappings per session.

    All methods are called exclusively from the relay processing thread and
    are therefore not locked.
    """

    def __init__(self) -> None:
        self._sessions: dict[int, _Session] = {}
        # Reverse lookups
        self._peer_to_session: dict[tuple[str, int], int] = {}
        self._peer_to_client_id: dict[tuple[str, int], int] = {}

    def register_host(self, session_id: int, addr: tuple[str, int]) -> None:
        """Create a new session with *addr* as the host (client ID 1)."""
        session = _Session()
        session.peers[1] = addr
        self._sessions[session_id] = session
        self._peer_to_session[addr] = session_id
        self._peer_to_client_id[addr] = 1

    def register_peer(self, session_id: int, client_id: int, addr: tuple[str, int]) -> None:
        """Add a new peer to an existing session."""
        session = self._sessions.get(session_id)
        if session is None:
            return
        session.peers[client_id] = addr
        self._peer_to_session[addr] = session_id
        self._peer_to_client_id[addr] = client_id
        session.last_activity = time.monotonic()

    def remove_peer(self, addr: tuple[str, int]) -> None:
        """Remove *addr* from its session; tears down the whole session if it was the host."""
        session_id = self._peer_to_session.pop(addr, None)
        if session_id is None:
            return
        client_id = self._peer_to_client_id.pop(addr, None)
        if client_id is None:
            return
        session = self._sessions.get(session_id)
        if session is None:
            return
        session.peers.pop(client_id, None)
        if client_id == 1:
            # Host left — tear down the whole session.
            for peer_addr in list(session.peers.values()):
                self._peer_to_session.pop(peer_addr, None)
                self._peer_to_client_id.pop(peer_addr, None)
            del self._sessions[session_id]

    def get_host(self, session_id: int) -> tuple[str, int] | None:
        """Return the host address for *session_id*, or ``None``."""
        session = self._sessions.get(session_id)
        return session.peers.get(1) if session else None

    def get_peer_address(self, session_id: int, client_id: int) -> tuple[str, int] | None:
        """Return the address of *client_id* in *session_id*, or ``None``."""
        session = self._sessions.get(session_id)
        return session.peers.get(client_id) if session else None

    def get_all_peers_except(
        self, session_id: int, exclude: tuple[str, int]
    ) -> list[tuple[str, int]]:
        """Return all peer addresses in *session_id* except *exclude*."""
        session = self._sessions.get(session_id)
        if session is None:
            return []
        return [addr for addr in session.peers.values() if addr != exclude]

    def get_session_for_peer(self, addr: tuple[str, int]) -> int | None:
        """Return the session ID that *addr* belongs to, or ``None``."""
        return self._peer_to_session.get(addr)

    def get_client_count(self, session_id: int) -> int:
        """Return the number of connected clients (not counting the host)."""
        session = self._sessions.get(session_id)
        if session is None:
            return 0
        return max(0, len(session.peers) - 1)

    def update_last_seen(self, addr: tuple[str, int]) -> None:
        """Refresh the last-activity timestamp for the session owning *addr*."""
        session_id = self._peer_to_session.get(addr)
        if session_id is None:
            return
        session = self._sessions.get(session_id)
        if session:
            session.last_activity = time.monotonic()

    def update_peer_address(
        self, session_id: int, client_id: int, new_addr: tuple[str, int]
    ) -> None:
        """Remap *client_id* in *session_id* to *new_addr* (used during reconnect)."""
        session = self._sessions.get(session_id)
        if session is None:
            return
        old_addr = session.peers.get(client_id)
        if old_addr is not None:
            self._peer_to_session.pop(old_addr, None)
            self._peer_to_client_id.pop(old_addr, None)
        session.peers[client_id] = new_addr
        self._peer_to_session[new_addr] = session_id
        self._peer_to_client_id[new_addr] = client_id

    def remove_stale(self, threshold_ms: int) -> None:
        """Evict sessions whose last activity exceeds *threshold_ms* milliseconds."""
        now = time.monotonic()
        threshold_s = threshold_ms / 1000.0
        stale = [
            sid
            for sid, session in self._sessions.items()
            if now - session.last_activity > threshold_s
        ]
        for sid in stale:
            session = self._sessions.pop(sid)
            for addr in session.peers.values():
                self._peer_to_session.pop(addr, None)
                self._peer_to_client_id.pop(addr, None)
