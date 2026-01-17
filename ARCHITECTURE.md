# Project Neon - Architecture Documentation

**Version:** 1.1.0
**Last Updated:** 2026-01-17

---

## Table of Contents

- [Overview](#overview)
- [Design Principles](#design-principles)
- [System Architecture](#system-architecture)
- [Protocol Design](#protocol-design)
- [Component Architecture](#component-architecture)
- [Packet Flow](#packet-flow)
- [State Machines](#state-machines)
- [Reliability Mechanisms](#reliability-mechanisms)
- [Security Architecture](#security-architecture)
- [Performance Considerations](#performance-considerations)
- [Design Decisions](#design-decisions)
- [Future Architecture](#future-architecture)

---

## Overview

Project Neon is a **minimal, game-agnostic, relay-based UDP multiplayer protocol**. It provides only the essential primitives for connection management, leaving all game-specific logic to the application layer.

### Key Characteristics

- **Protocol**: UDP-based, unreliable by default
- **Architecture**: Client-Relay-Host topology
- **Core**: 11 packet types (0x01-0x0F reserved, 0x10+ game-defined)
- **Philosophy**: Minimal core, maximum flexibility
- **Language**: Java 21 with C/JNI bindings

### Design Goals

1. **Modularity**: Games define their own packet vocabulary
2. **Simplicity**: Minimal protocol overhead (~8 byte header)
3. **Flexibility**: Suitable for any real-time multiplayer genre
4. **Performance**: Low latency, high throughput
5. **Compatibility**: Cross-platform, cross-engine support

---

## Design Principles

### 1. Zero Assumptions

**Principle**: The protocol makes no assumptions about game mechanics.

**Rationale**: Traditional protocols bundle connection management with game features (movement, inventory, etc.). This creates bloat and limits flexibility. Project Neon provides only the essentials, letting games define their own semantics.

**Implementation**:
- Core protocol has 11 packet types (connection management only)
- Game packets (0x10+) are completely application-defined
- Relay forwards payloads without parsing them

**Trade-offs**:
- ✅ Universal compatibility (works for any game genre)
- ✅ No unnecessary features or overhead
- ❌ Games must implement their own game logic (not a framework)

### 2. Payload-Agnostic Relay

**Principle**: The relay routes packets without understanding them.

**Rationale**: Parsing game packets would require the relay to know about game-specific formats, creating tight coupling. A dumb relay is fast, simple, and universally compatible.

**Implementation**:
- Relay validates only the 8-byte header (magic, version, type, sequence, IDs)
- Payloads are forwarded as raw bytes
- No deserialization or interpretation of game packets

**Trade-offs**:
- ✅ Relay is simple and fast
- ✅ Games can use any serialization format
- ✅ Protocol changes don't require relay updates
- ❌ Relay cannot validate game packet contents
- ❌ No relay-side game packet filtering or transformation

### 3. Opt-In Reliability

**Principle**: Reliability is optional and selective.

**Rationale**: UDP is unreliable by design (fast, low latency). Most game data (positions, animations) doesn't need reliability—only critical events (score, item pickup) do. Making reliability mandatory adds unnecessary overhead.

**Implementation**:
- Core protocol is unreliable (except SESSION_CONFIG)
- Optional `ReliablePacketManager` utility for game packets
- Games choose which packets need ACK/retry

**Trade-offs**:
- ✅ Optimal performance for most data
- ✅ Flexibility to add reliability where needed
- ❌ Games must implement reliability logic
- ❌ More complex than "everything reliable" approach

### 4. Explicit Configuration

**Principle**: All timing and limits are configurable.

**Rationale**: Different games have different requirements (fast-paced FPS vs. turn-based game). Hardcoded constants don't work for everyone.

**Implementation**:
- `NeonConfig` class with 30+ parameters
- Builder pattern for fluent configuration
- Validation ensures values are in safe ranges

**Trade-offs**:
- ✅ Tunable for different game types and networks
- ✅ No "one size fits all" compromises
- ❌ More complex than fixed defaults
- ❌ Users must understand tuning trade-offs

### 5. Security Through Isolation

**Principle**: Security is the deployer's responsibility, not the protocol's.

**Rationale**: Encryption and authentication add complexity and latency. For LAN gaming, these are unnecessary overhead. For internet deployment, application-layer security is more flexible.

**Implementation**:
- No built-in encryption (plaintext UDP)
- No built-in authentication
- Input validation and DoS protection only
- Security recommendations in [SECURITY.md](SECURITY.md)

**Trade-offs**:
- ✅ Minimal latency for trusted networks
- ✅ Flexibility in auth/encryption approach
- ❌ Not secure by default
- ❌ Deployers must implement security

---

## System Architecture

### Topology

```
┌─────────────────────────────────────────────────────────────────┐
│                         Internet / LAN                          │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  │ UDP
                                  │
                    ┌─────────────▼──────────────┐
                    │      NeonRelay Server      │
                    │                            │
                    │  - Packet routing          │
                    │  - Session management      │
                    │  - Rate limiting           │
                    │  - Connection tracking     │
                    │                            │
                    │  Port: 7777 (default)      │
                    └─────────┬──────────────────┘
                              │
                ┌─────────────┼─────────────┐
                │             │             │
                │             │             │
        ┌───────▼─────┐ ┌────▼─────┐ ┌────▼─────┐
        │  NeonClient │ │NeonClient│ │NeonClient│
        │ (Player 1)  │ │(Player 2)│ │(Player 3)│
        │             │ │          │ │          │
        │ Client ID: 2│ │ID: 3     │ │ID: 4     │
        └─────────────┘ └──────────┘ └──────────┘
                │
                │
        ┌───────▼─────────────┐
        │     NeonHost        │
        │  (Game Server)      │
        │                     │
        │  - Session owner    │
        │  - Game logic       │
        │  - State authority  │
        │                     │
        │  Client ID: 1       │
        │  Session ID: 12345  │
        └─────────────────────┘
```

### Component Responsibilities

| Component      | Role            | Responsibilities                                                                                                                                        |
| -------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **NeonRelay**  | Packet Router   | - Forward packets between clients/host<br>- Manage sessions<br>- Rate limiting and DoS protection<br>- Connection lifecycle tracking                    |
| **NeonHost**   | Game Server     | - Create and own game session<br>- Accept/deny client connections<br>- Implement game logic<br>- Maintain authoritative state<br>- Process game packets |
| **NeonClient** | Game Client     | - Connect to session via relay<br>- Send/receive game packets<br>- Maintain local state<br>- Handle predictions and reconciliation                      |
| **NeonSocket** | UDP Abstraction | - Wrap DatagramSocket<br>- Packet serialization/deserialization<br>- Timeout handling                                                                   |
| **NeonConfig** | Configuration   | - Store all tunable parameters<br>- Validate configuration<br>- Provide defaults                                                                        |

---

## Protocol Design

### Packet Structure

Every Project Neon packet consists of a fixed 8-byte header followed by a variable-length payload:

```
┌──────────────────────────────────────────────────┐
│                  Packet Header                   │
│                   (8 bytes)                      │
├──────────────────────────────────────────────────┤
│                Packet Payload                    │
│              (variable length)                   │
└──────────────────────────────────────────────────┘
```

### Packet Header (8 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├───────────────────────────────┬───────────────┬───────────────┤
│     Magic Number (0x4E45)     │    Version    │  Packet Type  │
├───────────────────────────────┼───────────────┴───────────────┤
│      Sequence Number          │  Client ID    │Destination ID │
└───────────────────────────────┴───────────────────────────────┘

Fields:
- Magic Number (2 bytes): 0x4E45 ("NE" in ASCII) - protocol identifier
- Version (1 byte): Protocol version (currently 0x01)
- Packet Type (1 byte): Type of packet (0x01-0x0F core, 0x10+ game)
- Sequence Number (2 bytes): Incrementing counter for ordering/reliability
- Client ID (1 byte): Sender's client ID (0 = unassigned, 1 = host, 2-255 = clients)
- Destination ID (1 byte): Target client ID (0 = broadcast, 1 = host, 2+ = specific client)
```

**Design Decisions**:

- **Fixed 8-byte size**: Predictable parsing, minimal overhead
- **Little-endian byte order**: Standard for most platforms
- **Magic number**: Quickly reject non-Neon packets
- **Version byte**: Protocol evolution support
- **Sequence number**: Enables ordering and reliability
- **Client/Dest IDs as bytes**: Limits to 254 clients, sufficient for most games

### Core Packet Types (0x01-0x0F)

| Type                 | Value | Purpose                             | Direction             | Reliability         |
| -------------------- | ----- | ----------------------------------- | --------------------- | ------------------- |
| CONNECT_REQUEST      | 0x01  | Client requests to join session     | Client → Relay → Host | Best-effort         |
| CONNECT_ACCEPT       | 0x02  | Host accepts client connection      | Host → Relay → Client | Best-effort         |
| CONNECT_DENY         | 0x03  | Host rejects client connection      | Host → Relay → Client | Best-effort         |
| SESSION_CONFIG       | 0x04  | Host sends session parameters       | Host → Relay → Client | **Reliable (ACK)**  |
| PACKET_TYPE_REGISTRY | 0x05  | Host shares packet type definitions | Host → Relay → Client | Best-effort         |
| PING                 | 0x0B  | Client keep-alive / latency check   | Client → Relay → Host | Best-effort         |
| PONG                 | 0x0C  | Host responds to ping               | Host → Relay → Client | Best-effort         |
| DISCONNECT_NOTICE    | 0x0D  | Graceful disconnect notification    | Any → Relay → Others  | Best-effort         |
| ACK                  | 0x0E  | Acknowledgment for reliable packets | Any → Any             | Best-effort         |
| RECONNECT_REQUEST    | 0x0F  | Client attempts reconnection        | Client → Relay → Host | Best-effort         |
| GAME_PACKET          | 0x10+ | Game-defined packets                | Any → Any             | Application-defined |

**Rationale**:
- **Minimal set**: Only 10 core types keep protocol simple
- **SESSION_CONFIG reliable**: Critical for gameplay setup
- **Game packets unreliable by default**: Optimize for latency
- **Reserved 0x01-0x0F**: Room for future core packets

### Game Packets (0x10-0xFF)

**Range**: 246 packet types available for games

**Design**: Completely application-defined. Games can:
- Define custom serialization formats (ByteBuffer, Protobuf, JSON, etc.)
- Use subtypes within a single packet type
- Implement versioning schemes
- Add reliability selectively (via ReliablePacketManager)

**Example Game Packet**:
```java
// 0x10 = Player Movement
record PlayerMovement(
    int playerId,
    float x, float y, float z,
    float velocityX, float velocityY, float velocityZ
) {
    byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(28);
        buf.putInt(playerId);
        buf.putFloat(x).putFloat(y).putFloat(z);
        buf.putFloat(velocityX).putFloat(velocityY).putFloat(velocityZ);
        return buf.array();
    }
}
```

---

## Component Architecture

### NeonRelay

**Purpose**: Central packet router and session manager

**Architecture**:
```
┌────────────────────────────────────────────────┐
│              NeonRelay                         │
├────────────────────────────────────────────────┤
│  SessionManager                                │
│    ├─ sessions: ConcurrentHashMap              │
│    │    Key: sessionId (int)                   │
│    │    Value: Session                         │
│    │      ├─ hostAddress: InetSocketAddress    │
│    │      ├─ clients: ConcurrentHashMap        │
│    │      │    Key: clientId (byte)            │
│    │      │    Value: InetSocketAddress        │
│    │      └─ lastActivity: long                │
│    └─ pendingConnections: ConcurrentHashMap    │
│         Key: InetSocketAddress                 │
│         Value: timestamp (long)                │
├────────────────────────────────────────────────┤
│  RateLimiter (per-client)                      │
│    ├─ rateLimiters: ConcurrentHashMap          │
│    │    Key: InetSocketAddress                 │
│    │    Value: RateLimiter                     │
│    │      ├─ tokens: int (available)           │
│    │      ├─ lastRefill: long                  │
│    │      └─ violations: int                   │
│    └─ cleanup (periodic)                       │
├────────────────────────────────────────────────┤
│  Packet Processing Loop                        │
│    ├─ receivePacket()                          │
│    ├─ validateHeader()                         │
│    ├─ checkRateLimit()                         │
│    ├─ routePacket()                            │
│    │    ├─ Core packet → special handling      │
│    │    └─ Game packet → forward raw           │
│    └─ cleanup() (periodic)                     │
└────────────────────────────────────────────────┘
```

**Key Design Decisions**:

1. **ConcurrentHashMap for thread safety**: Supports concurrent reads/writes without explicit locking
2. **Single-threaded packet loop**: Simplicity over parallelism (sufficient for most loads)
3. **Stateless packet routing**: No packet buffering or reordering (minimal memory)
4. **Per-client rate limiting**: Prevents DoS while allowing burst traffic
5. **Periodic cleanup**: Removes stale sessions and connections

**Packet Routing Logic**:
```java
void routePacket(NeonPacket packet, InetSocketAddress sender) {
    Session session = sessions.get(extractSessionId(packet, sender));
    if (session == null) {
        handleUnregisteredPacket(packet, sender);
        return;
    }

    if (packet.header().packetType() < 0x10) {
        // Core packet - special handling
        handleCorePacket(packet, sender, session);
    } else {
        // Game packet - forward raw
        byte destId = packet.header().destinationId();
        if (destId == 0) {
            // Broadcast to all clients + host
            broadcastToSession(packet, session, sender);
        } else {
            // Send to specific destination
            InetSocketAddress dest = session.getAddress(destId);
            if (dest != null) {
                forwardPacket(packet, dest);
            }
        }
    }
}
```

### NeonHost

**Purpose**: Game server managing a single session

**Architecture**:
```
┌────────────────────────────────────────────────┐
│              NeonHost                          │
├────────────────────────────────────────────────┤
│  Session State                                 │
│    ├─ sessionId: int                           │
│    ├─ clientId: byte (always 1)                │
│    ├─ socket: NeonSocket                       │
│    ├─ relayAddress: InetSocketAddress          │
│    └─ running: AtomicBoolean                   │
├────────────────────────────────────────────────┤
│  Client Tracking                               │
│    └─ connectedClients: ConcurrentHashMap      │
│         Key: clientId (byte)                   │
│         Value: ClientInfo                      │
│           ├─ name: String                      │
│           ├─ sessionToken: String (UUID)       │
│           ├─ lastPing: long                    │
│           ├─ connectedAt: long                 │
│           └─ address: InetSocketAddress        │
├────────────────────────────────────────────────┤
│  Reliability Layer (SESSION_CONFIG)            │
│    └─ pendingAcks: ConcurrentHashMap           │
│         Key: clientId (byte)                   │
│         Value: AckTracker                      │
│           ├─ packet: NeonPacket                │
│           ├─ sentAt: long                      │
│           ├─ retries: int                      │
│           └─ timeoutAt: long                   │
├────────────────────────────────────────────────┤
│  Callbacks (application-defined)               │
│    ├─ onClientConnect: (id, name, session) → void│
│    ├─ onClientDeny: (id, name, session) → void│
│    ├─ onClientDisconnect: (id) → void         │
│    ├─ onPing: (id, timestamp) → void          │
│    └─ onUnhandledPacket: (packet) → void      │
├────────────────────────────────────────────────┤
│  Processing Loop                               │
│    ├─ processPackets()                         │
│    │    ├─ receive packet                      │
│    │    ├─ handle core packets                 │
│    │    └─ dispatch game packets to callbacks  │
│    ├─ processAcks() (periodic)                 │
│    │    └─ retransmit unacked packets          │
│    └─ shutdown()                               │
│         └─ wait for pending ACKs (graceful)    │
└────────────────────────────────────────────────┘
```

**Key Design Decisions**:

1. **Client ID = 1 (fixed)**: Host is always client 1, simplifies routing
2. **Session tokens (UUIDs)**: Prevent session hijacking during reconnection
3. **ACK tracking for SESSION_CONFIG only**: Minimal reliability overhead
4. **Callback-based API**: Games implement logic via callbacks (no polling)
5. **Graceful shutdown timeout**: Wait for pending ACKs before closing

### NeonClient

**Purpose**: Game client connecting to a session

**Architecture**:
```
┌────────────────────────────────────────────────┐
│              NeonClient                        │
├────────────────────────────────────────────────┤
│  Connection State                              │
│    ├─ clientId: Optional<Byte>                 │
│    ├─ sessionId: Optional<Integer>             │
│    ├─ socket: NeonSocket                       │
│    ├─ relayAddress: InetSocketAddress          │
│    ├─ connected: AtomicBoolean                 │
│    └─ reconnecting: AtomicBoolean              │
├────────────────────────────────────────────────┤
│  Reconnection State                            │
│    ├─ sessionToken: Optional<String>           │
│    ├─ reconnectAttempts: int                   │
│    ├─ reconnectDelay: long (exponential)       │
│    └─ lastDisconnectTime: long                 │
├────────────────────────────────────────────────┤
│  Heartbeat                                     │
│    ├─ lastPingSent: long                       │
│    ├─ lastPongReceived: long                   │
│    └─ pingIntervalMs: long (config)            │
├────────────────────────────────────────────────┤
│  Callbacks                                     │
│    ├─ onConnected: () → void                   │
│    ├─ onDisconnected: () → void                │
│    ├─ onPong: (responseTime, timestamp) → void │
│    ├─ onSessionConfig: (config) → void         │
│    ├─ onPacketRegistry: (registry) → void      │
│    └─ onError: (exception) → void              │
├────────────────────────────────────────────────┤
│  Processing Loop                               │
│    ├─ processPackets()                         │
│    │    ├─ receive packets                     │
│    │    ├─ handle core packets                 │
│    │    └─ dispatch game packets               │
│    ├─ sendPings() (periodic)                   │
│    ├─ checkTimeout() (periodic)                │
│    └─ attemptReconnect() (on disconnect)       │
│         └─ exponential backoff                 │
└────────────────────────────────────────────────┘
```

**Key Design Decisions**:

1. **Optional IDs**: Client/session IDs are absent until connection succeeds
2. **Automatic ping**: Built-in keep-alive (configurable interval)
3. **Timeout detection**: Disconnect if no pong within timeout
4. **Exponential backoff reconnection**: 1s → 2s → 4s → 8s → 16s → 30s max
5. **Session tokens**: Stored after connection for reconnection attempts

---

## Packet Flow

### Connection Flow

```
┌────────┐         ┌───────┐         ┌────────┐
│ Client │         │ Relay │         │  Host  │
└───┬────┘         └───┬───┘         └───┬────┘
    │                  │                  │
    │  ① CONNECT_REQ   │                  │
    │ ───────────────→ │                  │
    │  (name, session, │                  │
    │   gameId)        │                  │
    │                  │  ② CONNECT_REQ   │
    │                  │ ───────────────→ │
    │                  │  (forwarded)     │
    │                  │                  │
    │                  │                  │ ③ Host validates
    │                  │                  │   - Session match?
    │                  │                  │   - Slot available?
    │                  │                  │   - Auth OK?
    │                  │                  │
    │                  │ ④ CONNECT_ACCEPT │
    │                  │ ←─────────────── │
    │  ⑤ CONNECT_ACCEPT│  (clientId=2,    │
    │ ←─────────────── │   sessionToken)  │
    │  (forwarded)     │                  │
    │                  │                  │
    │                  │  ⑥ SESSION_CONFIG│
    │                  │ ←─────────────── │
    │ ⑦ SESSION_CONFIG │  (version,tick,  │
    │ ←─────────────── │   maxPacket)     │
    │                  │                  │
    │  ⑧ ACK           │                  │
    │ ───────────────→ │  ⑨ ACK           │
    │                  │ ───────────────→ │
    │                  │                  │
    │         ⑩ Connected                 │
    │ ←═══════════════════════════════════│
    │                  │                  │
```

**Steps**:
1. Client sends CONNECT_REQUEST with name, target session ID, game identifier
2. Relay forwards to host (if session exists)
3. Host validates request (session match, capacity, authentication)
4. Host sends CONNECT_ACCEPT with assigned client ID and session token
5. Relay forwards CONNECT_ACCEPT to client
6. Host sends SESSION_CONFIG (reliable packet)
7. Relay forwards SESSION_CONFIG
8. Client sends ACK to relay
9. Relay forwards ACK to host
10. Connection established - client can send/receive game packets

**Alternative: Connection Denial**
```
    │                  │ ④ CONNECT_DENY   │
    │                  │ ←─────────────── │
    │  ⑤ CONNECT_DENY  │  (reason: "Full")│
    │ ←─────────────── │                  │
    │                  │                  │
    │  Connection failed                  │
```

### Game Packet Flow (Broadcast)

```
┌────────┐         ┌───────┐         ┌────────┐
│Client 2│         │ Relay │         │  Host  │
└───┬────┘         └───┬───┘         └───┬────┘
    │                  │                  │
    │  GAME_PACKET     │                  │
    │  (type=0x10,     │                  │
    │   dest=0)        │                  │
    │ ───────────────→ │                  │
    │                  │  Forward to host │
    │                  │ ───────────────→ │
    │                  │  Forward to all  │
    │                  │  other clients   │
    │                  │ ───────────────→ Client 3
    │                  │ ───────────────→ Client 4
    │                  │                  │
```

**Note**: Relay forwards broadcast packets to everyone in session **except sender**.

### Game Packet Flow (Unicast)

```
┌────────┐         ┌───────┐         ┌────────┐
│Client 2│         │ Relay │         │Client 3│
└───┬────┘         └───┬───┘         └───┬────┘
    │                  │                  │
    │  GAME_PACKET     │                  │
    │  (type=0x15,     │                  │
    │   dest=3)        │                  │
    │ ───────────────→ │                  │
    │                  │  Forward to      │
    │                  │  client 3 only   │
    │                  │ ───────────────→ │
    │                  │                  │
```

### Heartbeat Flow

```
┌────────┐         ┌───────┐         ┌────────┐
│ Client │         │ Relay │         │  Host  │
└───┬────┘         └───┬───┘         └───┬────┘
    │                  │                  │
    │  Every 5s:       │                  │
    │  PING            │                  │
    │  (timestamp)     │                  │
    │ ───────────────→ │ ───────────────→ │
    │                  │                  │
    │                  │     PONG         │
    │                  │  (orig timestamp)│
    │ ←─────────────── │ ←─────────────── │
    │                  │                  │
    │  Calculate RTT:  │                  │
    │  now - timestamp │                  │
    │                  │                  │
```

**Timeout Detection**:
- Client sends ping every `clientPingIntervalMs` (default 5s)
- If no pong within `clientConnectionTimeoutMs` (default 10s), client disconnects
- Host can use ping times to detect stale clients

### Reconnection Flow

```
┌────────┐         ┌───────┐         ┌────────┐
│ Client │         │ Relay │         │  Host  │
└───┬────┘         └───┬───┘         └───┬────┘
    │                  │                  │
    │  Network outage  │                  │
    │  ╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳│                  │
    │                  │                  │
    │  Detect timeout  │                  │
    │  Start reconnect │                  │
    │                  │                  │
    │  Attempt 1 (1s)  │                  │
    │  RECONNECT_REQ   │                  │
    │  (token, prevId) │                  │
    │ ───────────────→ │ ───────────────→ │
    │                  │                  │
    │                  │                  │ Validate token
    │                  │                  │ Check timeout
    │                  │                  │
    │                  │  CONNECT_ACCEPT  │
    │                  │ ←─────────────── │
    │  CONNECT_ACCEPT  │  (same clientId) │
    │ ←─────────────── │                  │
    │                  │                  │
    │  Reconnected!    │                  │
    │                  │                  │
```

**Exponential Backoff**:
- Attempt 1: 1 second delay
- Attempt 2: 2 seconds delay
- Attempt 3: 4 seconds delay
- Attempt 4: 8 seconds delay
- Attempt 5: 16 seconds delay
- Attempt 6+: 30 seconds delay (max)

**Token Expiry**:
- Session tokens valid for `hostSessionTokenTimeoutMs` (default 5 minutes)
- After timeout, client must perform full connection (new client ID assigned)

### Graceful Disconnect Flow

```
┌────────┐         ┌───────┐         ┌────────┐
│ Client │         │ Relay │         │  Host  │
└───┬────┘         └───┬───┘         └───┬────┘
    │                  │                  │
    │  client.close()  │                  │
    │                  │                  │
    │  DISCONNECT_     │                  │
    │  NOTICE          │                  │
    │ ───────────────→ │                  │
    │                  │  DISCONNECT_     │
    │                  │  NOTICE          │
    │                  │ ───────────────→ │
    │                  │                  │
    │                  │  Relay removes   │
    │                  │  client from     │
    │                  │  session         │
    │                  │                  │
    │                  │                  │ Host triggers
    │                  │                  │ onClientDisconnect
    │                  │                  │
```

---

## State Machines

### Client State Machine

```
┌─────────────┐
│ DISCONNECTED│◄────────────────────┐
└──────┬──────┘                     │
       │                            │
       │ connect()                  │
       │                            │
       ▼                            │
┌─────────────┐                     │
│ CONNECTING  │                     │
└──────┬──────┘                     │
       │                            │
       │ CONNECT_ACCEPT             │ DISCONNECT_NOTICE
       │                            │ or timeout
       ▼                            │
┌─────────────┐                     │
│  CONNECTED  │─────────────────────┤
└──────┬──────┘                     │
       │                            │
       │ Network loss               │
       │                            │
       ▼                            │
┌─────────────┐                     │
│RECONNECTING │                     │
└──────┬──────┘                     │
       │                            │
       │ CONNECT_ACCEPT             │
       │ (with token)               │
       └────────────────────────────┘
       or max attempts exceeded ────┘

States:
- DISCONNECTED: No connection, idle
- CONNECTING: Sent CONNECT_REQUEST, waiting for ACCEPT
- CONNECTED: Active connection, can send/receive packets
- RECONNECTING: Lost connection, attempting to reconnect with token

Transitions:
- connect() → CONNECTING
- CONNECT_ACCEPT → CONNECTED
- CONNECT_DENY → DISCONNECTED
- Timeout → RECONNECTING (if token available) or DISCONNECTED
- DISCONNECT_NOTICE → DISCONNECTED
- Max reconnect attempts → DISCONNECTED
```

### Host State Machine

```
┌─────────────┐
│    IDLE     │
└──────┬──────┘
       │
       │ start() / register with relay
       │
       ▼
┌─────────────┐
│  REGISTERING│
└──────┬──────┘
       │
       │ Registration confirmed
       │
       ▼
┌─────────────┐
│   RUNNING   │◄───────┐
└──────┬──────┘        │
       │               │
       │               │ CONNECT_REQUEST
       │               │ → validate → CONNECT_ACCEPT
       │               │
       │               │ PING
       │               │ → respond with PONG
       │               │
       │               │ DISCONNECT_NOTICE
       │               │ → remove client
       │               │
       │               └──────────────────┐
       │                                  │
       │ close()                          │
       │                                  │
       ▼                                  │
┌─────────────┐                           │
│  STOPPING   │                           │
└──────┬──────┘                           │
       │                                  │
       │ Wait for pending ACKs            │
       │ (graceful shutdown timeout)      │
       │                                  │
       ▼                                  │
┌─────────────┐                           │
│   STOPPED   │                           │
└─────────────┘                           │
                                          │
States:                                   │
- IDLE: Not started                       │
- REGISTERING: Connecting to relay        │
- RUNNING: Active, processing packets ────┘
- STOPPING: Shutting down gracefully
- STOPPED: Shutdown complete
```

### Session Lifecycle (Relay)

```
┌──────────────┐
│  NO SESSION  │
└───────┬──────┘
        │
        │ Host registration
        │
        ▼
┌──────────────┐
│SESSION_ACTIVE│◄──────────────────┐
└───────┬──────┘                   │
        │                          │
        │                          │ Client joins
        │                          │ → add to session
        │                          │
        │                          │ Packet received
        │                          │ → update lastActivity
        │                          │
        │                          │ Client disconnects
        │                          │ → remove from session
        │                          │
        │                          └─────────────────┐
        │                                            │
        │ Host disconnects OR                        │
        │ Session timeout (no activity)              │
        │                                            │
        ▼                                            │
┌──────────────┐                                     │
│SESSION_STALE │                                     │
└───────┬──────┘                                     │
        │                                            │
        │ Cleanup task runs                          │
        │ (relayCleanupIntervalMs)                   │
        │                                            │
        ▼                                            │
┌──────────────┐                                     │
│SESSION_REMOVED│                                    │
└──────────────┘                                     │
                                                     │
States:                                              │
- NO SESSION: Session ID not registered              │
- SESSION_ACTIVE: Host + clients connected ──────────┘
- SESSION_STALE: Inactive, marked for cleanup
- SESSION_REMOVED: Cleaned up from memory
```

---

## Reliability Mechanisms

### ACK/Retry for SESSION_CONFIG

**Problem**: SESSION_CONFIG contains critical session parameters (tick rate, max packet size). Loss of this packet breaks gameplay.

**Solution**: ACK/retry mechanism (only for SESSION_CONFIG, not all packets)

**Protocol**:
```
Host                                Client
  │                                   │
  │  SESSION_CONFIG (seq=42)          │
  ├─────────────────────────────────→ │
  │                                   │
  │  Start timer (ackTimeoutMs)       │ Receive, send ACK
  │                                   │
  │          ACK (seq=42)             │
  │ ←─────────────────────────────────┤
  │                                   │
  │  ACK received, remove from        │
  │  pending queue                    │
  │                                   │

Timeout scenario:
  │                                   │
  │  SESSION_CONFIG (seq=42)          │
  ├─────────────────────────────X    │ Lost
  │                                   │
  │  Timer expires (2s)               │
  │                                   │
  │  Retry #1                         │
  ├─────────────────────────────────→ │
  │                                   │
  │          ACK (seq=42)             │
  │ ←─────────────────────────────────┤
  │                                   │
```

**Parameters** (NeonConfig):
- `hostAckTimeoutMs`: Time before retransmitting (default 2000ms)
- `hostMaxAckRetries`: Maximum retries (default 5)
- If all retries fail, host logs error but continues (game may break without config)

**Duplicate ACK Handling**:
- Client may send multiple ACKs (if retransmit received after ACK sent)
- Host ignores ACKs for packets not in pending queue (idempotent)

### Optional Reliability for Game Packets

**Design**: Application-layer reliability via `ReliablePacketManager`

**Usage**:
```java
// Create manager
ReliablePacketManager reliable = new ReliablePacketManager(
    socket, relayAddress, clientId, config
);

// Send reliable game packet
byte[] payload = myGamePacket.serialize();
reliable.sendReliable((byte) 0x10, destId, payload, (success) -> {
    if (success) {
        System.out.println("Packet delivered");
    } else {
        System.out.println("Packet failed after retries");
    }
});

// Process ACKs and retransmits
while (running) {
    reliable.process();  // Call regularly
}
```

**Implementation**:
- Uses sequence numbers from packet header
- Tracks pending packets in HashMap
- Retransmits on timeout (configurable interval)
- Removes on ACK receipt
- Invokes callback on success/failure

**Trade-offs**:
- ✅ Selective reliability (only where needed)
- ✅ Application control over timeouts and retries
- ❌ Additional complexity vs. "always reliable"
- ❌ Overhead (ACK packets, retransmit logic)

---

## Security Architecture

See [SECURITY.md](SECURITY.md) for comprehensive security documentation.

### Threat Model Summary

**Trusted**:
- Network isolation (LAN / VPN / firewall)
- Relay operator
- Physical security of servers

**Untrusted**:
- Clients (may be malicious)
- Network integrity (packets may be modified)
- Network privacy (packets may be observed)

### Security Layers

```
┌──────────────────────────────────────────────────┐
│        Application Layer (Your Game)             │
│  - Authentication (passwords, tokens, OAuth)     │
│  - Authorization (ACLs, permissions)             │
│  - Encryption (AES, ChaCha20 for sensitive data) │
│  - Input validation (game packet fields)         │
│  - Server authority (validate all state changes) │
└──────────────────────────────────────────────────┘
                       ▲
                       │ Application responsibility
                       │
┌──────────────────────▼───────────────────────────┐
│           Protocol Layer (Neon Core)             │
│  - Input validation (buffer overflow protection) │
│  - String length limits (names, descriptions)    │
│  - Session ID validation (positive integers)     │
│  - Magic number + version checks                 │
└──────────────────────────────────────────────────┘
                       ▲
                       │ Built-in
                       │
┌──────────────────────▼───────────────────────────┐
│            Relay Layer (NeonRelay)               │
│  - Rate limiting (per-client packets/sec)        │
│  - Connection limits (per session, total)        │
│  - Flood detection (violation tracking)          │
│  - Memory limits (packet queue sizes)            │
│  - Session cleanup (stale connection removal)    │
└──────────────────────────────────────────────────┘
                       ▲
                       │ Built-in (DoS protection)
                       │
┌──────────────────────▼───────────────────────────┐
│        Deployment Layer (Infrastructure)         │
│  - Firewall (allow UDP port only)                │
│  - VPN / SSH tunnel (encrypted transport)        │
│  - DDoS protection (Cloudflare, AWS Shield)      │
│  - Network isolation (private VLANs)             │
│  - Monitoring (alerts, logging)                  │
└──────────────────────────────────────────────────┘
```

### Input Validation

All core packet deserialization includes validation:

```java
// Example: ConnectRequest validation
public static ConnectRequest deserializeConnectRequest(ByteBuffer buffer) {
    byte version = buffer.get();

    // String length validation
    short nameLen = buffer.getShort();
    if (nameLen < 0 || nameLen > MAX_NAME_LENGTH) {
        throw new PacketValidationException(
            "Name length " + nameLen + " exceeds maximum " + MAX_NAME_LENGTH
        );
    }

    // Buffer bounds checking
    if (buffer.remaining() < nameLen) {
        throw new BufferUnderflowException();
    }

    byte[] nameBytes = new byte[nameLen];
    buffer.get(nameBytes);
    String name = new String(nameBytes, StandardCharsets.UTF_8);

    // Sanitization (remove control characters)
    name = name.replaceAll("\\p{Cntrl}", "");

    // ... rest of deserialization
}
```

**Protected Against**:
- Buffer overflow (length checks before reads)
- Integer overflow (validate ranges)
- Control character injection (sanitize strings)
- Malformed packets (throw exceptions, don't crash)

---

## Performance Considerations

### Latency

**Sources of Latency**:
1. **Network RTT**: 1-100ms (LAN) or 10-300ms (internet) - unavoidable
2. **Processing Loop Sleep**: `clientProcessingLoopSleepMs` (default 10ms)
3. **Relay Forwarding**: < 1ms (negligible)
4. **Packet Serialization**: < 0.1ms (ByteBuffer operations)
5. **Callback Execution**: User-defined (avoid blocking!)

**Optimization Strategies**:
- Lower processing loop sleep (trade-off: CPU usage)
- Use non-blocking socket operations (enabled by default)
- Minimize callback execution time (offload to separate thread)
- Batch game updates (send multiple changes in one packet)

**Typical Latency Budget**:
```
Total: 50ms (target for responsive gameplay)
  - Network RTT: 30ms (LAN) or varies (internet)
  - Processing: 10ms (loop sleep + logic)
  - Serialization: < 1ms
  - Relay: < 1ms
  - Render pipeline: remaining ~8ms
```

### Throughput

**Packet Size**:
- Header: 8 bytes (fixed)
- Payload: variable (keep < 1200 bytes to avoid fragmentation)
- Total: typically 50-500 bytes per packet

**Packet Rate**:
- Fast-paced games: 20-60 packets/sec per client (position updates)
- Turn-based games: 1-10 packets/sec (actions only)
- Rate limited by relay: `maxPacketsPerSecond` (default 100)

**Bandwidth Calculation**:
```
Example: 32 clients, 30 packets/sec, 200 bytes/packet

Per-client: 30 pkt/s * 200 bytes = 6 KB/s = 48 Kbps
Total relay: 32 clients * 6 KB/s = 192 KB/s = 1.5 Mbps

With broadcast: 32 * 31 (others) * 30 * 200 = 5.95 MB/s = 47.6 Mbps
(Relay must forward to all clients - significant amplification)

Recommendation: Use unicast when possible, broadcast sparingly
```

### Memory Usage

**Per-Component Memory**:
- NeonClient: ~1-5 KB (minimal state)
- NeonHost: ~5-20 KB + (num_clients * 2 KB)
- NeonRelay: ~10 MB base + (num_sessions * 1 KB) + (num_clients * 2 KB)

**Example Relay Memory**:
```
1000 total connections, 50 sessions

Base: 10 MB
Sessions: 50 * 1 KB = 50 KB
Clients: 1000 * 2 KB = 2 MB
Rate limiters: 1000 * 500 bytes = 500 KB

Total: ~12.5 MB (negligible for modern servers)
```

**Packet Queue Limits**:
- Socket receive buffer: `bufferSize` (default 1024 bytes, configurable)
- No packet buffering in relay (stateless forwarding)
- Pending ACKs in host: max `maxClientsPerSession` packets (32 * 500 bytes = 16 KB)

### CPU Usage

**Relay CPU Consumption**:
- Idle: ~0% (blocked on socket receive)
- Low load (< 100 pkt/s): < 5% (single core)
- Medium load (1000 pkt/s): ~10-20% (single core)
- High load (10,000 pkt/s): ~50-80% (single core)

**Scaling**:
- Relay is single-threaded (simplicity over parallelism)
- For higher loads: horizontal scaling (multiple relay instances, load balanced by session ID)
- Vertical scaling limited (single-threaded bottleneck)

---

## Design Decisions

### Why UDP?

**Decision**: Use UDP instead of TCP

**Rationale**:
- **Lower latency**: No connection handshake, no retransmission delays
- **No head-of-line blocking**: Lost packet doesn't block newer packets
- **Simplicity**: No connection state management at transport layer
- **Control**: Application decides what needs reliability

**Trade-offs**:
- ✅ Optimal for real-time games (position updates, animations)
- ❌ No reliability (must implement ACK/retry where needed)
- ❌ No congestion control (must implement rate limiting)
- ❌ NAT traversal challenges (relay helps but not perfect)

**Alternatives Considered**:
- TCP: Rejected due to latency and head-of-line blocking
- QUIC: Future consideration (not widely supported in Java yet)
- SCTP: Rejected (not supported on all platforms)

### Why Relay Architecture?

**Decision**: Use relay (client-relay-host) instead of peer-to-peer

**Rationale**:
- **NAT traversal**: Relay acts as rendezvous point
- **Single authority**: Host maintains authoritative game state
- **Scalability**: Relay can manage multiple sessions
- **Security**: Relay provides single point for rate limiting

**Trade-offs**:
- ✅ Works behind most NATs/firewalls
- ✅ Host has authority (no consensus needed)
- ✅ Single codebase (no separate relay and direct modes)
- ❌ Additional latency hop (client → relay → host instead of client → host)
- ❌ Relay is single point of failure
- ❌ Relay bandwidth amplification (must forward to all clients)

**Alternatives Considered**:
- Peer-to-peer: Rejected due to NAT traversal complexity
- Hybrid (direct + fallback relay): Rejected due to implementation complexity
- Dedicated servers (no relay): Possible but less flexible

### Why Sealed Interfaces?

**Decision**: Use sealed interfaces (Java 17+) for PacketPayload

**Rationale**:
- **Exhaustiveness**: Compiler ensures all packet types handled in switch
- **Type safety**: No runtime casting needed
- **Documentation**: All permitted implementations visible at a glance
- **Evolution**: Adding new packet types is explicit (must update permits)

**Trade-offs**:
- ✅ Compile-time safety
- ✅ Clear API contract
- ❌ Requires Java 17+ (acceptable trade-off)
- ❌ Less flexible than traditional inheritance

**Example**:
```java
public sealed interface PacketPayload
    permits ConnectRequest, ConnectAccept, ConnectDeny, /* ... */ {
    // Only these types can implement PacketPayload
}

// Exhaustive switch (compiler error if case missing)
switch (payload) {
    case ConnectRequest req -> handleConnectRequest(req);
    case ConnectAccept acc -> handleConnectAccept(acc);
    // ... all cases required
}
```

### Why Single-Threaded Relay?

**Decision**: Relay processes packets in a single thread

**Rationale**:
- **Simplicity**: No locking, no race conditions, easier to reason about
- **Sufficient performance**: Single thread handles 1000+ packets/sec
- **Lock-free data structures**: ConcurrentHashMap for concurrent reads
- **Predictable behavior**: No thread scheduling issues

**Trade-offs**:
- ✅ Simple implementation, fewer bugs
- ✅ Adequate for most deployments
- ❌ CPU-bound (limited to single core)
- ❌ Cannot scale vertically beyond single-core performance

**Future**: If needed, can parallelize by session (each session in own thread)

### Why No Built-in Encryption?

**Decision**: No encryption in core protocol

**Rationale**:
- **Performance**: Encryption adds latency and CPU overhead
- **Flexibility**: Different games have different security needs
- **Simplicity**: Keep core protocol minimal
- **Layering**: Security should be at deployment or application layer

**Trade-offs**:
- ✅ Minimal latency for trusted networks
- ✅ Application chooses encryption approach
- ❌ Not secure by default
- ❌ Deployers must understand security implications

**Recommendation**: Use VPN/SSH tunnel for internet deployment, or implement application-layer encryption (AES-GCM) for sensitive data.

---

## Future Architecture

### Recently Implemented (v1.1)

1. **Full-size receive buffers + enforcement**: Default buffer size increased to 65535 bytes with
   truncation detection and enforcement options via `NeonConfig.setEnforceBufferSize()`.

2. **Honest relay semantics**: `RelaySemantics` class provides deterministic, transparent packet
   routing with explicit routing decisions (Unicast, Broadcast, Unroutable, RelayHandled).

3. **Unsealed payload architecture**: `PacketPayload` is now a non-sealed interface allowing custom
   payload types. Use `PayloadRegistry.register()` to add custom deserializers for game packets.

4. **Centralized ACK state machine**: `AckStateMachine` provides unified acknowledgment tracking
   with timeout detection, retry signaling, and failure callbacks for both host and client.

5. **Event-driven receive loop**: `EventDrivenReceiver` uses NIO Selector for non-blocking I/O,
   eliminating sleep-based polling. Enable via `NeonConfig.setUseEventDrivenReceiver(true)`.

6. **Game packet registry with subtype/version/validation**: `GamePacketRegistry` and
   `GamePacketDescriptor` provide rich metadata, subtype ranges, versioning support, and
   custom validators for game packet types. Extends `PayloadRegistry` with schema evolution.

7. **Core metrics**: `NeonMetrics` provides thread-safe, lock-free metrics collection including
   packet counts, byte counts, latency tracking (min/max/avg), error counts by type, and
   connection statistics. Supports snapshots for monitoring and Prometheus integration.

8. **Runtime configuration**: `RuntimeConfig` enables hot-reload configuration with change
   listeners, file-based persistence, and bidirectional conversion to/from `NeonConfig`.
   Supports reactive updates without restart.

9. **Payload size enforcement**: `PayloadSizeEnforcer` validates payload sizes with configurable
   limits, strict mode for exceptions, integration with `GamePacketRegistry` per-type limits,
   and optional automatic truncation with warnings.

10. **Explicit version mismatch handling**: `VersionMismatchHandler` provides configurable
    version compatibility checking with strict/lenient modes, version range support, suggested
    upgrade/downgrade actions, and mismatch event listeners.

11. **Clean start/stop lifecycle**: `Lifecycle` interface provides consistent state machine
    (CREATED → STARTING → RUNNING → STOPPING → STOPPED) for all components with state change
    listeners. `NeonHost`, `NeonClient`, and `NeonRelay` all implement `Lifecycle`.

12. **Transport interface**: `Transport` interface abstracts the transport layer, with `UdpTransport`
    as the default implementation. Enables future TCP/QUIC support without changing component code.

13. **Structured logging**: `StructuredLogger` provides JSON-formatted logs with key-value context,
    log entry builders, and JUL handler integration. Enable JSON mode via `setJsonEnabled(true)`.

14. **Better backpressure signals**: `Backpressure` provides flow control with high/low water marks,
    signal states (NORMAL, WARNING, CRITICAL, RECOVERED), rate tracking, and listener notifications.

15. **Session state serialization**: `SessionState` defines a binary format for persisting session
    data including clients, tokens, and custom application data. Foundation for session recovery.

### Post-1.0 Features

1. **Save relay state to disk periodically**: Use `SessionState` format for persistence
2. **Restore sessions on relay restart**: Deserialize `SessionState` on startup
3. **Configurable persistence backend**: Pluggable storage (file, database, Redis)
4. **DTLS Support**: Optional encryption at transport layer
5. **NAT Traversal**: STUN/TURN integration for symmetric NAT
6. **IPv6 Support**: Dual-stack (IPv4 + IPv6)
7. **Compression**: Optional payload compression for large data
8. **WebSocket Variant**: Browser client support (Unity WebGL, HTML5 games)

---

## Appendix: Key Interfaces

### PacketPayload (Extensible Interface)

```java
public interface PacketPayload {
    byte[] toBytes();

    // Built-in record types: ConnectRequest, ConnectAccept, ConnectDeny,
    // SessionConfig, PacketTypeRegistry, Ping, Pong, DisconnectNotice,
    // Ack, ReconnectRequest, GamePacket

    // Custom payloads can be registered via PayloadRegistry:
    // PayloadRegistry.register((byte) 0x20, MyCustomPayload::fromBytes);
}
```

### NeonConfig (Configuration)

```java
public class NeonConfig {
    // 30+ configuration parameters
    // Grouped by: Socket, Client, Host, Relay, Limits, Rate Limiting, Protocol

    public static class Builder {
        // Fluent API for configuration
        public Builder setClientPingIntervalMs(long ms) { ... }
        public Builder setMaxPacketsPerSecond(int max) { ... }
        // ... 28 more parameters
        public NeonConfig build() { ... }  // Validates config
    }

    public void validate() throws IllegalArgumentException {
        // Comprehensive validation of all parameters
    }
}
```

### Callback Interfaces

```java
// Host callbacks
@FunctionalInterface
public interface ClientConnectCallback {
    void onConnect(byte clientId, String name, int sessionId);
}

// Client callbacks
@FunctionalInterface
public interface PongCallback {
    void onPong(long responseTimeMs, long originalTimestamp);
}

// ... 9 more callback types
```

### GamePacketRegistry (Typed Packet Registration)

```java
public final class GamePacketRegistry {
    // Register a game packet type with metadata and validation
    public static void register(GamePacketDescriptor descriptor, PayloadDeserializer<?> deserializer);

    // Register additional versions for schema evolution
    public static void registerVersion(byte packetType, int version, PayloadDeserializer<?> deserializer);

    // Validate payloads against registered constraints
    public static Optional<String> validatePayload(byte packetType, byte[] payload);
    public static Optional<String> validatePayload(byte packetType, int subtype, byte[] payload);

    // Query registered types
    public static Optional<GamePacketDescriptor> getDescriptor(byte packetType);
    public static Set<Integer> getRegisteredVersions(byte packetType);
}
```

### NeonMetrics (Core Metrics)

```java
public final class NeonMetrics {
    public static NeonMetrics create();

    // Record activity
    public void recordPacketSent(int sizeBytes);
    public void recordPacketReceived(int sizeBytes);
    public void recordLatency(long latencyMs);
    public void recordError(String errorType);

    // Get snapshot for monitoring
    public Snapshot snapshot();

    public record Snapshot(
        long packetsSent, long packetsReceived, long packetsDropped,
        long bytesSent, long bytesReceived,
        double minLatencyMs, double maxLatencyMs, double averageLatencyMs,
        // ... additional fields
    ) {
        public double packetsPerSecond();
        public double dropRatePercent();
    }
}
```

### RuntimeConfig (Hot-Reload Configuration)

```java
public final class RuntimeConfig {
    public static RuntimeConfig create();
    public static RuntimeConfig fromNeonConfig(NeonConfig config);

    // Get/set values with type safety
    public int getInt(String key);
    public void setInt(String key, int value);
    // ... boolean, long, String variants

    // React to changes
    public void addListener(String key, ConfigChangeListener listener);

    // Persistence
    public void loadFromFile(Path path) throws IOException;
    public void saveToFile(Path path) throws IOException;

    // Convert to NeonConfig
    public NeonConfig toNeonConfig();
}
```

### VersionMismatchHandler (Version Compatibility)

```java
public final class VersionMismatchHandler {
    public static VersionMismatchHandler create();
    public static VersionMismatchHandler strict();
    public static VersionMismatchHandler lenient(byte minVersion, byte maxVersion);

    // Check version compatibility
    public Result check(byte version);
    public void validate(byte version) throws VersionMismatchException;

    // Configure behavior
    public VersionMismatchHandler onMismatch(BiConsumer<Byte, Byte> listener);

    public record Result(
        byte receivedVersion, byte expectedVersion,
        boolean isAccepted, SuggestedAction suggestedAction
    ) {}

    public enum ActionType { UPGRADE_REQUIRED, DOWNGRADE_SUGGESTED, INCOMPATIBLE }
}
```

### Lifecycle (Component Lifecycle)

```java
public interface Lifecycle {
    enum State { CREATED, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

    State getState();
    void start();
    void stop();

    default boolean isRunning() { return getState() == State.RUNNING; }
    default boolean isTerminated() {
        State s = getState();
        return s == State.STOPPED || s == State.FAILED;
    }

    void addStateChangeListener(StateChangeListener listener);
    void removeStateChangeListener(StateChangeListener listener);

    @FunctionalInterface
    interface StateChangeListener {
        void onStateChange(State oldState, State newState, Throwable cause);
    }
}
```

### Transport (Transport Layer)

```java
public interface Transport extends AutoCloseable {
    enum Type { UDP, TCP, QUIC }

    Type getType();
    void bind(int port) throws IOException;
    SocketAddress getLocalAddress();
    void send(byte[] data, SocketAddress address) throws IOException;
    ReceivedData receive() throws IOException;
    void setBlocking(boolean blocking) throws IOException;
    void setTimeout(int timeoutMs) throws IOException;
    boolean isClosed();

    record ReceivedData(byte[] data, SocketAddress source) {}
}
```

### StructuredLogger (Structured Logging)

```java
public final class StructuredLogger {
    public static StructuredLogger getLogger(String name);
    public static void setJsonEnabled(boolean enabled);
    public static void setOutput(Consumer<String> output);

    public StructuredLogger withContext(String key, Object value);

    public LogEntry info(String message);
    public LogEntry warn(String message);
    public LogEntry error(String message);

    public class LogEntry {
        public LogEntry with(String key, Object value);
        public LogEntry withException(Throwable t);
        public void log();
    }
}
```

### Backpressure (Flow Control)

```java
public final class Backpressure {
    public static Backpressure create();

    public Backpressure setHighWaterMark(int mark);
    public Backpressure setLowWaterMark(int mark);
    public Backpressure addListener(Listener listener);

    public int recordEnqueue();
    public int recordDequeue();
    public void recordDrop();
    public boolean shouldPause();
    public Signal getCurrentSignal();
    public State getState();

    public enum Signal { NORMAL, WARNING, CRITICAL, RECOVERED }

    public record State(int currentDepth, int highWaterMark, /* ... */) {
        public boolean shouldPause();
        public double fillRatio();
    }

    @FunctionalInterface
    interface Listener {
        void onSignalChange(Signal signal, State state);
    }
}
```

### SessionState (Session Persistence)

```java
public final class SessionState {
    public static Builder builder(int sessionId);
    public static SessionState fromBytes(byte[] data);
    public static SessionState fromStream(InputStream in) throws IOException;

    public byte[] toBytes();
    public void toStream(OutputStream out) throws IOException;

    public int getSessionId();
    public long getHostToken();
    public List<ClientState> getClients();
    public byte[] getCustomData();

    public record ClientState(byte clientId, long token, String name,
                              Instant connectedAt, boolean isHost) {}
}
```

---

**End of Architecture Documentation**

For implementation details, see source code in `src/main/java/com/quietterminal/projectneon/`

For security details, see [SECURITY.md](SECURITY.md)

For contributing guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md)
