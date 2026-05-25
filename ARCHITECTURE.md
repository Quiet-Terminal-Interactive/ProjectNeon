# Architecture

## Topology

```
                    ┌──────────────────────────────────┐
                    │           NeonRelay              │
                    │                                  │
                    │  SessionManager                  │
                    │    session 42:                   │
                    │      host → addr:port            │
                    │      client 2 → addr:port        │
                    │      client 3 → addr:port        │
                    │                                  │
                    │  RateLimiter (per source)        │
                    │  PendingConnection queue         │
                    │  PendingReconnect map            │
                    └──────────────┬───────────────────┘
                                   │ UDP
               ┌───────────────────┼───────────────────┐
               │                   │                   │
        ┌──────┴──────┐    ┌───────┴──────┐     ┌──────┴──────┐
        │  NeonClient │    │  NeonClient  │     │  NeonHost   │
        │  (client 2) │    │  (client 3)  │     │  (id=1)     │
        └─────────────┘    └──────────────┘     └─────────────┘
```

All UDP traffic flows through the relay. Clients and the host never communicate directly, which means:

- NAT traversal is handled entirely by the relay's single public address
- The host's real address is never exposed to clients
- Clients connect to the relay address, not each other

## Component Responsibilities

### NeonRelay

- Routes packets by destination ID in the header
- Manages the host-registration and connection handshakes
- Enforces per-source rate limits
- Evicts stale connections on a cleanup interval
- Buffers reconnect requests until the host validates them

The relay is stateless with respect to game logic. It only parses the 8-byte header plus the payloads of lifecycle packets (`HOST_REGISTER`, `CONNECT_REQUEST/ACCEPT/DENY`, `RECONNECT_REQUEST`, `DISCONNECT_NOTICE`). Everything else is routed opaquely by destination ID.

### NeonHost

- Registers a session with the relay (`HOST_REGISTER`)
- Assigns client IDs atomically (starts at 2; ID 1 is the host itself)
- Generates a cryptographically random session token per client for reconnect
- Sends `SESSION_CONFIG` reliably (tracked by `AckStateMachine`)
- Maintains connected/disconnected client maps
- Dispatches game packets to the application via `unhandledPacketCallback`

The host has no notion of "which relay" after registration — all subsequent packets arrive from the relay's address.

### NeonClient

- Sends `CONNECT_REQUEST` and waits synchronously for `CONNECT_ACCEPT`
- Stores session token and client ID for reconnect
- Drives the packet loop via `run()` or application-controlled `processPackets()`
- Sends auto-pings at a configurable interval
- Recreates its UDP socket on reconnect if the original is closed

## Session Lifecycle

```
Host registers
      │
      ▼
[REGISTERED] ─── client joins ──► [ACTIVE: N clients]
                                          │
                               client disconnects
                                          │
                                          ▼
                                 [token retained, N-1 clients]
                                          │
                              reconnect window expires
                                          │
                                          ▼
                                 [slot freed permanently]
```

The reconnect token window is 5 minutes by default (`hostSessionTokenTimeoutMs`). Within that window a client can rejoin with its original ID. After expiry the ID may be reassigned.

## Connection Handshake Detail

```
1. Client calls connect(sessionId, relayAddr)
2. NeonSocket(port=0) binds a random local port
3. CONNECT_REQUEST sent to relay
4. Relay looks up session, finds host address, adds client to FIFO queue
5. Relay forwards CONNECT_REQUEST to host
6. Host calls connectedNames.putIfAbsent(name) — atomic name reservation
7. Host calls nextClientId.getAndIncrement() — atomic ID allocation
8. Host sends CONNECT_ACCEPT, SESSION_CONFIG (reliable), PACKET_TYPE_REGISTRY
9. Relay pops FIFO queue → maps clientId→clientAddr in SessionManager
10. Relay forwards CONNECT_ACCEPT to client
11. Client exits blocking handshake loop, transitions to RUNNING
12. SESSION_CONFIG and PACKET_TYPE_REGISTRY arrive in client's buffer
13. client.processPackets() / client.run() handles them
```

## Reconnect Handshake Detail

The critical invariant: **the relay does not update the peer address until the host sends CONNECT_ACCEPT**.

```
1. Client calls reconnect()
2. If socket is closed, a new NeonSocket is created (new local port)
3. RECONNECT_REQUEST sent to relay (carries token + old clientId)
4. Relay stores PendingReconnect(sessionId, clientId, newAddr) keyed by "sessionId:clientId"
5. Relay forwards RECONNECT_REQUEST to host (old address still in SessionManager)
6. Host validates token — if invalid, sends CONNECT_DENY; relay discards PendingReconnect
7. If valid, host sends CONNECT_ACCEPT
8. Relay finds PendingReconnect → calls updatePeerAddress(sessionId, clientId, newAddr)
9. Relay forwards CONNECT_ACCEPT to newAddr
10. Client receives CONNECT_ACCEPT, stores new token, reconnect() returns true
```

## Threading Model

All three components are designed for a single dedicated virtual thread:

```java
Thread.ofVirtual().start(relay::startAndRun);
Thread.ofVirtual().start(() -> host.startAndRun());
Thread.ofVirtual().start(client::run);
```

The processing loops sleep between iterations (`relayMainLoopSleepMs`, `hostProcessingLoopSleepMs`, `clientProcessingLoopSleepMs`) and yield to the virtual thread scheduler. NIO `DatagramChannel` is always non-blocking; blocking semantics during handshakes are emulated with a `Selector` so carrier threads are never pinned.

Shared state between threads uses `ConcurrentHashMap` and `AtomicInteger`/`AtomicReference`. The relay's `pendingBySession` map and `pendingConnections` map are only accessed from the single relay processing thread.

## DTLS Encryption

DTLS is relay-terminated: each peer (host or client) maintains a separate DTLS session with the
relay. Peers never negotiate DTLS with each other.

```
NeonClient ──── DTLS ────► NeonRelay ◄──── DTLS ──── NeonHost
```

When DTLS is enabled (`NeonConfig.sslContext != null`):

- **Relay** calls `NeonSocket.enableServerDtls(ctx)` on startup. Inbound `ClientHello` records
  (first byte `0x14–0x17`) from unknown peers automatically trigger server-side handshakes.
- **Host / Client** calls `NeonSocket.performClientHandshake(ctx, relayAddr, timeoutMs)` during
  `doStart()`, before sending `HOST_REGISTER` or `CONNECT_REQUEST`.
- After the handshake, `NeonSocket.send()` transparently encrypts and `NeonSocket.receive()`
  transparently decrypts all subsequent packets for that peer. Game code sees no difference.

`DtlsSession` wraps a single `SSLEngine` and drives the `NEED_WRAP` / `NEED_UNWRAP` /
`NEED_TASK` / `NEED_UNWRAP_AGAIN` state machine. Sessions are keyed by `SocketAddress` in a
`ConcurrentHashMap` on `NeonSocket`. On disconnect, `removeDtlsSession()` drops the session.

`DtlsConfig` provides factory methods for creating `SSLContext` instances:
- `DtlsConfig.fromKeyStore(KeyStore, char[])` — relay server context (requires a private key)
- `DtlsConfig.insecureTrustAll()` — development client context (accepts any certificate)

## Packet Processing Loop

```
while (isRunning()) {
    receive all buffered packets → handle each
    check pending ACKs (host only)
    check cleanup (relay only)
    check auto-ping (client only)
    sleep(loopSleepMs)
}
```

`SESSION_CONFIG` reliability is handled by `AckStateMachine` inside the host loop. `ReliablePacketManager` is available for opt-in reliability on game packets.
