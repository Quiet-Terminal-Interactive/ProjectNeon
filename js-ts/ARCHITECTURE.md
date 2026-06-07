# Architecture — TypeScript

This document covers the TypeScript/Node.js implementation internals. For the protocol topology, session lifecycle, and handshake sequences see [ARCHITECTURE.md](../ARCHITECTURE.md).

## Component Responsibilities

### NeonRelay

Routes packets by destination ID. Handles `HOST_REGISTER`, `CONNECT_REQUEST`, `CONNECT_ACCEPT`, `CONNECT_DENY`, `RECONNECT_REQUEST`, and `DISCONNECT_NOTICE` directly; all other valid packets are routed opaquely.

- `SessionManager` — stores host and client `Address` mappings per session, keyed by `"address:port"` string
- `RateLimiter` — per-source token-bucket rate enforcement (`Map<string, RateLimiter>`)
- `pendingConnections` — `Map<string, PendingConnection>` of in-flight connect requests keyed by source address key
- `pendingBySession` — `Map<number, Address[]>` FIFO queue of pending client addresses per session
- `pendingReconnects` — `Map<string, PendingReconnect>` keyed by `"sessionId:clientId"`

When the host sends `CONNECT_DENY` for a pending connection, the relay pops the FIFO queue and forwards the deny directly to the waiting client — the client is not yet a registered session peer at that point.

### NeonHost

- Assigns client IDs via a monotonically incremented `nextClientId` counter (starts at 2; host is always 1)
- Reserves client names via a `connectedNames: Map<string, number>` — safe because Node.js is single-threaded
- Generates cryptographically random session tokens via `crypto.randomBytes(8).readBigUInt64LE(0)`; field type is `bigint`
- Sends `SESSION_CONFIG` reliably, tracked by `AckStateMachine`
- Maintains `connectedClients: Map<number, string>` and `disconnectedClients: Map<number, DisconnectedClient>`

### NeonClient

- Performs an async handshake via `_waitForConnectResponse()` — a Promise that resolves on `CONNECT_ACCEPT` or `CONNECT_DENY`
- Stores `sessionToken: bigint | null` and `clientId: number | null` for reconnect
- Drives the packet loop via the `dgram` socket's `'packet'` event and a `setInterval` for auto-ping
- Recreates `NeonSocket` on reconnect if the original socket is closed
- Sends `DISCONNECT_NOTICE` on `stop()`, with a `setImmediate` deferral before `socket.close()` to allow the datagram to flush

## Event-Driven Model

Unlike the Java and Python implementations, the TypeScript implementation does **not** use a blocking processing loop or dedicated threads. Instead, all I/O is driven by Node.js's event loop:

```
dgram 'message' event  →  NeonSocket._onRaw()  →  parsePacket()  →  emit('packet', pkt, addr)
                                                                            │
                                              ┌─────────────────────────────┘
                                              ▼
                                   NeonRelay._onPacket()
                                   NeonHost._handlePacket()
                                   NeonClient._handlePacket()
```

Periodic tasks (ACK retransmit, auto-ping, session cleanup) are driven by `setInterval`, with `.unref()` called on each interval handle so the process does not stay alive indefinitely on behalf of the library:

```ts
// Host — ACK retransmit
this.ackInterval = setInterval(() => this._checkPendingAcks(), config.hostProcessingLoopSleepMs);
this.ackInterval.unref();

// Client — auto-ping
this.pingInterval = setInterval(() => this._checkAutoPing(), config.clientProcessingLoopSleepMs);
this.pingInterval.unref();

// Relay — stale session cleanup
this.cleanupTimer = setInterval(() => this._performCleanup(), config.relayCleanupIntervalMs);
this.cleanupTimer.unref();
```

There is no `sleep()` or blocking I/O anywhere in the implementation. Handshake waits (e.g. waiting for `CONNECT_ACCEPT` during `start()`) are implemented as Promises that resolve inside an event listener, keeping the event loop free:

```ts
private _waitForConnectResponse(timeoutMs: number): Promise<NeonPacket | null> {
    return new Promise((resolve) => {
        const timer = setTimeout(() => { this.socket.off('packet', handler); resolve(null); }, timeoutMs);
        const handler = (pkt: NeonPacket) => {
            if (isConnectAccept(pkt.payload) || isConnectDeny(pkt.payload)) {
                clearTimeout(timer); this.socket.off('packet', handler); resolve(pkt);
            }
        };
        this.socket.on('packet', handler);
    });
}
```

Because Node.js is single-threaded, there are no data races and no locking primitives are needed anywhere in the implementation.

## DTLS Implementation

DTLS is relay-terminated. Each peer (`NeonHost` or `NeonClient`) maintains a separate DTLS session with the relay via `NeonSocket`.

```
NeonClient ──── DTLS ────► NeonRelay ◄──── DTLS ──── NeonHost
```

Node.js has no built-in DTLS support and no mature DTLS npm package. The TypeScript implementation calls OpenSSL's C API directly via **koffi** (a foreign function interface for Node.js), using the same memory BIO architecture that the Python implementation uses via cffi.

When DTLS is enabled (`NeonConfig.dtlsConfig !== null`):

- **Relay** calls `NeonSocket.enableServerDtls(cfg)` on startup, which builds a server-side `DtlsContext` (OpenSSL `SSL_CTX*`). Inbound DTLS records (first byte `0x14–0x17` or `0x20–0x3F`) from unknown peers automatically create new per-peer `DtlsSession` instances and drive the server-side handshake.
- **Host / Client** calls `NeonSocket.performClientHandshake(cfg, relayAddr, timeoutMs)` during `start()` / `connect()`, creating a client-side `DtlsSession` and driving the handshake via a temporary `'message'` event listener before sending `HOST_REGISTER` or `CONNECT_REQUEST`.
- After the handshake, `NeonSocket.send()` transparently encrypts via `DtlsSession.wrap()` and `NeonSocket._handleDtls()` transparently decrypts via `DtlsSession.receive()`. Game code sees no difference.

`DtlsSession` owns one OpenSSL `SSL*` object and two memory BIOs (`rbio` for inbound data, `wbio` for outbound data). The session drives the `SSL_do_handshake()` / `SSL_read()` / `SSL_write()` state machine:

- `initiate()` — calls `SSL_do_handshake()`, drains `wbio` to produce the initial `ClientHello`
- `receive(data)` — feeds data into `rbio`, calls `SSL_do_handshake()` or `SSL_read()`, drains `wbio` for outbound records
- `wrap(plaintext)` — calls `SSL_write()`, drains `wbio` for the ciphertext record
- `destroy()` — frees `SSL*` and both BIOs

koffi is an optional peer dependency (`npm install koffi`). OpenSSL 3 (`libssl.so.3` + `libcrypto.so.3`) must be present on the system. If koffi is not installed, loading `dtls.ts` still succeeds — the error is deferred to the first `DtlsConfig.buildContext()` call.

`DtlsConfig` provides factory methods:

- `DtlsConfig.fromKeyStore(certFile, keyFile)` — relay server config (PEM certificate + private key)
- `DtlsConfig.withTrustStore(caFile)` — production host/client config (trusts relay certificate)
- `DtlsConfig.insecureTrustAll()` — development/test config (accepts any certificate)

## 64-bit Integer Fields

JavaScript's `number` type cannot represent all 64-bit unsigned integers. All wire-format 64-bit fields — `token`, `hostToken`, and `timestamp` — are typed as `bigint` throughout the implementation. Serialisation uses `Buffer.writeBigUInt64LE(BigInt.asUintN(64, value))` and deserialisation uses `Buffer.readBigUInt64LE()`.

## Packet Processing Loop

There is no explicit loop. Inbound packet processing happens synchronously inside the `dgram` `'message'` event callback. Periodic tasks are driven by `setInterval`:

```
dgram 'message' → _onRaw() → parsePacket() → emit('packet') → handler (sync)

setInterval (hostProcessingLoopSleepMs)  → _checkPendingAcks()   [host]
setInterval (clientProcessingLoopSleepMs) → _checkAutoPing()      [client]
setInterval (relayCleanupIntervalMs)     → _performCleanup()      [relay]
```

`SESSION_CONFIG` reliability is handled by `AckStateMachine` inside `_checkPendingAcks()`. `ReliablePacketManager` is available for opt-in reliability on game packets.

## Key Classes

| Class / Interface       | Module          | Role                                                            |
| ----------------------- | --------------- | --------------------------------------------------------------- |
| `NeonRelay`             | `relay`         | Packet routing and session lifecycle                            |
| `NeonHost`              | `host`          | Host packet handling and client lifecycle                       |
| `NeonClient`            | `client`        | Client connect, reconnect, and packet dispatch                  |
| `NeonSocket`            | `_socket`       | dgram wrapper with per-peer DTLS dispatch (internal)            |
| `NeonConfig`            | `_config`       | Immutable configuration with validated defaults                 |
| `NeonConfigOptions`     | `_config`       | Constructor interface for `NeonConfig`                          |
| `DtlsConfig`            | `dtls`          | DTLS configuration with factory methods                         |
| `DtlsContext`           | `dtls`          | Owns the OpenSSL `SSL_CTX*` (internal)                          |
| `DtlsSession`           | `dtls`          | Per-peer OpenSSL `SSL*` + memory BIOs state machine (internal)  |
| `SessionManager`        | `_session`      | Peer address map per session (relay-internal)                   |
| `ReliablePacketManager` | `_reliable`     | Opt-in reliable delivery for game packets                       |
| `AckStateMachine`       | `_ack`          | Reliable delivery for `SESSION_CONFIG`                          |
| `RateLimiter`           | `_rate_limiter` | Per-source token-bucket rate limiter (relay-internal)           |
| `GamePacketRegistry`    | `_registry`     | Registry of application-defined game packet types               |
| `PacketType`            | `_protocol`     | Packet type enum constants                                      |
| `NeonPacket`            | `_protocol`     | Packet parsing and serialisation (header + typed payload union) |
| `Address`               | `_socket`       | `{ address: string; port: number }` — immutable peer address    |
