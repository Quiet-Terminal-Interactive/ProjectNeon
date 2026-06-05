# Architecture — Python

This document covers the Python implementation internals. For the protocol topology, session lifecycle, and handshake sequences see [ARCHITECTURE.md](../ARCHITECTURE.md).

## Component Responsibilities

### NeonRelay

Routes packets by destination ID. Handles `HOST_REGISTER`, `CONNECT_REQUEST`, `CONNECT_ACCEPT`, `RECONNECT_REQUEST`, and `DISCONNECT_NOTICE` directly; all other valid packets are routed opaquely.

- `_SessionManager` — stores host and client address mappings per session
- `_RateLimiter` — per-source token-bucket rate enforcement
- `pending_connections` — dict of in-flight connect requests keyed by source address
- `pending_by_session` — FIFO queue of pending client addresses per session
- `pending_reconnects` — dict of pending reconnect entries keyed by `"sessionId:clientId"`

### NeonHost

- Assigns client IDs via an atomically incremented counter (starts at 2; host is 1)
- Reserves client names via a dict with lock-guarded `setdefault`
- Generates cryptographically random session tokens via `secrets.token_bytes(8)`
- Sends `SESSION_CONFIG` reliably, tracked by `_AckStateMachine`
- Maintains `connected_clients` and `disconnected_clients` dicts guarded by `threading.Lock`

### NeonClient

- Performs a blocking handshake loop using `socket.settimeout()`
- Stores session token, client ID, and session ID as `threading.Lock`-guarded fields
- Drives the packet loop via `run()` or application-controlled `process_packets()`
- Recreates the underlying `_NeonSocket` on reconnect if the original is closed

## Threading Model

All three components are designed for a single dedicated thread:

```python
import threading

threading.Thread(target=relay.start_and_run, daemon=True).start()
threading.Thread(target=host.start_and_run, daemon=True).start()
threading.Thread(target=client.run, daemon=True).start()
```

The processing loops sleep between iterations (`relay_main_loop_sleep_ms`, `host_processing_loop_sleep_ms`, `client_processing_loop_sleep_ms`). The UDP socket is used in non-blocking mode (`setblocking(False)`) during the processing loop; blocking semantics during handshakes are emulated with `socket.settimeout()`.

Shared state visible to the application (connected clients map, session token, client ID) is guarded by `threading.Lock`. Internal relay state (`pending_connections`, `pending_by_session`, `pending_reconnects`) is only accessed from the single relay processing thread and is not locked.

## DTLS Implementation

DTLS is relay-terminated. Each peer (`NeonHost` or `NeonClient`) maintains a separate DTLS session with the relay via `_NeonSocket`.

```
NeonClient ──── DTLS ────► NeonRelay ◄──── DTLS ──── NeonHost
```

When DTLS is enabled (`NeonConfig.dtls_config is not None`):

- **Relay** calls `_NeonSocket.enable_server_dtls(cfg)` on startup, which wraps the raw UDP socket in a server-side `dtls.DtlsSocket`. Inbound DTLS handshakes from new peers are handled automatically.
- **Host / Client** calls `_NeonSocket.perform_client_handshake(cfg, relay_addr, timeout_ms)` during startup, performing a blocking DTLS client-side handshake before sending `HOST_REGISTER` or `CONNECT_REQUEST`.
- After the handshake, `_NeonSocket.send()` and `_NeonSocket.receive()` are transparent to the caller.

DTLS requires the optional `dtls` package: `pip install qti-neon[dtls]`.

`DtlsConfig` provides factory methods for constructing config objects:

- `DtlsConfig.from_key_store(certfile, keyfile)` — relay server config (requires certificate + key)
- `DtlsConfig.with_trust_store(cafile)` — production host/client config (trusts relay certificate)
- `DtlsConfig.insecure_trust_all()` — development/test config (accepts any certificate)

## Packet Processing Loop

```python
while self._running:
    self._process_packets()        # drain inbound, handle each
    self._check_pending_acks()     # host only — AckStateMachine
    self._perform_cleanup()        # relay only — stale session eviction
    self._check_auto_ping()        # client only
    time.sleep(loop_sleep_ms / 1000)
```

## Key Classes

| Class                   | Module          | Role                                                  |
| ----------------------- | --------------- | ----------------------------------------------------- |
| `NeonRelay`             | `relay`         | Relay processing loop and packet routing              |
| `NeonHost`              | `host`          | Host processing loop and client lifecycle             |
| `NeonClient`            | `client`        | Client processing loop and reconnect                  |
| `_NeonSocket`           | `_socket`       | UDP socket wrapper with optional DTLS (internal)      |
| `NeonConfig`            | `_config`       | Frozen configuration dataclass                        |
| `DtlsConfig`            | `dtls`          | DTLS configuration with factory methods               |
| `_SessionManager`       | `_session`      | Peer address map per session (relay-internal)         |
| `ReliablePacketManager` | `_reliable`     | Opt-in reliable delivery for game packets             |
| `_AckStateMachine`      | `_ack`          | Reliable delivery for `SESSION_CONFIG`                |
| `_RateLimiter`          | `_rate_limiter` | Per-source token-bucket rate limiter (relay-internal) |
| `GamePacketRegistry`    | `_registry`     | Registry of application-defined game packet types     |
| `PacketType`            | `_protocol`     | Packet type byte constants                            |
| `NeonPacket`            | `_protocol`     | Packet parsing and serialisation                      |
