# Configuration Reference — Godot (GDScript)

All configuration is done through `NeonConfig`. Obtain defaults with `NeonConfig.new()` or customise by passing a Dictionary of overrides:

```gdscript
var cfg = NeonConfig.new({
    relay_port = 9001,
    host_session_tick_rate = 30,
    client_ping_interval_ms = 2000,
})
```

One `NeonConfig` instance can be shared across the relay, host, and client in the same process — **except when DTLS is enabled** (see [DTLS](#dtls) below).

For a description of what each setting does, see [CONFIGURATION.md](../CONFIGURATION.md).

## Socket / Buffer

| Field                  | Type   | Default | Description                                                                            |
| ---------------------- | ------ | ------- | -------------------------------------------------------------------------------------- |
| `buffer_size`          | `int`  | `65535` | UDP receive buffer size in bytes                                                       |
| `buffer_pool_init_size`| `int`  | `16`    | Reserved; stored but not used                                                          |
| `buffer_pool_max_size` | `int`  | `64`    | Reserved; stored but not used                                                          |
| `enforce_buffer_size`  | `bool` | `true`  | Drop datagrams that fill the receive buffer exactly, treating them as likely truncated |

## Relay

| Field                      | Type  | Default | Description                                                                     |
| -------------------------- | ----- | ------- | ------------------------------------------------------------------------------- |
| `relay_port`               | `int` | `7777`  | UDP port the relay binds to                                                     |
| `relay_cleanup_interval_ms`| `int` | `5000`  | How often stale sessions/connections are evicted (ms)                           |
| `relay_client_timeout_ms`  | `int` | `15000` | How long since last activity before a peer is considered stale (ms)             |
| `relay_main_loop_sleep_ms` | `int` | `1`     | `OS.delay_msec()` between relay loop iterations (ms)                            |
| `max_pending_connections`  | `int` | `64`    | Max clients simultaneously in the connection handshake queue                    |
| `max_rate_limiters`        | `int` | `1024`  | Max number of per-source rate limiter instances; cleared entirely when exceeded |
| `max_packets_per_second`   | `int` | `100`   | Per-source packet rate limit; excess packets are dropped                        |
| `max_clients_per_session`  | `int` | `32`    | Maximum connected clients per session (not counting the host)                   |

## Host

| Field                               | Type  | Default  | Description                                                            |
| ----------------------------------- | ----- | -------- | ---------------------------------------------------------------------- |
| `host_ack_timeout_ms`               | `int` | `2000`   | How long to wait for a `SESSION_CONFIG` ACK before retransmitting (ms) |
| `host_max_ack_retries`              | `int` | `5`      | Max `SESSION_CONFIG` retransmit attempts before giving up              |
| `host_session_token_timeout_ms`     | `int` | `300000` | Reconnect token validity window — 5 minutes (ms)                       |
| `host_graceful_shutdown_timeout_ms` | `int` | `3000`   | How long `stop()` waits for pending ACKs to drain (ms)                 |
| `host_processing_loop_sleep_ms`     | `int` | `10`     | `OS.delay_msec()` between host loop iterations (ms)                    |
| `host_session_tick_rate`            | `int` | `60`     | Tick rate advertised to clients in `SESSION_CONFIG`                    |
| `host_session_max_packet_size`      | `int` | `1200`   | Max game packet size advertised in `SESSION_CONFIG` (bytes)            |

## Client

| Field                             | Type  | Default | Description                                                  |
| --------------------------------- | ----- | ------- | ------------------------------------------------------------ |
| `client_connection_timeout_ms`    | `int` | `5000`  | Max time to wait for `CONNECT_ACCEPT` during handshake (ms)  |
| `client_ping_interval_ms`         | `int` | `5000`  | How often to send an auto-ping when the loop is running (ms) |
| `client_initial_reconnect_delay_ms`| `int`| `1000`  | Initial backoff delay between reconnect attempts (ms)        |
| `client_max_reconnect_delay_ms`   | `int` | `30000` | Maximum backoff delay between reconnect attempts (ms)        |
| `client_max_reconnect_attempts`   | `int` | `6`     | Max reconnect attempts before `reconnect()` returns `false`  |
| `client_processing_loop_sleep_ms` | `int` | `10`    | `OS.delay_msec()` between client loop iterations (ms)        |

## DTLS

| Field         | Type                    | Default           | Description                                                                                                                                                      |
| ------------- | ----------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dtls_config` | `DtlsConfig` or `null`  | `null` (disabled) | DTLS configuration for encryption. The relay requires a server config (with certificate + key); hosts and clients require a client config (with trust store).   |

The same `NeonConfig` must **not** be shared between the relay and a host/client when DTLS is enabled — the relay needs a server context (`TLSOptions.server`) and the host/client need a client context (`TLSOptions.client` or `TLSOptions.client_unsafe`).

Use `DtlsConfig.from_key_store(cert_path, key_path)` to create a relay server config from PEM files.
Use `DtlsConfig.with_trust_store(ca_path)` to create a production host/client config.
Use `DtlsConfig.insecure_trust_all()` for development and testing only.

## Reliable Packet Manager

| Field                      | Type  | Default | Description                                                 |
| -------------------------- | ----- | ------- | ----------------------------------------------------------- |
| `reliable_packet_timeout_ms`| `int`| `2000`  | Per-packet ACK timeout before retransmit (ms)               |
| `reliable_packet_max_retries`|`int`| `5`     | Max retransmit attempts before `on_delivery_failed` fires   |

## Examples

### High-frequency action game (20ms tick)

```gdscript
var cfg = NeonConfig.new({
    host_session_tick_rate       = 50,
    host_session_max_packet_size = 512,
    host_processing_loop_sleep_ms   = 5,
    client_processing_loop_sleep_ms = 5,
    client_ping_interval_ms      = 2000,
})
```

### Small LAN relay (trusted network)

```gdscript
var cfg = NeonConfig.new({
    relay_port               = 9999,
    max_packets_per_second   = 10000,
    max_clients_per_session  = 8,
    relay_client_timeout_ms  = 60000,
})
```

### Slow mobile connection with aggressive reconnect

```gdscript
var cfg = NeonConfig.new({
    client_connection_timeout_ms      = 10000,
    client_initial_reconnect_delay_ms = 500,
    client_max_reconnect_attempts     = 10,
    host_session_token_timeout_ms     = 600000,  # 10 minutes
    host_ack_timeout_ms               = 5000,
})
```

### DTLS-enabled relay and client

```gdscript
# Relay
var relay_cfg = NeonConfig.new({
    dtls_config = DtlsConfig.from_key_store("relay.crt", "relay.key"),
})
var relay = NeonRelay.new("0.0.0.0", relay_cfg)

# Client (dev — accepts any certificate)
var client_cfg = NeonConfig.new({
    dtls_config = DtlsConfig.insecure_trust_all(),
})
var client = NeonClient.new("player1", client_cfg)
```
