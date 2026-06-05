# Architecture — Java

This document covers the Java implementation internals. For the protocol topology, session lifecycle, and handshake sequences see [ARCHITECTURE.md](../ARCHITECTURE.md).

## Component Responsibilities

### NeonRelay

The relay parses the 8-byte header plus the payloads of lifecycle packets. `HOST_REGISTER`, `CONNECT_REQUEST`, `CONNECT_ACCEPT`, `RECONNECT_REQUEST`, and `DISCONNECT_NOTICE` have relay-specific handlers; other valid packets, including `CONNECT_DENY`, are routed opaquely by destination ID.

- `SessionManager` — stores host and client `SocketAddress` mappings per session
- `RateLimiter` — per-source packet rate enforcement
- `pendingConnections` — FIFO queue of in-flight connect requests
- `pendingBySession` — map of pending reconnect entries keyed by `"sessionId:clientId"`

### NeonHost

- Assigns client IDs via `nextClientId.getAndIncrement()` — atomic, no locking
- Reserves client names via `connectedNames.putIfAbsent(name)` — atomic
- Generates a cryptographically random session token per client via `SecureRandom`
- Sends `SESSION_CONFIG` reliably, tracked by `AckStateMachine`
- Maintains `connectedClients` and `disconnectedClients` as `ConcurrentHashMap`
- Dispatches game packets to the application via `unhandledPacketCallback`

### NeonClient

- Performs a blocking handshake loop using a `Selector` — carrier thread is never pinned
- Stores session token and client ID as `AtomicReference` / `AtomicInteger` for reconnect
- Drives the packet loop via `run()` or application-controlled `processPackets()`
- Recreates `NeonSocket` on reconnect if the original socket is closed

## Threading Model

All three components are designed for a single dedicated virtual thread:

```java
Thread.ofVirtual().start(relay::startAndRun);
Thread.ofVirtual().start(() -> host.startAndRun());
Thread.ofVirtual().start(client::run);
```

The processing loops sleep between iterations (`relayMainLoopSleepMs`, `hostProcessingLoopSleepMs`, `clientProcessingLoopSleepMs`) and yield to the virtual thread scheduler. `DatagramChannel` is always non-blocking; blocking semantics during handshakes are emulated with a `Selector` so carrier threads are never pinned.

Shared state between threads uses `ConcurrentHashMap` and `AtomicInteger`/`AtomicReference`. The relay's `pendingBySession` and `pendingConnections` maps are only accessed from the single relay processing thread.

## DTLS Implementation

DTLS is relay-terminated. Each peer (`NeonHost` or `NeonClient`) maintains a separate `DtlsSession` with the relay via `NeonSocket`.

```
NeonClient ──── DTLS ────► NeonRelay ◄──── DTLS ──── NeonHost
```

When DTLS is enabled (`NeonConfig.sslContext != null`):

- **Relay** calls `NeonSocket.enableServerDtls(ctx)` on startup. Inbound `ClientHello` records (first byte `0x14–0x17`) from unknown peers automatically trigger server-side handshakes.
- **Host / Client** calls `NeonSocket.performClientHandshake(ctx, relayAddr, timeoutMs)` during `doStart()`, before sending `HOST_REGISTER` or `CONNECT_REQUEST`.
- After the handshake, `NeonSocket.send()` transparently encrypts and `NeonSocket.receive()` transparently decrypts all subsequent packets for that peer. Game code sees no difference.

`DtlsSession` wraps a single `SSLEngine` and drives the `NEED_WRAP` / `NEED_UNWRAP` / `NEED_TASK` / `NEED_UNWRAP_AGAIN` state machine. Sessions are keyed by `SocketAddress` in a `ConcurrentHashMap` on `NeonSocket`. On disconnect, `removeDtlsSession()` drops the session.

`DtlsConfig` provides factory methods for creating `SSLContext` instances:

- `DtlsConfig.fromKeyStore(KeyStore, char[])` — relay server context (requires a private key)
- `DtlsConfig.withTrustStore(KeyStore)` — production host/client context (trusts relay certificates)
- `DtlsConfig.insecureTrustAll()` — development client context (accepts any certificate)

## Packet Processing Loop

```java
while (isRunning()) {
    // receive all buffered packets → handle each
    check pending ACKs          // host only — AckStateMachine
    check cleanup               // relay only — stale session eviction
    check auto-ping             // client only
    sleep(loopSleepMs);
}
```

`SESSION_CONFIG` reliability is handled by `AckStateMachine` inside the host loop. `ReliablePacketManager` is available for opt-in reliability on game packets.

## Key Classes

| Class                   | Role                                                             |
| ----------------------- | ---------------------------------------------------------------- |
| `NeonRelay`             | Relay processing loop and packet routing                         |
| `NeonHost`              | Host processing loop and client lifecycle                        |
| `NeonClient`            | Client processing loop and reconnect                             |
| `NeonSocket`            | UDP socket wrapper with optional DTLS                            |
| `NeonConfig`            | Immutable configuration record                                   |
| `SessionManager`        | Peer address map per session                                     |
| `ReliablePacketManager` | Opt-in reliable delivery with retransmit and duplicate detection |
| `AckStateMachine`       | Reliable delivery for `SESSION_CONFIG`                           |
| `DtlsSession`           | SSLEngine state machine per peer                                 |
| `DtlsConfig`            | SSLContext factory methods                                       |
| `PacketType`            | Packet type byte constants                                       |
| `NeonPacket`            | Packet parsing and serialisation                                 |