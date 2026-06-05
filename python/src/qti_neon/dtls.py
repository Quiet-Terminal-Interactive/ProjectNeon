"""DTLS configuration for Neon components.

DTLS support requires the optional ``pyopenssl`` package::

    pip install qti-neon[dtls]

Typical setup:

- **Relay**: `DtlsConfig.from_key_store` — has the certificate and private key.
- **Host / Client** (production): `DtlsConfig.with_trust_store` — trusts the
  relay certificate.
- **Host / Client** (development): `DtlsConfig.insecure_trust_all` — accepts
  any certificate. **Never use in production.**

Pass the resulting `DtlsConfig` to `NeonConfig` via the ``dtls_config``
parameter. The relay and client/host configs must be separate `NeonConfig`
instances — the relay uses server mode and peers use client mode.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class DtlsConfig:
    """DTLS configuration object.

    Do not construct directly — use the factory methods.
    """

    certfile: str | None
    """Path to the PEM certificate file (relay/server mode)."""
    keyfile: str | None
    """Path to the PEM private key file (relay/server mode)."""
    cafile: str | None
    """Path to the CA certificate file used to verify the relay (client mode)."""
    server_side: bool
    """``True`` for relay (server) mode, ``False`` for host/client mode."""
    insecure: bool
    """Skip certificate verification. **Development only.**"""

    # ------------------------------------------------------------------ #
    # Factory methods                                                      #
    # ------------------------------------------------------------------ #

    @classmethod
    def from_key_store(cls, certfile: str, keyfile: str) -> "DtlsConfig":
        """Create a relay (server-side) DTLS config.

        Args:
            certfile: Path to the PEM-encoded server certificate.
            keyfile: Path to the PEM-encoded private key.
        """
        return cls(certfile=certfile, keyfile=keyfile, cafile=None,
                   server_side=True, insecure=False)

    @classmethod
    def with_trust_store(cls, cafile: str) -> "DtlsConfig":
        """Create a host/client DTLS config that trusts a specific CA certificate.

        Use in production to pin the relay's certificate.

        Args:
            cafile: Path to the PEM-encoded CA certificate to trust.
        """
        return cls(certfile=None, keyfile=None, cafile=cafile,
                   server_side=False, insecure=False)

    @classmethod
    def insecure_trust_all(cls) -> "DtlsConfig":
        """Create a host/client DTLS config that accepts **any** certificate.

        **Never use this in production.** For local development and integration
        tests only.
        """
        return cls(certfile=None, keyfile=None, cafile=None,
                   server_side=False, insecure=True)

    # ------------------------------------------------------------------ #
    # Internal — used by _NeonSocket                                       #
    # ------------------------------------------------------------------ #

    def _build_ctx(self):
        """Return a raw ``SSL_CTX*`` configured for DTLS.

        The caller is responsible for freeing it with ``SSL_CTX_free``.

        Raises:
            RuntimeError: if pyopenssl is not installed or DTLS is unavailable.
        """
        try:
            from OpenSSL._util import ffi as _ffi, lib as _lib
        except ImportError as exc:
            raise RuntimeError(
                "DTLS requires pyopenssl: pip install qti-neon[dtls]"
            ) from exc

        method_fn = (
            getattr(_lib, "DTLS_server_method", None)
            or getattr(_lib, "DTLS_method", None)
        ) if self.server_side else (
            getattr(_lib, "DTLS_client_method", None)
            or getattr(_lib, "DTLS_method", None)
        )
        if method_fn is None:
            raise RuntimeError(
                "OpenSSL DTLS not available — ensure OpenSSL >= 1.0.2 is installed"
            )

        ctx = _lib.SSL_CTX_new(method_fn())
        if ctx == _ffi.NULL:
            raise RuntimeError("SSL_CTX_new failed")

        _lib.SSL_CTX_set_read_ahead(ctx, 1)

        if self.server_side:
            if self.certfile:
                if _lib.SSL_CTX_use_certificate_file(
                    ctx, self.certfile.encode(), _lib.SSL_FILETYPE_PEM
                ) != 1:
                    _lib.SSL_CTX_free(ctx)
                    raise RuntimeError(f"Failed to load certificate: {self.certfile}")
            if self.keyfile:
                if _lib.SSL_CTX_use_PrivateKey_file(
                    ctx, self.keyfile.encode(), _lib.SSL_FILETYPE_PEM
                ) != 1:
                    _lib.SSL_CTX_free(ctx)
                    raise RuntimeError(f"Failed to load private key: {self.keyfile}")
        else:
            if self.insecure:
                _lib.SSL_CTX_set_verify(ctx, _lib.SSL_VERIFY_NONE, _ffi.NULL)
            elif self.cafile:
                if _lib.SSL_CTX_load_verify_locations(
                    ctx, self.cafile.encode(), _ffi.NULL
                ) != 1:
                    _lib.SSL_CTX_free(ctx)
                    raise RuntimeError(f"Failed to load CA file: {self.cafile}")
                _lib.SSL_CTX_set_verify(ctx, _lib.SSL_VERIFY_PEER, _ffi.NULL)

        return ctx
