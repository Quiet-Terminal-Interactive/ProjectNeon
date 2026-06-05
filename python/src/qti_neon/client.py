"""Neon relay-connected UDP client."""

from __future__ import annotations

import enum
import logging
import threading
import time
from typing import Callable

from ._config import NeonConfig
from ._protocol import (
    NeonPacket,
    PacketType,
    PacketHeader,
    ConnectRequest,
    ConnectAccept,
    ConnectDeny,
    ReconnectRequest,
    SessionConfig,
    PacketTypeRegistry,
    DisconnectNotice,
    Ack,
    Ping,
    Pong,
)
from ._socket import _NeonSocket

logger = logging.getLogger(__name__)


class _State(enum.Enum):
    CREATED = "CREATED"
    STARTING = "STARTING"
    RUNNING = "RUNNING"
    STOPPING = "STOPPING"
    STOPPED = "STOPPED"
    FAILED = "FAILED"


class NeonClient:
    """Relay-connected UDP client for the Neon multiplayer protocol.

    Connects to a session hosted by a `NeonHost` through a `NeonRelay`. The
    connection handshake is synchronous; ongoing packet processing is driven
    either by calling `run` in a dedicated thread or by periodically calling
    `process_packets` from the game loop.

    Typical usage:

    ```python
    import threading
    from qti_neon import NeonClient, NeonConfig

    client = NeonClient("player1", NeonConfig())
    client.set_session_config_callback(lambda sc: print(f"tick rate: {sc.tick_rate}"))
    client.set_unhandled_packet_callback(lambda ptype, sender: handle_game(ptype, sender))

    if client.connect(session_id=42, relay_address="relay.example.com:7777"):
        threading.Thread(target=client.run, daemon=True).start()
    ```
    """

    def __init__(self, name: str, config: NeonConfig | None = None) -> None:
        """Create a client with the given display name.

        Binds a local UDP socket on an OS-assigned port.

        Args:
            name: Player display name sent to the host during connection.
            config: Protocol configuration. Uses defaults when ``None``.

        Raises:
            OSError: if the local socket cannot be bound.
        """
        self._name = name
        self._config = config or NeonConfig()
        self._socket = _NeonSocket(config=self._config)

        self._relay_addr: tuple[str, int] | None = None
        self._session_id: int | None = None
        self._client_id: int | None = None
        self._session_token: int | None = None

        self._next_sequence: int = 0
        self._seq_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._state = _State.CREATED
        self._last_ping: float = time.monotonic()

        # Callbacks
        self._pong_callback: Callable[[Pong], None] | None = None
        self._session_config_callback: Callable[[SessionConfig], None] | None = None
        self._packet_type_registry_callback: Callable[[PacketTypeRegistry], None] | None = None
        self._unhandled_packet_callback: Callable[[int, int], None] | None = None
        self._disconnect_callback: Callable[[int], None] | None = None

    # ------------------------------------------------------------------ #
    # Callbacks                                                            #
    # ------------------------------------------------------------------ #

    def set_pong_callback(self, callback: Callable[[Pong], None] | None) -> None:
        """Set a callback fired when a `Pong` is received.

        Args:
            callback: Called with the `Pong` payload.
        """
        self._pong_callback = callback

    def set_session_config_callback(
        self, callback: Callable[[SessionConfig], None] | None
    ) -> None:
        """Set a callback fired when the host delivers a `SessionConfig`.

        The client ACKs automatically; the callback is for application use only.

        Args:
            callback: Called with the `SessionConfig` payload.
        """
        self._session_config_callback = callback

    def set_packet_type_registry_callback(
        self, callback: Callable[[PacketTypeRegistry], None] | None
    ) -> None:
        """Set a callback fired when the host broadcasts its `PacketTypeRegistry`.

        Args:
            callback: Called with the `PacketTypeRegistry` payload.
        """
        self._packet_type_registry_callback = callback

    def set_unhandled_packet_callback(
        self, callback: Callable[[int, int], None] | None
    ) -> None:
        """Set a callback fired for packets not handled by the protocol layer.

        Args:
            callback: Called with ``(packet_type_byte, sender_client_id)``.
        """
        self._unhandled_packet_callback = callback

    def set_disconnect_callback(self, callback: Callable[[int], None] | None) -> None:
        """Set a callback fired when a `DisconnectNotice` is received.

        Args:
            callback: Called with the disconnecting peer's ``client_id``.
        """
        self._disconnect_callback = callback

    # ------------------------------------------------------------------ #
    # Connection                                                           #
    # ------------------------------------------------------------------ #

    def connect(self, session_id: int, relay_address: str) -> bool:
        """Connect to *session_id* through *relay_address*.

        Blocks until the relay accepts or denies the connection, or until the
        connection timeout elapses.

        Args:
            session_id: The session to join.
            relay_address: Relay address in ``"host:port"`` format.

        Returns:
            ``True`` if the connection was accepted; ``False`` if denied or
            timed out.
        """
        self._session_id = session_id
        self._relay_addr = _parse_address(relay_address)
        try:
            self._start()
            return True
        except Exception as exc:
            logger.warning("Connection failed: %s", exc)
            return False

    def _start(self) -> None:
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

    def _do_start(self) -> None:
        assert self._relay_addr is not None
        assert self._session_id is not None

        if self._config.is_dtls_enabled:
            self._socket.perform_client_handshake(
                self._config.dtls_config,  # type: ignore[arg-type]
                self._relay_addr,
                self._config.client_connection_timeout_ms,
            )

        self._socket.send_packet(
            NeonPacket.create(
                PacketType.CONNECT_REQUEST,
                self._next_seq(),
                0,
                1,
                ConnectRequest(PacketHeader.VERSION, self._name, self._session_id, 0),
            ),
            self._relay_addr,
        )

        timeout_s = self._config.client_connection_timeout_ms / 1000.0
        self._socket.set_blocking(True, timeout_s)
        try:
            deadline = time.monotonic() + timeout_s
            while True:
                if time.monotonic() >= deadline:
                    raise OSError("Connection timed out")
                result = self._socket.receive_packet()
                if result is None:
                    raise OSError("No response from relay")
                packet, _ = result
                if isinstance(packet.payload, ConnectAccept):
                    self._client_id = packet.payload.client_id
                    self._session_token = packet.payload.token
                    logger.info(
                        "Connected as client %d to session %d",
                        self._client_id,
                        self._session_id,
                    )
                    return
                if isinstance(packet.payload, ConnectDeny):
                    raise OSError(f"Connection denied: {packet.payload.reason}")
        finally:
            self._socket.set_blocking(False)

    def stop(self) -> None:
        """Send a disconnect notice and close the socket.

        Raises:
            RuntimeError: if the client is not running.
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

    def _do_stop(self) -> None:
        assert self._relay_addr is not None
        cid = self._client_id or 0
        try:
            self._socket.send_packet(
                NeonPacket.create(
                    PacketType.DISCONNECT_NOTICE,
                    self._next_seq(),
                    cid,
                    0,
                    DisconnectNotice(),
                ),
                self._relay_addr,
            )
        except OSError:
            pass
        self._socket.close()

    @property
    def is_running(self) -> bool:
        """``True`` while the client processing loop is active."""
        return self._state is _State.RUNNING

    @property
    def client_id(self) -> int | None:
        """The client ID assigned by the host, or ``None`` if not yet connected."""
        return self._client_id

    def local_address(self) -> tuple[str, int]:
        """Return the local ``(host, port)`` of the underlying UDP socket."""
        return self._socket.local_address()

    def __enter__(self) -> "NeonClient":
        return self

    def __exit__(self, *_: object) -> None:
        if self._state is _State.RUNNING:
            self.stop()

    # ------------------------------------------------------------------ #
    # Processing loop                                                      #
    # ------------------------------------------------------------------ #

    def run(self) -> None:
        """Drive the client processing loop until `stop` is called.

        Drains inbound packets and fires auto-pings on each iteration. Intended
        to run in a dedicated thread:

        ```python
        threading.Thread(target=client.run, daemon=True).start()
        ```
        """
        while self.is_running:
            try:
                self.process_packets()
                self._check_auto_ping()
                time.sleep(self._config.client_processing_loop_sleep_ms / 1000.0)
            except InterruptedError:
                break
            except OSError as exc:
                if not self._socket.is_closed:
                    logger.warning("IO error in client loop: %s", exc)
                break

    def process_packets(self) -> None:
        """Drain all currently buffered inbound packets and fire callbacks.

        Use this instead of `run` when integrating with an external game loop.

        Raises:
            OSError: if a socket error occurs while receiving.
        """
        while True:
            result = self._socket.receive_packet()
            if result is None:
                break
            packet, _ = result
            self._handle_packet(packet)

    def _handle_packet(self, packet: NeonPacket) -> None:
        header = packet.header
        cid = self._client_id
        if cid is not None and header.destination_id != 0 and header.destination_id != cid:
            return

        match packet.payload:
            case Pong():
                if self._pong_callback:
                    self._pong_callback(packet.payload)
            case SessionConfig():
                self._handle_session_config(packet.payload, header.sequence)
            case PacketTypeRegistry():
                if self._packet_type_registry_callback:
                    self._packet_type_registry_callback(packet.payload)
            case Ping():
                self._send_pong(packet.payload.timestamp)
            case DisconnectNotice():
                if self._disconnect_callback:
                    self._disconnect_callback(header.client_id)
            case _:
                if self._unhandled_packet_callback:
                    self._unhandled_packet_callback(header.packet_type, header.client_id)

    def _handle_session_config(self, sc: SessionConfig, sequence: int) -> None:
        if self._session_config_callback:
            self._session_config_callback(sc)
        assert self._relay_addr is not None
        cid = self._client_id or 0
        self._socket.send_packet(
            NeonPacket.create(
                PacketType.ACK,
                self._next_seq(),
                cid,
                0,
                Ack((sequence,)),
            ),
            self._relay_addr,
        )

    def _send_pong(self, timestamp: int) -> None:
        assert self._relay_addr is not None
        cid = self._client_id or 0
        self._socket.send_packet(
            NeonPacket.create(PacketType.PONG, self._next_seq(), cid, 1, Pong(timestamp)),
            self._relay_addr,
        )

    def _check_auto_ping(self) -> None:
        assert self._relay_addr is not None
        now = time.monotonic()
        if (now - self._last_ping) * 1000 >= self._config.client_ping_interval_ms:
            self._last_ping = now
            cid = self._client_id or 0
            ts = int(now * 1000)
            self._socket.send_packet(
                NeonPacket.create(PacketType.PING, self._next_seq(), cid, 1, Ping(ts)),
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
            dest_id: Destination client ID, or ``0`` to broadcast to all session peers.

        Raises:
            OSError: if the send fails.
        """
        from ._protocol import GamePacket
        assert self._relay_addr is not None
        cid = self._client_id or 0
        header = PacketHeader.create(packet_type, self._next_seq(), cid, dest_id)
        self._socket.send_packet(NeonPacket(header, GamePacket(payload)), self._relay_addr)

    def reconnect(self, max_attempts: int | None = None) -> bool:
        """Attempt to rejoin the last session using the stored session token.

        Uses exponential backoff between attempts. Creates a new socket if the
        existing one is closed.

        Args:
            max_attempts: Override the maximum number of attempts. Uses
                ``config.client_max_reconnect_attempts`` when ``None``.

        Returns:
            ``True`` if reconnection succeeded.
        """
        if self._session_token is None or self._session_id is None or self._client_id is None or self._relay_addr is None:
            logger.warning("Cannot reconnect: missing session state")
            return False

        attempts = max_attempts if max_attempts is not None else self._config.client_max_reconnect_attempts

        if self._socket.is_closed:
            try:
                self._socket = _NeonSocket(config=self._config)
                if self._config.is_dtls_enabled:
                    self._socket.perform_client_handshake(
                        self._config.dtls_config,  # type: ignore[arg-type]
                        self._relay_addr,
                        self._config.client_connection_timeout_ms,
                    )
            except OSError as exc:
                logger.warning("Failed to recreate socket for reconnect: %s", exc)
                return False

        delay_ms = self._config.client_initial_reconnect_delay_ms
        for attempt in range(attempts):
            if attempt > 0:
                time.sleep(delay_ms / 1000.0)
                delay_ms = min(delay_ms * 2, self._config.client_max_reconnect_delay_ms)
            if self._attempt_reconnect():
                return True
        return False

    def _attempt_reconnect(self) -> bool:
        assert self._relay_addr is not None
        assert self._client_id is not None
        assert self._session_id is not None
        assert self._session_token is not None

        try:
            self._socket.send_packet(
                NeonPacket.create(
                    PacketType.RECONNECT_REQUEST,
                    self._next_seq(),
                    self._client_id,
                    1,
                    ReconnectRequest(self._session_token, self._session_id, self._client_id),
                ),
                self._relay_addr,
            )

            timeout_s = self._config.client_connection_timeout_ms / 1000.0
            self._socket.set_blocking(True, timeout_s)
            try:
                deadline = time.monotonic() + timeout_s
                while True:
                    if time.monotonic() >= deadline:
                        return False
                    result = self._socket.receive_packet()
                    if result is None:
                        return False
                    packet, _ = result
                    if isinstance(packet.payload, ConnectAccept):
                        self._session_token = packet.payload.token
                        logger.info("Reconnected as client %d", self._client_id)
                        return True
                    if isinstance(packet.payload, ConnectDeny):
                        logger.warning("Reconnect denied: %s", packet.payload.reason)
                        return False
            finally:
                self._socket.set_blocking(False)
        except OSError as exc:
            logger.warning("Reconnect attempt failed: %s", exc)
            return False

    def _next_seq(self) -> int:
        with self._seq_lock:
            seq = self._next_sequence & 0xFFFF
            self._next_sequence += 1
            return seq


def _parse_address(address: str) -> tuple[str, int]:
    last_colon = address.rfind(":")
    return address[:last_colon], int(address[last_colon + 1 :])
