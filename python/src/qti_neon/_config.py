"""Immutable configuration dataclass for all Neon components."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .dtls import DtlsConfig


@dataclass(frozen=True, kw_only=True)
class NeonConfig:
    """Immutable configuration shared across `NeonRelay`, `NeonHost`, and `NeonClient`.

    Obtain defaults with ``NeonConfig()`` or customise with keyword arguments:

    ```python
    cfg = NeonConfig(relay_port=9001, host_session_tick_rate=30)
    ```

    One instance can be shared across components in the same process, **except**
    when DTLS is enabled — the relay requires a server `DtlsConfig` and
    hosts/clients require a client `DtlsConfig`. Create separate instances when
    using DTLS.
    """

    # --- Socket / Buffer ---

    buffer_size: int = 65535
    """UDP receive buffer size in bytes."""
    buffer_pool_init_size: int = 16
    """Reserved; stored but not used."""
    buffer_pool_max_size: int = 64
    """Reserved; stored but not used."""
    enforce_buffer_size: bool = True
    """Drop datagrams that exactly fill the receive buffer (likely truncated)."""

    # --- Relay ---

    relay_port: int = 7777
    """UDP port the relay binds to."""
    relay_cleanup_interval_ms: int = 5000
    """How often stale sessions/connections are evicted (ms)."""
    relay_client_timeout_ms: int = 15000
    """How long since last activity before a peer is considered stale (ms)."""
    relay_main_loop_sleep_ms: int = 1
    """Sleep between relay processing loop iterations (ms)."""
    max_pending_connections: int = 64
    """Max clients simultaneously in the connection handshake queue."""
    max_rate_limiters: int = 1024
    """Max number of per-source rate limiter instances; cleared entirely when exceeded."""
    max_packets_per_second: int = 100
    """Per-source packet rate limit; excess packets are silently dropped."""
    max_clients_per_session: int = 32
    """Maximum connected clients per session, not counting the host."""

    # --- Host ---

    host_ack_timeout_ms: int = 2000
    """How long to wait for a ``SESSION_CONFIG`` ACK before retransmitting (ms)."""
    host_max_ack_retries: int = 5
    """Max ``SESSION_CONFIG`` retransmit attempts before giving up."""
    host_session_token_timeout_ms: int = 300_000
    """Reconnect token validity window — 5 minutes (ms)."""
    host_graceful_shutdown_timeout_ms: int = 3000
    """How long ``stop()`` waits for pending ACKs to drain (ms)."""
    host_processing_loop_sleep_ms: int = 10
    """Sleep between host processing loop iterations (ms)."""
    host_session_tick_rate: int = 60
    """Tick rate advertised to clients in ``SESSION_CONFIG``."""
    host_session_max_packet_size: int = 1200
    """Max game packet size advertised in ``SESSION_CONFIG`` (bytes)."""

    # --- Client ---

    client_connection_timeout_ms: int = 5000
    """Max time to wait for ``CONNECT_ACCEPT`` during handshake (ms)."""
    client_ping_interval_ms: int = 5000
    """How often to send an auto-ping when the processing loop is running (ms)."""
    client_initial_reconnect_delay_ms: int = 1000
    """Initial backoff delay between reconnect attempts (ms)."""
    client_max_reconnect_delay_ms: int = 30_000
    """Maximum backoff delay between reconnect attempts (ms)."""
    client_max_reconnect_attempts: int = 6
    """Max reconnect attempts before ``reconnect()`` returns ``False``."""
    client_processing_loop_sleep_ms: int = 10
    """Sleep between client processing loop iterations (ms)."""

    # --- Reliable Packet Manager ---

    reliable_packet_timeout_ms: int = 2000
    """Per-packet ACK timeout before retransmit (ms)."""
    reliable_packet_max_retries: int = 5
    """Max retransmit attempts before ``on_delivery_failed`` fires."""

    # --- DTLS ---

    dtls_config: "DtlsConfig | None" = field(default=None, compare=False)
    """DTLS configuration, or ``None`` to disable encryption.

    The relay must use a server-side `DtlsConfig` (with certificate + key).
    Hosts and clients must use a client-side `DtlsConfig` (with trust store).
    Do **not** share a single `NeonConfig` between the relay and a host/client
    when DTLS is enabled.
    """

    @property
    def is_dtls_enabled(self) -> bool:
        """``True`` if a `DtlsConfig` has been set."""
        return self.dtls_config is not None

    def __post_init__(self) -> None:
        def require_positive(v: int, name: str) -> None:
            if v <= 0:
                raise ValueError(f"{name} must be > 0")

        def require_non_negative(v: int, name: str) -> None:
            if v < 0:
                raise ValueError(f"{name} must be >= 0")

        if self.buffer_size < 8:
            raise ValueError("buffer_size must be >= 8")
        require_positive(self.buffer_pool_init_size, "buffer_pool_init_size")
        if self.buffer_pool_max_size < self.buffer_pool_init_size:
            raise ValueError("buffer_pool_max_size must be >= buffer_pool_init_size")

        require_non_negative(self.relay_cleanup_interval_ms, "relay_cleanup_interval_ms")
        require_non_negative(self.relay_client_timeout_ms, "relay_client_timeout_ms")
        require_non_negative(self.relay_main_loop_sleep_ms, "relay_main_loop_sleep_ms")
        require_positive(self.max_pending_connections, "max_pending_connections")
        require_positive(self.max_rate_limiters, "max_rate_limiters")
        require_positive(self.max_packets_per_second, "max_packets_per_second")
        require_positive(self.max_clients_per_session, "max_clients_per_session")

        require_non_negative(self.host_ack_timeout_ms, "host_ack_timeout_ms")
        require_non_negative(self.host_max_ack_retries, "host_max_ack_retries")
        require_non_negative(self.host_session_token_timeout_ms, "host_session_token_timeout_ms")
        require_non_negative(self.host_graceful_shutdown_timeout_ms, "host_graceful_shutdown_timeout_ms")
        require_non_negative(self.host_processing_loop_sleep_ms, "host_processing_loop_sleep_ms")
        if self.host_session_tick_rate <= 0:
            raise ValueError("host_session_tick_rate must be > 0")
        if self.host_session_max_packet_size <= 0:
            raise ValueError("host_session_max_packet_size must be > 0")

        require_non_negative(self.client_connection_timeout_ms, "client_connection_timeout_ms")
        require_non_negative(self.client_ping_interval_ms, "client_ping_interval_ms")
        require_non_negative(self.client_initial_reconnect_delay_ms, "client_initial_reconnect_delay_ms")
        require_non_negative(self.client_max_reconnect_delay_ms, "client_max_reconnect_delay_ms")
        require_non_negative(self.client_max_reconnect_attempts, "client_max_reconnect_attempts")
        require_non_negative(self.client_processing_loop_sleep_ms, "client_processing_loop_sleep_ms")

        require_non_negative(self.reliable_packet_timeout_ms, "reliable_packet_timeout_ms")
        require_non_negative(self.reliable_packet_max_retries, "reliable_packet_max_retries")
