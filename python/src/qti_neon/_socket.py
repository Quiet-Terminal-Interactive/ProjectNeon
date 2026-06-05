"""Internal UDP socket wrapper with optional DTLS encryption."""

from __future__ import annotations

import socket
import logging
from typing import TYPE_CHECKING

from ._protocol import NeonPacket, HEADER_SIZE

if TYPE_CHECKING:
    from ._config import NeonConfig
    from .dtls import DtlsConfig

logger = logging.getLogger(__name__)

# DTLS 1.2 content-type bytes (0x14–0x17) and DTLS 1.3 unified header (0x20–0x3F)
_DTLS_12_RANGE = range(0x14, 0x18)
_DTLS_13_RANGE = range(0x20, 0x40)


def _is_dtls_record(first_byte: int) -> bool:
    return first_byte in _DTLS_12_RANGE or first_byte in _DTLS_13_RANGE


# ---------------------------------------------------------------------------
# Optional pyopenssl imports — only resolved when DTLS is first used
# ---------------------------------------------------------------------------

def _get_openssl():
    """Return (ffi, lib) from pyopenssl, raising RuntimeError if unavailable."""
    try:
        from OpenSSL._util import ffi as _ffi, lib as _lib
        return _ffi, _lib
    except ImportError as exc:
        raise RuntimeError(
            "DTLS requires pyopenssl: pip install qti-neon[dtls]"
        ) from exc


# ---------------------------------------------------------------------------
# _DtlsSession — one DTLS peer session driven via OpenSSL memory BIOs
# ---------------------------------------------------------------------------

class _DtlsSession:
    """Per-peer DTLS session backed by OpenSSL memory BIOs.

    Mirrors the architecture of Java's ``DtlsSession``: drives the SSL state
    machine by feeding raw bytes in through the read BIO and draining encrypted
    output from the write BIO, so no real socket is attached to the SSL object.
    """

    __slots__ = ("_ffi", "_lib", "_ssl", "_ready")

    def __init__(self, ctx, server_side: bool) -> None:
        ffi, lib = _get_openssl()
        self._ffi = ffi
        self._lib = lib

        ssl = lib.SSL_new(ctx)
        if ssl == ffi.NULL:
            raise RuntimeError("SSL_new failed")
        self._ssl = ssl

        bio_in = lib.BIO_new(lib.BIO_s_mem())
        bio_out = lib.BIO_new(lib.BIO_s_mem())
        if bio_in == ffi.NULL or bio_out == ffi.NULL:
            lib.SSL_free(ssl)
            raise RuntimeError("BIO_new failed")

        # SSL takes ownership of both BIOs after SSL_set_bio
        lib.SSL_set_bio(ssl, bio_in, bio_out)

        if server_side:
            lib.SSL_set_accept_state(ssl)
        else:
            lib.SSL_set_connect_state(ssl)

        self._ready = False

    def __del__(self) -> None:
        try:
            lib = self._lib
            ffi = self._ffi
            if self._ssl != ffi.NULL:
                lib.SSL_free(self._ssl)
                self._ssl = ffi.NULL
        except Exception:
            pass

    @property
    def is_ready(self) -> bool:
        """``True`` once the DTLS handshake has completed."""
        return self._ready

    def initiate(self) -> bytes:
        """Kick off the DTLS handshake (call once for the client side).

        Returns the initial ClientHello bytes to send to the peer.
        """
        self._lib.SSL_do_handshake(self._ssl)
        return self._drain_outbound()

    def receive(self, data: bytes) -> tuple[bytes | None, bytes]:
        """Feed inbound DTLS bytes from the network.

        Returns ``(plaintext_or_None, outbound_bytes_to_send)``.  The caller
        must send *outbound_bytes_to_send* to the peer if non-empty.
        """
        ffi, lib = self._ffi, self._lib

        # Feed data into the inbound read-BIO
        bio_in = lib.SSL_get_rbio(self._ssl)
        lib.BIO_write(bio_in, data, len(data))

        plaintext: bytes | None = None

        if not self._ready:
            lib.SSL_do_handshake(self._ssl)
            if lib.SSL_is_init_finished(self._ssl):
                self._ready = True

        if self._ready:
            buf = ffi.new("unsigned char[]", 65536)
            n = lib.SSL_read(self._ssl, buf, 65536)
            if n > 0:
                plaintext = bytes(ffi.buffer(buf, n))

        return plaintext, self._drain_outbound()

    def wrap(self, plaintext: bytes) -> bytes:
        """Encrypt *plaintext* and return the ciphertext bytes to send."""
        self._lib.SSL_write(self._ssl, plaintext, len(plaintext))
        return self._drain_outbound()

    def _drain_outbound(self) -> bytes:
        ffi, lib = self._ffi, self._lib
        bio_out = lib.SSL_get_wbio(self._ssl)
        buf = ffi.new("unsigned char[]", 65536)
        n = lib.BIO_read(bio_out, buf, 65536)
        return bytes(ffi.buffer(buf, n)) if n > 0 else b""


# ---------------------------------------------------------------------------
# _DtlsCtx — owns an SSL_CTX* and frees it on deletion
# ---------------------------------------------------------------------------

class _DtlsCtx:
    __slots__ = ("_ffi", "_lib", "_ctx")

    def __init__(self, dtls_cfg: "DtlsConfig") -> None:
        ffi, lib = _get_openssl()
        self._ffi = ffi
        self._lib = lib
        self._ctx = dtls_cfg._build_ctx()

    def __del__(self) -> None:
        try:
            if self._ctx != self._ffi.NULL:
                self._lib.SSL_CTX_free(self._ctx)
                self._ctx = self._ffi.NULL
        except Exception:
            pass

    def new_session(self, server_side: bool) -> _DtlsSession:
        return _DtlsSession(self._ctx, server_side)


# ---------------------------------------------------------------------------
# _NeonSocket
# ---------------------------------------------------------------------------

class _NeonSocket:
    """Non-blocking UDP socket with optional per-peer DTLS encryption.

    In plaintext mode it wraps a standard `socket.socket`. When DTLS is
    enabled, each peer address gets a `_DtlsSession` driven by memory BIOs —
    the same architecture as Java's ``NeonSocket`` + ``DtlsSession``.
    """

    def __init__(
        self,
        bind_address: tuple[str, int] | None = None,
        config: "NeonConfig | None" = None,
    ) -> None:
        from ._config import NeonConfig as _NeonConfig
        cfg = config or _NeonConfig()
        self._buffer_size: int = cfg.buffer_size
        self._enforce: bool = cfg.enforce_buffer_size

        raw = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            raw.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, cfg.buffer_size)
            if bind_address:
                raw.bind(bind_address)
            raw.setblocking(False)
        except Exception:
            raw.close()
            raise
        self._raw: socket.socket = raw

        self._dtls_ctx: _DtlsCtx | None = None
        self._dtls_sessions: dict[tuple[str, int], _DtlsSession] = {}
        self._is_dtls_server: bool = False
        self._closed: bool = False

    # ------------------------------------------------------------------ #
    # DTLS setup                                                           #
    # ------------------------------------------------------------------ #

    def enable_server_dtls(self, dtls_cfg: "DtlsConfig") -> None:
        """Configure server-side DTLS.

        Called by the relay before entering its processing loop. Inbound DTLS
        handshakes from new peers are initiated automatically when a DTLS
        record arrives from an unknown address.

        Raises:
            RuntimeError: if pyopenssl is not installed or DTLS unavailable.
        """
        self._dtls_ctx = _DtlsCtx(dtls_cfg)
        self._is_dtls_server = True

    def perform_client_handshake(
        self,
        dtls_cfg: "DtlsConfig",
        peer: tuple[str, int],
        timeout_ms: int,
    ) -> None:
        """Perform a DTLS client-side handshake with *peer*.

        Blocks until the handshake completes or *timeout_ms* elapses.

        Raises:
            RuntimeError: if pyopenssl is not installed.
            OSError: if the handshake fails or times out.
        """
        import time
        self._dtls_ctx = _DtlsCtx(dtls_cfg)
        session = self._dtls_ctx.new_session(server_side=False)
        self._dtls_sessions[peer] = session

        # Send initial ClientHello
        initial = session.initiate()
        if initial:
            self._raw.sendto(initial, peer)

        deadline = time.monotonic() + timeout_ms / 1000.0
        self._raw.settimeout(0.1)
        try:
            while not session.is_ready:
                if time.monotonic() >= deadline:
                    raise OSError("DTLS handshake timed out")
                try:
                    data, addr = self._raw.recvfrom(self._buffer_size)
                    if addr == peer:
                        _, outbound = session.receive(data)
                        if outbound:
                            self._raw.sendto(outbound, peer)
                except socket.timeout:
                    pass
        finally:
            self._raw.setblocking(False)

    def remove_dtls_session(self, address: tuple[str, int]) -> None:
        """Discard the DTLS session for *address* when a peer disconnects."""
        self._dtls_sessions.pop(address, None)

    # ------------------------------------------------------------------ #
    # I/O                                                                  #
    # ------------------------------------------------------------------ #

    def send(self, data: bytes, address: tuple[str, int]) -> None:
        """Send *data* to *address*, encrypting via DTLS if a session exists."""
        session = self._dtls_sessions.get(address)
        if session is not None and session.is_ready:
            encrypted = session.wrap(data)
            if encrypted:
                self._raw.sendto(encrypted, address)
            return
        self._raw.sendto(data, address)

    def send_packet(self, packet: NeonPacket, address: tuple[str, int]) -> None:
        """Serialise *packet* and send it to *address*."""
        self.send(packet.to_bytes(), address)

    def receive(self) -> tuple[bytes, tuple[str, int]] | None:
        """Return the next available ``(data, address)`` pair, or ``None``.

        Non-blocking — returns ``None`` immediately when no datagram is
        pending. Handles DTLS transparently: handshake messages drive the
        per-peer session; application data is returned as plaintext.
        """
        try:
            data, addr = self._raw.recvfrom(self._buffer_size)
        except (BlockingIOError, socket.timeout, OSError):
            return None

        if not data:
            return None

        if self._enforce and len(data) == self._buffer_size:
            return None

        # Route DTLS records through session management
        if _is_dtls_record(data[0]):
            return self._handle_dtls(data, addr)

        if len(data) < HEADER_SIZE:
            return None
        return bytes(data), addr

    def _handle_dtls(
        self, data: bytes, addr: tuple[str, int]
    ) -> tuple[bytes, tuple[str, int]] | None:
        session = self._dtls_sessions.get(addr)

        if session is None:
            if not self._is_dtls_server or self._dtls_ctx is None:
                return None
            # New peer — start a server-side DTLS session
            try:
                session = self._dtls_ctx.new_session(server_side=True)
                self._dtls_sessions[addr] = session
            except Exception as exc:
                logger.warning("Failed to create DTLS session for %s: %s", addr, exc)
                return None

        try:
            plaintext, outbound = session.receive(data)
        except Exception as exc:
            logger.warning("DTLS session error from %s: %s", addr, exc)
            self._dtls_sessions.pop(addr, None)
            return None

        if outbound:
            try:
                self._raw.sendto(outbound, addr)
            except OSError:
                pass

        if plaintext is None or len(plaintext) < HEADER_SIZE:
            return None
        return plaintext, addr

    def receive_packet(self) -> tuple[NeonPacket, tuple[str, int]] | None:
        """Return the next parsed ``(NeonPacket, address)`` pair, or ``None``."""
        result = self.receive()
        if result is None:
            return None
        data, addr = result
        try:
            return NeonPacket.from_bytes(data), addr
        except (ValueError, Exception):
            return None

    def set_blocking(self, blocking: bool, timeout_s: float = 0.0) -> None:
        """Switch between non-blocking and blocking-with-timeout mode."""
        if blocking:
            self._raw.settimeout(timeout_s if timeout_s > 0 else None)
        else:
            self._raw.setblocking(False)

    # ------------------------------------------------------------------ #
    # Metadata                                                             #
    # ------------------------------------------------------------------ #

    def local_address(self) -> tuple[str, int]:
        """Return the bound local ``(host, port)``."""
        addr = self._raw.getsockname()
        return addr[0], addr[1]

    @property
    def is_closed(self) -> bool:
        """``True`` after `close` has been called."""
        return self._closed

    def close(self) -> None:
        """Close the underlying socket."""
        if not self._closed:
            self._closed = True
            self._dtls_sessions.clear()
            try:
                self._raw.close()
            except OSError:
                pass
