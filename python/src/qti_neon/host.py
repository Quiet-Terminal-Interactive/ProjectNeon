"""Neon session host."""

from __future__ import annotations

import enum
import logging
import secrets
import struct as _struct
import threading
import time
from typing import Callable

from ._ack import _AckStateMachine
from ._config import NeonConfig
from ._protocol import (
    NeonPacket,
    PacketType,
    PacketHeader,
    ConnectAccept,
    ConnectDeny,
    ConnectRequest,
    ReconnectRequest,
    SessionConfig,
    PacketTypeRegistry,
    DisconnectNotice,
    Ack,
    Ping,
    Pong,
)
from ._registry import GamePacketRegistry
from ._socket import _NeonSocket

logger = logging.getLogger(__name__)


class _DisconnectedClient:
    __slots__ = ("name", "token", "disconnected_at")

    def __init__(self, name: str, token: int, disconnected_at: float) -> None:
        self.name = name
        self.token = token
        self.disconnected_at = disconnected_at


class _State(enum.Enum):
    CREATED = "CREATED"
    STARTING = "STARTING"
    RUNNING = "RUNNING"
    STOPPING = "STOPPING"
    STOPPED = "STOPPED"
    FAILED = "FAILED"


class NeonHost:
    """Session host for the Neon multiplayer protocol.

    Registers a session with a `NeonRelay`, accepts client connections, manages
    session state, and reliably delivers `SessionConfig` to each connecting
    client. The host always occupies client ID ``1``; player IDs start at ``2``.

    Typical usage:

    ```python
    import threading
    from qti_neon import NeonHost, NeonConfig

    host = NeonHost(session_id=42, relay_address="relay.example.com:7777")
    host.set_client_connect_callback(lambda cid, name, sid: print(f"{name} joined"))
    host.set_unhandled_packet_callback(lambda ptype, sender: handle_game(ptype, sender))

    threading.Thread(target=host.start_and_run, daemon=True).start()
    ```
    """

    def __init__(
        self,
        session_id: int,
        relay_address: str,
        config: NeonConfig | None = None,
    ) -> None:
        """Create a host for *session_id* that will register with *relay_address*.

        Args:
            session_id: Unique session identifier (must be > 0).
            relay_address: Relay address in ``"host:port"`` format.
            config: Protocol configuration. Uses defaults when ``None``.

        Raises:
            OSError: if the local UDP socket cannot be bound.
        """
        self._config = config or NeonConfig()
        self._session_id = session_id
        self._relay_addr = _parse_address(relay_address)
        self._socket = _NeonSocket(config=self._config)
        self._ack_machine = _AckStateMachine(
            self._config.host_ack_timeout_ms,
            self._config.host_max_ack_retries,
        )

        self._next_client_id: int = 2
        self._next_sequence: int = 0
        self._id_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._state = _State.CREATED

        self._connected_clients: dict[int, str] = {}
        self._connected_names: dict[str, int] = {}
        self._client_tokens: dict[int, int] = {}
        self._disconnected_clients: dict[int, _DisconnectedClient] = {}
        self._seq_to_client: dict[int, int] = {}
        self._clients_lock = threading.Lock()

        # Callbacks
        self._client_connect_callback: Callable[[int, str, int], None] | None = None
        self._client_deny_callback: Callable[[str, str], None] | None = None
        self._client_disconnect_callback: Callable[[int], None] | None = None
        self._ping_received_callback: Callable[[int], None] | None = None
        self._unhandled_packet_callback: Callable[[int, int], None] | None = None
        self._game_packet_registry: GamePacketRegistry | None = None

    # ------------------------------------------------------------------ #
    # Callbacks                                                            #
    # ------------------------------------------------------------------ #

    def set_client_connect_callback(
        self, callback: Callable[[int, str, int], None] | None
    ) -> None:
        """Set a callback fired when a client joins or rejoins.

        Args:
            callback: Called with ``(client_id, player_name, session_id)``.
        """
        self._client_connect_callback = callback

    def set_client_deny_callback(
        self, callback: Callable[[str, str], None] | None
    ) -> None:
        """Set a callback fired when a connection request is denied.

        Args:
            callback: Called with ``(player_name, reason)``.
        """
        self._client_deny_callback = callback

    def set_client_disconnect_callback(
        self, callback: Callable[[int], None] | None
    ) -> None:
        """Set a callback fired when a client disconnects.

        Args:
            callback: Called with the disconnecting ``client_id``.
        """
        self._client_disconnect_callback = callback

    def set_ping_received_callback(
        self, callback: Callable[[int], None] | None
    ) -> None:
        """Set a callback fired when a PING is received.

        Args:
            callback: Called with the sender's ``client_id``.
        """
        self._ping_received_callback = callback

    def set_unhandled_packet_callback(
        self, callback: Callable[[int, int], None] | None
    ) -> None:
        """Set a callback fired for game-specific packets.

        Args:
            callback: Called with ``(packet_type_byte, sender_client_id)``.
        """
        self._unhandled_packet_callback = callback

    def set_game_packet_registry(self, registry: GamePacketRegistry | None) -> None:
        """Set the registry of game packet types advertised to connecting clients.

        When not set, an empty `PacketTypeRegistry` is sent.
        """
        self._game_packet_registry = registry

    # ------------------------------------------------------------------ #
    # Lifecycle                                                            #
    # ------------------------------------------------------------------ #

    def start(self) -> None:
        """Register with the relay without entering the processing loop.

        Blocks until the relay acknowledges the registration or the connection
        timeout elapses. Call `start_and_run` for typical use.

        Raises:
            RuntimeError: if called from a state other than ``CREATED``.
            OSError: if registration with the relay fails or times out.
        """
        with self._state_lock:
            if self._state is not _State.CREATED:
                raise RuntimeError(f"Cannot start from state: {self._state.value}")
            self._state = _State.STARTING
        try:
            self._do_start()
            with self._state_lock:
                self._state = _State.RUNNING
        except Exception:
            with self._state_lock:
                self._state = _State.FAILED
            raise

    def stop(self) -> None:
        """Send a disconnect notice, drain pending ACKs, and close the socket.

        Raises:
            RuntimeError: if the host is not running.
        """
        with self._state_lock:
            if self._state is not _State.RUNNING:
                raise RuntimeError(f"Cannot stop from state: {self._state.value}")
            self._state = _State.STOPPING
        try:
            self._do_stop()
        finally:
            with self._state_lock:
                self._state = _State.STOPPED

    def start_and_run(self) -> None:
        """Register with the relay and block in the processing loop.

        Intended to run in a dedicated thread:

        ```python
        threading.Thread(target=host.start_and_run, daemon=True).start()
        ```

        Raises:
            OSError: if relay registration fails.
        """
        self.start()
        self._run()

    def _do_start(self) -> None:
        if self._config.is_dtls_enabled:
            self._socket.perform_client_handshake(
                self._config.dtls_config,  # type: ignore[arg-type]
                self._relay_addr,
                self._config.client_connection_timeout_ms,
            )

        host_token = _struct.unpack("<Q", secrets.token_bytes(8))[0]
        self._client_tokens[1] = host_token
        self._socket.send_packet(
            NeonPacket.create(
                PacketType.HOST_REGISTER,
                self._next_seq(),
                1,
                0,
                _HostRegister(self._session_id, host_token),
            ),
            self._relay_addr,
        )

        timeout_s = self._config.client_connection_timeout_ms / 1000.0
        self._socket.set_blocking(True, timeout_s)
        try:
            deadline = time.monotonic() + timeout_s
            while True:
                if time.monotonic() >= deadline:
                    raise OSError("Relay registration timed out")
                result = self._socket.receive_packet()
                if result is None:
                    raise OSError("No response from relay during registration")
                packet, _ = result
                if isinstance(packet.payload, ConnectAccept) and packet.payload.client_id == 1:
                    logger.info("Registered with relay for session %d", self._session_id)
                    return
        finally:
            self._socket.set_blocking(False)

    def _do_stop(self) -> None:
        try:
            self._socket.send_packet(
                NeonPacket.create(
                    PacketType.DISCONNECT_NOTICE,
                    self._next_seq(),
                    1,
                    0,
                    DisconnectNotice(),
                ),
                self._relay_addr,
            )
        except OSError:
            pass

        deadline = time.monotonic() + self._config.host_graceful_shutdown_timeout_ms / 1000.0
        while self._ack_machine.has_pending and time.monotonic() < deadline:
            result = self._ack_machine.process()
            for seq in result.needs_retry:
                pkt = self._ack_machine.mark_resent(seq)
                if pkt is not None:
                    try:
                        self._socket.send_packet(pkt, self._relay_addr)
                    except OSError:
                        pass
            for seq in result.failed:
                self._ack_machine.acknowledge(seq)
            time.sleep(0.01)

        self._socket.close()

    @property
    def is_running(self) -> bool:
        """``True`` while the host processing loop is active."""
        return self._state is _State.RUNNING

    def local_address(self) -> tuple[str, int]:
        """Return the local ``(host, port)`` of the underlying UDP socket."""
        return self._socket.local_address()

    def __enter__(self) -> "NeonHost":
        return self

    def __exit__(self, *_: object) -> None:
        if self._state is _State.RUNNING:
            self.stop()

    # ------------------------------------------------------------------ #
    # Packet loop                                                          #
    # ------------------------------------------------------------------ #

    def _run(self) -> None:
        while self.is_running:
            try:
                self._process_packets()
                self._check_pending_acks()
                time.sleep(self._config.host_processing_loop_sleep_ms / 1000.0)
            except InterruptedError:
                break
            except OSError as exc:
                if not self._socket.is_closed:
                    logger.warning("IO error in host loop: %s", exc)
                break

    def _process_packets(self) -> None:
        while True:
            result = self._socket.receive_packet()
            if result is None:
                break
            packet, _ = result
            self._handle_packet(packet)

    def _handle_packet(self, packet: NeonPacket) -> None:
        header = packet.header
        match packet.payload:
            case ConnectRequest():
                self._handle_connect_request(packet.payload, header)
            case ReconnectRequest():
                self._handle_reconnect_request(packet.payload)
            case Ping():
                self._handle_ping(packet.payload, header)
            case Ack():
                self._handle_ack(packet.payload)
            case DisconnectNotice():
                self._handle_disconnect(header)
            case _:
                self._dispatch_to_game(header)

    def _handle_connect_request(self, req: ConnectRequest, header: PacketHeader) -> None:
        with self._clients_lock:
            if len(self._connected_clients) >= self._config.max_clients_per_session:
                self._send_deny(req.name, "Session full")
                return
            if req.name in self._connected_names:
                self._send_deny(req.name, "Name taken")
                return

            with self._id_lock:
                cid = self._next_client_id
                self._next_client_id += 1

            if cid == 0 or cid == 1 or cid > 254:
                self._send_deny(req.name, "Server full")
                return

            token = _struct.unpack("<Q", secrets.token_bytes(8))[0]
            self._connected_clients[cid] = req.name
            self._connected_names[req.name] = cid
            self._client_tokens[cid] = token

        self._socket.send_packet(
            NeonPacket.create(
                PacketType.CONNECT_ACCEPT,
                self._next_seq(),
                1,
                0,
                ConnectAccept(cid, self._session_id, token),
            ),
            self._relay_addr,
        )

        if self._client_connect_callback:
            self._client_connect_callback(cid, req.name, self._session_id)

        cfg_seq = self._next_seq()
        cfg_packet = NeonPacket.create(
            PacketType.SESSION_CONFIG,
            cfg_seq,
            1,
            cid,
            SessionConfig(
                PacketHeader.VERSION,
                self._config.host_session_tick_rate,
                self._config.host_session_max_packet_size,
            ),
        )
        self._socket.send_packet(cfg_packet, self._relay_addr)
        self._ack_machine.track(cfg_seq, cfg_packet)
        self._seq_to_client[cfg_seq] = cid

        registry = (
            self._game_packet_registry.build_registry()
            if self._game_packet_registry and not self._game_packet_registry.is_empty
            else PacketTypeRegistry(())
        )
        self._socket.send_packet(
            NeonPacket.create(
                PacketType.PACKET_TYPE_REGISTRY,
                self._next_seq(),
                1,
                cid,
                registry,
            ),
            self._relay_addr,
        )
        logger.info(
            "Client %d (%s) connected to session %d", cid, req.name, self._session_id
        )

    def _handle_reconnect_request(self, req: ReconnectRequest) -> None:
        prev_id = req.previous_client_id
        with self._clients_lock:
            dc = self._disconnected_clients.get(prev_id)
            if dc is None:
                self._send_deny("", "Reconnect denied")
                return
            if dc.token != req.token:
                self._send_deny(dc.name, "Reconnect denied")
                return
            elapsed_ms = (time.monotonic() - dc.disconnected_at) * 1000
            if elapsed_ms > self._config.host_session_token_timeout_ms:
                del self._disconnected_clients[prev_id]
                self._send_deny(dc.name, "Token expired")
                return

            del self._disconnected_clients[prev_id]
            new_token = _struct.unpack("<Q", secrets.token_bytes(8))[0]
            self._connected_clients[prev_id] = dc.name
            self._connected_names[dc.name] = prev_id
            self._client_tokens[prev_id] = new_token
            name = dc.name

        self._socket.send_packet(
            NeonPacket.create(
                PacketType.CONNECT_ACCEPT,
                self._next_seq(),
                1,
                0,
                ConnectAccept(prev_id, self._session_id, new_token),
            ),
            self._relay_addr,
        )
        if self._client_connect_callback:
            self._client_connect_callback(prev_id, name, self._session_id)
        logger.info(
            "Client %d (%s) reconnected to session %d", prev_id, name, self._session_id
        )

    def _handle_ping(self, ping: Ping, header: PacketHeader) -> None:
        self._socket.send_packet(
            NeonPacket.create(
                PacketType.PONG,
                self._next_seq(),
                1,
                header.client_id,
                Pong(ping.timestamp),
            ),
            self._relay_addr,
        )
        if self._ping_received_callback:
            self._ping_received_callback(header.client_id)

    def _handle_ack(self, ack: Ack) -> None:
        for seq in ack.sequences:
            self._ack_machine.acknowledge(seq)
            self._seq_to_client.pop(seq, None)

    def _handle_disconnect(self, header: PacketHeader) -> None:
        cid = header.client_id
        with self._clients_lock:
            name = self._connected_clients.pop(cid, None)
            if name is None:
                return
            self._connected_names.pop(name, None)
            token = self._client_tokens.get(cid, 0)
            self._disconnected_clients[cid] = _DisconnectedClient(
                name, token, time.monotonic()
            )
        self._seq_to_client = {
            seq: c for seq, c in self._seq_to_client.items() if c != cid
        }
        if self._client_disconnect_callback:
            self._client_disconnect_callback(cid)
        logger.info(
            "Client %d (%s) disconnected from session %d", cid, name, self._session_id
        )

    def _dispatch_to_game(self, header: PacketHeader) -> None:
        if self._unhandled_packet_callback:
            self._unhandled_packet_callback(header.packet_type, header.client_id)

    def _check_pending_acks(self) -> None:
        result = self._ack_machine.process()
        for seq in result.needs_retry:
            pkt = self._ack_machine.mark_resent(seq)
            if pkt is not None:
                self._socket.send_packet(pkt, self._relay_addr)
        for seq in result.failed:
            logger.warning("ACK permanently failed for sequence %d", seq)
            self._ack_machine.acknowledge(seq)
            self._seq_to_client.pop(seq, None)

    def _send_deny(self, name: str, reason: str) -> None:
        if self._client_deny_callback and name:
            self._client_deny_callback(name, reason)
        self._socket.send_packet(
            NeonPacket.create(
                PacketType.CONNECT_DENY, self._next_seq(), 1, 0, ConnectDeny(reason)
            ),
            self._relay_addr,
        )

    # ------------------------------------------------------------------ #
    # Public API                                                           #
    # ------------------------------------------------------------------ #

    def send_packet(self, payload: bytes, packet_type: int, dest_id: int) -> None:
        """Send a game-specific payload through the relay.

        Args:
            payload: Raw payload bytes.
            packet_type: Application-defined packet type byte (must be ``>= 0x10``).
            dest_id: Destination client ID, or ``0`` to broadcast to all peers.

        Raises:
            OSError: if the send fails.
        """
        from ._protocol import GamePacket
        header = PacketHeader.create(packet_type, self._next_seq(), 1, dest_id)
        self._socket.send_packet(NeonPacket(header, GamePacket(payload)), self._relay_addr)

    def connected_clients(self) -> dict[int, str]:
        """Return a snapshot of ``{client_id: player_name}`` for all connected clients."""
        with self._clients_lock:
            return dict(self._connected_clients)

    def _next_seq(self) -> int:
        seq = self._next_sequence & 0xFFFF
        self._next_sequence += 1
        return seq


# Avoid circular import
from ._protocol import HostRegister as _HostRegister


def _parse_address(address: str) -> tuple[str, int]:
    last_colon = address.rfind(":")
    return address[:last_colon], int(address[last_colon + 1 :])
