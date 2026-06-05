# Configuration Reference

All implementations share the same set of configurable values. Each implementation exposes these through its own idiomatic API — see the configuration reference in the relevant subdirectory for details.

## Socket / Buffer

| Setting                  | Default | Description                                                                            |
| ------------------------ | ------- | -------------------------------------------------------------------------------------- |
| Buffer size              | `65535` | UDP receive buffer size and internal receive buffer capacity in bytes                  |
| Buffer pool initial size | `16`    | Initial number of pre-allocated buffers in the pool                                    |
| Buffer pool max size     | `64`    | Maximum pool size before buffers are discarded on return                               |
| Enforce buffer size      | `true`  | Drop datagrams that fill the receive buffer exactly, treating them as likely truncated |

## Relay

| Setting                 | Default   | Description                                                                     |
| ----------------------- | --------- | ------------------------------------------------------------------------------- |
| Relay port              | `7777`    | UDP port the relay binds to                                                     |
| Cleanup interval        | `5000ms`  | How often stale sessions and connections are evicted                            |
| Client timeout          | `15000ms` | How long since last activity before a peer is considered stale                  |
| Main loop sleep         | `1ms`     | Sleep between relay processing loop iterations                                  |
| Max pending connections | `64`      | Max clients simultaneously in the connection handshake queue                    |
| Max rate limiters       | `1024`    | Max number of per-source rate limiter instances; cleared entirely when exceeded |
| Max packets per second  | `100`     | Per-source packet rate limit; excess packets are dropped                        |
| Max clients per session | `32`      | Maximum connected clients per session, not counting the host                    |

## Host

| Setting                   | Default    | Description                                                       |
| ------------------------- | ---------- | ----------------------------------------------------------------- |
| ACK timeout               | `2000ms`   | How long to wait for a `SESSION_CONFIG` ACK before retransmitting |
| Max ACK retries           | `5`        | Max `SESSION_CONFIG` retransmit attempts before giving up         |
| Session token timeout     | `300000ms` | Reconnect token validity window — 5 minutes                       |
| Graceful shutdown timeout | `3000ms`   | How long `stop()` waits for pending ACKs to drain                 |
| Processing loop sleep     | `10ms`     | Sleep between host processing loop iterations                     |
| Session tick rate         | `60`       | Tick rate advertised to clients in `SESSION_CONFIG`               |
| Session max packet size   | `1200`     | Max game packet size advertised in `SESSION_CONFIG` in bytes      |

## Client

| Setting                 | Default   | Description                                             |
| ----------------------- | --------- | ------------------------------------------------------- |
| Connection timeout      | `5000ms`  | Max time to wait for `CONNECT_ACCEPT` during handshake  |
| Ping interval           | `5000ms`  | How often to send an auto-ping when the loop is running |
| Initial reconnect delay | `1000ms`  | Initial backoff delay between reconnect attempts        |
| Max reconnect delay     | `30000ms` | Maximum backoff delay between reconnect attempts        |
| Max reconnect attempts  | `6`       | Max reconnect attempts before giving up                 |
| Processing loop sleep   | `10ms`    | Sleep between client processing loop iterations         |

## DTLS

| Setting      | Default           | Description                                                                                                                                                                                                                                                 |
| ------------ | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DTLS context | `null` (disabled) | When set, a DTLS handshake is performed before any Neon packets are exchanged. The relay requires a server context (with a private key); hosts and clients require a client context (with a trust store). These should not share the same context instance. |

## Reliable Packet Manager

| Setting                     | Default  | Description                                                 |
| --------------------------- | -------- | ----------------------------------------------------------- |
| Reliable packet timeout     | `2000ms` | Per-packet ACK timeout before retransmit                    |
| Reliable packet max retries | `5`      | Max retransmit attempts before delivery failure is reported |

## Common Configurations

### High-frequency action game (20ms tick)

- Host tick rate: `50`
- Max packet size: `512`
- Host and client loop sleep: `5ms`
- Ping interval: `2000ms`

### Small LAN relay (trusted network)

- Relay port: `9999`
- Max packets per second: `10000`
- Max clients per session: `8`
- Client timeout: `60000ms`

### Slow mobile connection with aggressive reconnect

- Connection timeout: `10000ms`
- Initial reconnect delay: `500ms`
- Max reconnect attempts: `10`
- Session token timeout: `600000ms` (10 minutes)
- ACK timeout: `5000ms`