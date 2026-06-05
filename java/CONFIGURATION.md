# Configuration Reference — Java

All configuration is done through `NeonConfig`. Obtain defaults with `NeonConfig.defaults()` or customise with `NeonConfig.builder()...build()`.

One `NeonConfig` instance can be shared across the relay, host, and client in the same JVM — except when DTLS is enabled (see [DTLS](#dtls) below).

For a description of what each setting does, see [CONFIGURATION.md](../CONFIGURATION.md).

## Socket / Buffer

| Builder method               | Default | Description                                                                            |
| ---------------------------- | ------- | -------------------------------------------------------------------------------------- |
| `bufferSize(int)`            | `65535` | UDP receive buffer size and internal receive buffer capacity in bytes                  |
| `bufferPoolInitSize(int)`    | `16`    | Initial number of pre-allocated `ByteBuffer`s in the pool                              |
| `bufferPoolMaxSize(int)`     | `64`    | Maximum pool size before buffers are discarded on return                               |
| `enforceBufferSize(boolean)` | `true`  | Drop datagrams that fill the receive buffer exactly, treating them as likely truncated |

## Relay

| Builder method                | Default | Description                                                                     |
| ----------------------------- | ------- | ------------------------------------------------------------------------------- |
| `relayPort(int)`              | `7777`  | UDP port the relay binds to                                                     |
| `relaySocketTimeoutMs(int)`   | `100`   | Reserved; currently stored but not used by the runtime                          |
| `relayCleanupIntervalMs(int)` | `5000`  | How often stale sessions/connections are evicted (ms)                           |
| `relayClientTimeoutMs(int)`   | `15000` | How long since last activity before a peer is considered stale (ms)             |
| `relayMainLoopSleepMs(int)`   | `1`     | Sleep between relay processing loop iterations (ms)                             |
| `maxPendingConnections(int)`  | `64`    | Max clients simultaneously in the connection handshake queue                    |
| `maxRateLimiters(int)`        | `1024`  | Max number of per-source rate limiter instances; cleared entirely when exceeded |
| `maxPacketsPerSecond(int)`    | `100`   | Per-source packet rate limit; excess packets are dropped and logged at `FINE`   |
| `maxClientsPerSession(int)`   | `32`    | Maximum connected clients per session (not counting the host)                   |
| `maxTotalConnections(int)`    | `1024`  | Reserved; currently stored but not enforced by the runtime                      |

## Host

| Builder method                       | Default  | Description                                                            |
| ------------------------------------ | -------- | ---------------------------------------------------------------------- |
| `hostSocketTimeoutMs(int)`           | `100`    | Reserved; currently stored but not used by the runtime                 |
| `hostAckTimeoutMs(int)`              | `2000`   | How long to wait for a `SESSION_CONFIG` ACK before retransmitting (ms) |
| `hostMaxAckRetries(int)`             | `5`      | Max `SESSION_CONFIG` retransmit attempts before giving up              |
| `hostSessionTokenTimeoutMs(int)`     | `300000` | Reconnect token validity window — 5 minutes (ms)                       |
| `hostGracefulShutdownTimeoutMs(int)` | `3000`   | How long `stop()` waits for pending ACKs to drain (ms)                 |
| `hostProcessingLoopSleepMs(int)`     | `10`     | Sleep between host processing loop iterations (ms)                     |
| `hostSessionTickRate(short)`         | `60`     | Tick rate advertised to clients in `SESSION_CONFIG`                    |
| `hostSessionMaxPacketSize(short)`    | `1200`   | Max game packet size advertised in `SESSION_CONFIG` (bytes)            |

## Client

| Builder method                       | Default | Description                                                  |
| ------------------------------------ | ------- | ------------------------------------------------------------ |
| `clientSocketTimeoutMs(int)`         | `100`   | Reserved; currently stored but not used by the runtime       |
| `clientConnectionTimeoutMs(int)`     | `5000`  | Max time to wait for `CONNECT_ACCEPT` during handshake (ms)  |
| `clientPingIntervalMs(int)`          | `5000`  | How often to send an auto-ping when the loop is running (ms) |
| `clientInitialReconnectDelayMs(int)` | `1000`  | Initial backoff delay between reconnect attempts (ms)        |
| `clientMaxReconnectDelayMs(int)`     | `30000` | Maximum backoff delay between reconnect attempts (ms)        |
| `clientMaxReconnectAttempts(int)`    | `6`     | Max reconnect attempts before `reconnect()` returns `false`  |
| `clientDisconnectNoticeDelayMs(int)` | `50`    | Reserved; currently stored but not used                      |
| `clientProcessingLoopSleepMs(int)`   | `10`    | Sleep between client processing loop iterations (ms)         |

## DTLS

| Builder method           | Default           | Description                                                                                                                                                     |
| ------------------------ | ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `sslContext(SSLContext)` | `null` (disabled) | DTLS `SSLContext` for the relay (server mode) or host/client (client mode). When non-null, a DTLS handshake is performed before any Neon packets are exchanged. |

Use `DtlsConfig.fromKeyStore(KeyStore, char[])` to create a relay server context from a PKCS12 keystore. Use `DtlsConfig.withTrustStore(KeyStore)` to create a production host/client context that trusts relay certificates. Use `DtlsConfig.insecureTrustAll()` for development and testing only.

The same `NeonConfig` instance must **not** be shared between the relay and a host/client when DTLS is enabled — the relay requires a server context (with `KeyManager`) and the host/client require a client context (with `TrustManager`).

## Reliable Packet Manager

| Builder method                  | Default | Description                                             |
| ------------------------------- | ------- | ------------------------------------------------------- |
| `reliablePacketTimeoutMs(int)`  | `2000`  | Per-packet ACK timeout before retransmit (ms)           |
| `reliablePacketMaxRetries(int)` | `5`     | Max retransmit attempts before `onDeliveryFailed` fires |

## Examples

### High-frequency action game (20ms tick)

```java
NeonConfig cfg = NeonConfig.builder()
    .hostSessionTickRate((short) 50)
    .hostSessionMaxPacketSize((short) 512)
    .hostProcessingLoopSleepMs(5)
    .clientProcessingLoopSleepMs(5)
    .clientPingIntervalMs(2000)
    .build();
```

### Small LAN relay (trusted network, no rate limiting needed)

```java
NeonConfig cfg = NeonConfig.builder()
    .relayPort(9999)
    .maxPacketsPerSecond(10000)
    .maxClientsPerSession(8)
    .relayClientTimeoutMs(60000)
    .build();
```

### Slow mobile connection with aggressive reconnect

```java
NeonConfig cfg = NeonConfig.builder()
    .clientConnectionTimeoutMs(10000)
    .clientInitialReconnectDelayMs(500)
    .clientMaxReconnectAttempts(10)
    .hostSessionTokenTimeoutMs(600000) // 10 minutes
    .hostAckTimeoutMs(5000)
    .build();
```