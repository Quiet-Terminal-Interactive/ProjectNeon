# Configuration Reference — TypeScript

All configuration is done through `NeonConfig`. Obtain defaults with `new NeonConfig()` or customise by passing a `NeonConfigOptions` object:

```ts
import { NeonConfig } from 'qti-neon';

const cfg = new NeonConfig({
    relayPort: 9001,
    hostSessionTickRate: 30,
    clientPingIntervalMs: 2000,
});
```

One `NeonConfig` instance can be shared across the relay, host, and client in the same process — **except when DTLS is enabled** (see [DTLS](#dtls) below).

For a description of what each setting does, see [CONFIGURATION.md](../CONFIGURATION.md).

## Socket / Buffer

| `NeonConfigOptions` key | Type      | Default | Description                                                                            |
| ----------------------- | --------- | ------- | -------------------------------------------------------------------------------------- |
| `bufferSize`        | `number`  | `65535` | UDP receive buffer size in bytes; must be `>= 8`                                       |
| `enforceBufferSize` | `boolean` | `true`  | Drop datagrams that fill the receive buffer exactly, treating them as likely truncated |

## Relay

| `NeonConfigOptions` key  | Type     | Default | Description                                                                     |
| ------------------------ | -------- | ------- | ------------------------------------------------------------------------------- |
| `relayPort`              | `number` | `7777`  | UDP port the relay binds to                                                     |
| `relayCleanupIntervalMs` | `number` | `5000`  | How often stale sessions/connections are evicted (ms)                           |
| `relayClientTimeoutMs`   | `number` | `15000` | How long since last activity before a peer is considered stale (ms)             |
| `relayMainLoopSleepMs`   | `number` | `1`     | (Stored; not used — cleanup is driven by `setInterval`, not a sleep loop)       |
| `maxPendingConnections`  | `number` | `64`    | Max clients simultaneously in the connection handshake queue; must be `> 0`     |
| `maxRateLimiters`        | `number` | `1024`  | Max number of per-source rate limiter instances; cleared entirely when exceeded |
| `maxPacketsPerSecond`    | `number` | `100`   | Per-source packet rate limit; excess packets are dropped                        |
| `maxClientsPerSession`   | `number` | `32`    | Maximum connected clients per session (not counting the host)                   |

## Host

| `NeonConfigOptions` key         | Type     | Default  | Description                                                            |
| ------------------------------- | -------- | -------- | ---------------------------------------------------------------------- |
| `hostAckTimeoutMs`              | `number` | `2000`   | How long to wait for a `SESSION_CONFIG` ACK before retransmitting (ms) |
| `hostMaxAckRetries`             | `number` | `5`      | Max `SESSION_CONFIG` retransmit attempts before giving up              |
| `hostSessionTokenTimeoutMs`     | `number` | `300000` | Reconnect token validity window — 5 minutes (ms)                       |
| `hostGracefulShutdownTimeoutMs` | `number` | `3000`   | How long `stop()` waits for pending ACKs to drain (ms)                 |
| `hostProcessingLoopSleepMs`     | `number` | `10`     | `setInterval` period for ACK retransmit checks (ms)                    |
| `hostSessionTickRate`           | `number` | `60`     | Tick rate advertised to clients in `SESSION_CONFIG`                    |
| `hostSessionMaxPacketSize`      | `number` | `1200`   | Max game packet size advertised in `SESSION_CONFIG` (bytes)            |

## Client

| `NeonConfigOptions` key         | Type     | Default | Description                                                  |
| ------------------------------- | -------- | ------- | ------------------------------------------------------------ |
| `clientConnectionTimeoutMs`     | `number` | `5000`  | Max time to wait for `CONNECT_ACCEPT` during handshake (ms)  |
| `clientPingIntervalMs`          | `number` | `5000`  | How often to send an auto-ping when the loop is running (ms) |
| `clientInitialReconnectDelayMs` | `number` | `1000`  | Initial backoff delay between reconnect attempts (ms)        |
| `clientMaxReconnectDelayMs`     | `number` | `30000` | Maximum backoff delay between reconnect attempts (ms)        |
| `clientMaxReconnectAttempts`    | `number` | `6`     | Max reconnect attempts before `reconnect()` returns `false`  |
| `clientProcessingLoopSleepMs`   | `number` | `10`    | `setInterval` period for auto-ping checks (ms)               |

## DTLS

| `NeonConfigOptions` key | Type                 | Default           | Description                                                                                                                                                   |
| ----------------------- | -------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dtlsConfig`            | `DtlsConfig \| null` | `null` (disabled) | DTLS configuration for encryption. The relay requires a server config (with certificate + key); hosts and clients require a client config (with trust store). |

Use `DtlsConfig.fromKeyStore(certFile, keyFile)` to create a relay server config from PEM files. Use `DtlsConfig.withTrustStore(caFile)` to create a production host/client config. Use `DtlsConfig.insecureTrustAll()` for development and testing only.

The same `NeonConfig` instance must **not** be shared between the relay and a host/client when DTLS is enabled — the relay requires a server context and hosts/clients require a client context, and `DtlsContext` is not reusable across both roles.

Requires `npm install koffi` and OpenSSL 3 (`libssl.so.3`) on the system. If koffi is not installed, `NeonConfig` still constructs successfully; the error is thrown only when the first DTLS handshake is attempted.

## Reliable Packet Manager

| `NeonConfigOptions` key    | Type     | Default | Description                                     |
| -------------------------- | -------- | ------- | ----------------------------------------------- |
| `reliablePacketTimeoutMs`  | `number` | `2000`  | Per-packet ACK timeout before retransmit (ms)   |
| `reliablePacketMaxRetries` | `number` | `5`     | Max retransmit attempts before delivery failure |

## Examples

### High-frequency action game (20ms tick)

```ts
const cfg = new NeonConfig({
    hostSessionTickRate: 50,
    hostSessionMaxPacketSize: 512,
    hostProcessingLoopSleepMs: 5,
    clientProcessingLoopSleepMs: 5,
    clientPingIntervalMs: 2000,
});
```

### Small LAN relay (trusted network)

```ts
const cfg = new NeonConfig({
    relayPort: 9999,
    maxPacketsPerSecond: 10000,
    maxClientsPerSession: 8,
    relayClientTimeoutMs: 60000,
});
```

### Slow mobile connection with aggressive reconnect

```ts
const cfg = new NeonConfig({
    clientConnectionTimeoutMs: 10000,
    clientInitialReconnectDelayMs: 500,
    clientMaxReconnectAttempts: 10,
    hostSessionTokenTimeoutMs: 600000, // 10 minutes
    hostAckTimeoutMs: 5000,
});
```

### DTLS-enabled relay and client

```ts
import { DtlsConfig, NeonConfig, NeonRelay, NeonClient } from 'qti-neon';

// Relay — server config with certificate and private key
const relayCfg = new NeonConfig({
    dtlsConfig: DtlsConfig.fromKeyStore('relay.crt', 'relay.key'),
});
const relay = new NeonRelay('0.0.0.0:7777', relayCfg);

// Client — development config; accepts any certificate
const clientCfg = new NeonConfig({
    dtlsConfig: DtlsConfig.insecureTrustAll(),
});
const client = new NeonClient('player1', clientCfg);
```
