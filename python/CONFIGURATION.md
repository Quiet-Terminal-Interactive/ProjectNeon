# Configuration Reference — Python

All configuration is done through `NeonConfig`. Obtain defaults with `NeonConfig()` or customise with keyword arguments:

```python
from qti_neon import NeonConfig

cfg = NeonConfig(
    relay_port=9001,
    host_session_tick_rate=30,
    client_ping_interval_ms=2000,
)
```

One `NeonConfig` instance can be shared across the relay, host, and client in the same process — **except when DTLS is enabled** (see [DTLS](#dtls) below).

For a description of what each setting does, see [CONFIGURATION.md](../CONFIGURATION.md).

## Socket / Buffer

| Parameter               | Default | Description                                                                            |
| ----------------------- | ------- | -------------------------------------------------------------------------------------- |
| `buffer_size`           | `65535` | UDP receive buffer size in bytes                                                       |
| `buffer_pool_init_size` | `16`    | (Reserved; stored but not used)                                                        |
| `buffer_pool_max_size`  | `64`    | (Reserved; stored but not used)                                                        |
| `enforce_buffer_size`   | `True`  | Drop datagrams that fill the receive buffer exactly, treating them as likely truncated |

## Relay

| Parameter                   | Default | Description                                                                     |
| --------------------------- | ------- | ------------------------------------------------------------------------------- |
| `relay_port`                | `7777`  | UDP port the relay binds to                                                     |
| `relay_cleanup_interval_ms` | `5000`  | How often stale sessions/connections are evicted (ms)                           |
| `relay_client_timeout_ms`   | `15000` | How long since last activity before a peer is considered stale (ms)             |
| `relay_main_loop_sleep_ms`  | `1`     | Sleep between relay processing loop iterations (ms)                             |
| `max_pending_connections`   | `64`    | Max clients simultaneously in the connection handshake queue                    |
| `max_rate_limiters`         | `1024`  | Max number of per-source rate limiter instances; cleared entirely when exceeded |
| `max_packets_per_second`    | `100`   | Per-source packet rate limit; excess packets are dropped                        |
| `max_clients_per_session`   | `32`    | Maximum connected clients per session (not counting the host)                   |

## Host

| Parameter                           | Default  | Description                                                            |
| ----------------------------------- | -------- | ---------------------------------------------------------------------- |
| `host_ack_timeout_ms`               | `2000`   | How long to wait for a `SESSION_CONFIG` ACK before retransmitting (ms) |
| `host_max_ack_retries`              | `5`      | Max `SESSION_CONFIG` retransmit attempts before giving up              |
| `host_session_token_timeout_ms`     | `300000` | Reconnect token validity window — 5 minutes (ms)                       |
| `host_graceful_shutdown_timeout_ms` | `3000`   | How long `stop()` waits for pending ACKs to drain (ms)                 |
| `host_processing_loop_sleep_ms`     | `10`     | Sleep between host processing loop iterations (ms)                     |
| `host_session_tick_rate`            | `60`     | Tick rate advertised to clients in `SESSION_CONFIG`                    |
| `host_session_max_packet_size`      | `1200`   | Max game packet size advertised in `SESSION_CONFIG` (bytes)            |

## Client

| Parameter                           | Default | Description                                                  |
| ----------------------------------- | ------- | ------------------------------------------------------------ |
| `client_connection_timeout_ms`      | `5000`  | Max time to wait for `CONNECT_ACCEPT` during handshake (ms)  |
| `client_ping_interval_ms`           | `5000`  | How often to send an auto-ping when the loop is running (ms) |
| `client_initial_reconnect_delay_ms` | `1000`  | Initial backoff delay between reconnect attempts (ms)        |
| `client_max_reconnect_delay_ms`     | `30000` | Maximum backoff delay between reconnect attempts (ms)        |
| `client_max_reconnect_attempts`     | `6`     | Max reconnect attempts before `reconnect()` returns `False`  |
| `client_processing_loop_sleep_ms`   | `10`    | Sleep between client processing loop iterations (ms)         |

## DTLS

| Parameter     | Default           | Description                                                                                                                                                                                                  |
| ------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `dtls_config` | `None` (disabled) | `DtlsConfig` for DTLS encryption. The relay requires a server config (with certificate + key); hosts and clients require a client config (with trust store). These must **not** share the same `NeonConfig`. |

Use `DtlsConfig.from_key_store(certfile, keyfile)` to create a relay server config. Use `DtlsConfig.with_trust_store(cafile)` to create a production host/client config. Use `DtlsConfig.insecure_trust_all()` for development and testing only.

Requires `pip install qti-neon[dtls]`.

## Reliable Packet Manager

| Parameter                     | Default | Description                                               |
| ----------------------------- | ------- | --------------------------------------------------------- |
| `reliable_packet_timeout_ms`  | `2000`  | Per-packet ACK timeout before retransmit (ms)             |
| `reliable_packet_max_retries` | `5`     | Max retransmit attempts before `on_delivery_failed` fires |

## Examples

### High-frequency action game (20ms tick)

```python
cfg = NeonConfig(
    host_session_tick_rate=50,
    host_session_max_packet_size=512,
    host_processing_loop_sleep_ms=5,
    client_processing_loop_sleep_ms=5,
    client_ping_interval_ms=2000,
)
```

### Small LAN relay (trusted network)

```python
cfg = NeonConfig(
    relay_port=9999,
    max_packets_per_second=10000,
    max_clients_per_session=8,
    relay_client_timeout_ms=60000,
)
```

### Slow mobile connection with aggressive reconnect

```python
cfg = NeonConfig(
    client_connection_timeout_ms=10000,
    client_initial_reconnect_delay_ms=500,
    client_max_reconnect_attempts=10,
    host_session_token_timeout_ms=600000,
    host_ack_timeout_ms=5000,
)
```
