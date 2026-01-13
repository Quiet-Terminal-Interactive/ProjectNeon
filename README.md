# Project Neon Protocol Specification

Version: 0.2
Author: Kohan Mathers

---

## Overview

Project Neon is a **fully modular**, relay-based multiplayer protocol that is completely game-agnostic. Unlike traditional protocols with hardcoded game features, Neon provides only the bare essentials for connection management, leaving all game-specific logic to be defined by the application layer.

This allows true crossovers by letting each game define its own packet vocabulary.

---

## Core Design Philosophy

- **Minimal Core**: Only connection/session management is hardcoded
- **Zero Assumptions**: No built-in movement, inventory, combat, or any game mechanics
- **Dynamic Packet Registry**: Games register their own packet types at runtime
- **Universal Relay**: Relay forwards anything without understanding it
- **Complete Modularity**: From simple chat apps to complex MMOs using the same base protocol

---

## Packet Layout

All Neon packets follow this structure:

```java
record NeonPacket(
    PacketHeader header,
    PacketPayload payload  // Raw bytes - game interprets
) {}
```

### PacketHeader

```java
record PacketHeader(
    short magic,         // 0x4E45 = "NE"
    byte version,        // Protocol version (core only)
    byte packetType,     // See packet types below
    short sequence,      // For ordering/reliability
    byte clientId,       // Sender
    byte destinationId   // Target (0 = broadcast, 1 = host, 2+ = clients)
) {}
```

---

## Core Packet Types

**Only these packet types are part of the core protocol:**

```java
enum PacketType {
    // Connection Management (0x01-0x0F reserved)
    CONNECT_REQUEST((byte) 0x01),
    CONNECT_ACCEPT((byte) 0x02),
    CONNECT_DENY((byte) 0x03),
    SESSION_CONFIG((byte) 0x04),
    PACKET_TYPE_REGISTRY((byte) 0x05),
    PING((byte) 0x0B),
    PONG((byte) 0x0C),
    DISCONNECT_NOTICE((byte) 0x0D),
    ACK((byte) 0x0E),

    // Game-Defined Range (0x10-0xFF)
    GAME_PACKET((byte) 0x10)  // Everything else is application-defined
}
```

---

## Core Packet Payloads

### ConnectRequest

```java
record ConnectRequest(
    byte clientVersion,      // Client's protocol version
    String desiredName,      // Display name
    int targetSessionId,     // Which session to join
    int gameIdentifier       // Game hash/ID (optional validation)
) implements PacketPayload {}
```

### ConnectAccept

```java
record ConnectAccept(
    byte assignedClientId,
    int sessionId
) implements PacketPayload {}
```

### ConnectDeny

```java
record ConnectDeny(
    String reason
) implements PacketPayload {}
```

### SessionConfig

```java
record SessionConfig(
    byte version,            // Session protocol version
    short tickRate,          // Server tick rate (informational)
    short maxPacketSize      // MTU hint
) implements PacketPayload {}
```

### PacketTypeRegistry

Allows host to share packet type definitions with clients (optional, for debugging/tooling):

```java
record PacketTypeRegistry(
    List<PacketTypeEntry> entries
) implements PacketPayload {}

record PacketTypeEntry(
    byte packetId,           // e.g., 0x10
    String name,             // e.g., "PlayerMovement"
    String description       // Optional schema info
) {}
```

### Ping/Pong

```java
record Ping(
    long timestamp
) implements PacketPayload {}

record Pong(
    long originalTimestamp
) implements PacketPayload {}
```

---

## Game-Defined Packets (0x10+)

**Everything from 0x10 onwards is application-defined.** The protocol doesn't care what you send.

---

## Game Packet Structure

Games are free to structure their payloads however they want:

```java
// Example: A movement packet
record GameMovementPacket(
    int actorId,
    float[] position,  // [x, y, z]
    float[] rotation,  // [x, y, z, w]
    float[] velocity   // [vx, vy, vz]
    // ... whatever the game needs
) {
    byte[] serialize() {
        // Your serialization logic
    }
}

// Sent as:
NeonPacket packet = NeonPacket.create(
    PacketType.GAME_PACKET,
    sequenceNumber,
    clientId,
    destinationId,
    new PacketPayload.GamePacket(movement.serialize())
);
```

---

## Relay Behavior

The relay is **completely payload-agnostic**:

1. Receives packet
2. Validates header (magic, version)
3. Routes based on `destination_id`
4. Forwards raw bytes without parsing payload

**The relay never needs to understand game packets.**

---

## Session Discovery & Matching

Since there are no feature flags, games identify compatibility through:

1. **Game Identifier**: Hash or ID in ConnectRequest
2. **Version Checking**: Host can reject incompatible clients
3. **PacketTypeRegistry**: Optional negotiation of supported packets
4. **Out-of-band Matching**: External matchmaking services

---

## Benefits of This Approach

### Complete Freedom
- FPS, RPG, puzzle, chat app - all use same protocol
- No protocol updates needed for new game types

### True Modularity
- Replace/extend any packet type without core changes
- Multiple games can coexist on same relay

### Crossover Support
- Shared packet types for common features
- Game-specific packets ignored by others
- Universal translator pattern possible

### Simplicity
- Core protocol is tiny (~8 packet types)
- Games handle their own complexity
- Relay is dumb and fast

---

## Implementation Strategy

### For Game Developers

1. Define your packet types (0x10+)
2. Implement serialization for your packets
3. Send/receive through Neon core
4. Optionally share PacketTypeRegistry for debugging

### Example Code Structure

```java
// Game-specific packet handler
PacketType type = PacketType.fromByte(packet.header().packetType());
if (type.isCorePacket()) {
    coreHandler.handle(packet);
} else {
    gameHandler.handle(packet);
}
```

---

## Usage

### Building from Source

Project Neon is now implemented in **Java 21** with full Maven support.

```bash
# Clone the repository
git clone https://github.com/QuietTerminal/ProjectNeon
cd ProjectNeon

# Build with Maven
mvn clean package

# Standalone executable JARs will be created in target/:
# - neon-relay.jar (relay server)
# - neon-host.jar (example host)
# - neon-client.jar (example client)
# - project-neon-0.2.0.jar (library for integration)
```

### Running the Components

#### Relay Server

```bash
# Run relay on default port (7777)
java -jar target/neon-relay.jar

# Or specify a custom bind address
java -jar target/neon-relay.jar --bind 0.0.0.0:8888

# Or use Maven exec plugin
mvn exec:java@relay
```

#### Host

```bash
# Run host with auto-generated session ID
java -jar target/neon-host.jar

# Or specify a session ID
java -jar target/neon-host.jar 12345

# Or use Maven exec plugin
mvn exec:java@host
```

#### Client

```bash
# Run interactive client
java -jar target/neon-client.jar

# Or use Maven exec plugin
mvn exec:java@client
```

### Java Integration

Add Project Neon to your Java project:

**Maven:**
```xml
<dependency>
    <groupId>com.quietterminal</groupId>
    <artifactId>project-neon</artifactId>
    <version>0.2.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'com.quietterminal:project-neon:0.2.0'
```

**Example Usage:**

```java
import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.host.NeonHost;

// Create and connect a client
try (NeonClient client = new NeonClient("PlayerName")) {
    client.setPongCallback((responseTime, timestamp) ->
        System.out.println("Pong! Response time: " + responseTime + "ms")
    );

    if (client.connect(12345, "127.0.0.1:7777")) {
        System.out.println("Connected! ID: " + client.getClientId().orElse((byte) 0));

        // Game loop
        while (gameRunning) {
            client.processPackets();
            // Your game logic here
        }
    }
}

// Create a host
try (NeonHost host = new NeonHost(12345, "127.0.0.1:7777")) {
    host.setClientConnectCallback((clientId, name, sessionId) ->
        System.out.println("Client connected: " + name)
    );

    // Start host (blocking)
    host.start();
}
```

### Configuration

Project Neon provides comprehensive configuration options through the `NeonConfig` class. All timing values are in milliseconds, and all size values are in bytes.

**Creating a Custom Configuration:**

```java
import com.quietterminal.projectneon.core.NeonConfig;

NeonConfig config = new NeonConfig()
    .setClientPingIntervalMs(3000)           // Send pings every 3 seconds
    .setClientConnectionTimeoutMs(15000)     // 15 second connection timeout
    .setHostAckTimeoutMs(1000)               // 1 second ACK timeout
    .setMaxClientsPerSession(64)             // Allow up to 64 clients per session
    .setBufferSize(2048);                    // 2KB receive buffer

config.validate();  // Validate all values before using

// Use with client
NeonClient client = new NeonClient("PlayerName", config);

// Use with host
NeonHost host = new NeonHost(12345, "127.0.0.1:7777", config);

// Use with relay
NeonRelay relay = new NeonRelay("0.0.0.0:7777", config);
```

**Configuration Categories:**

| Category | Parameters | Default | Description |
|----------|-----------|---------|-------------|
| **Socket** | `bufferSize` | 1024 bytes | UDP receive buffer size |
| **Client** | `clientPingIntervalMs` | 5000 ms | How often to send keep-alive pings |
| | `clientConnectionTimeoutMs` | 10000 ms | Timeout for connection attempts |
| | `clientMaxReconnectAttempts` | 5 | Maximum automatic reconnection tries |
| | `clientInitialReconnectDelayMs` | 1000 ms | Initial reconnection backoff |
| | `clientMaxReconnectDelayMs` | 30000 ms | Maximum reconnection backoff |
| | `clientSocketTimeoutMs` | 100 ms | Non-blocking socket timeout |
| | `clientProcessingLoopSleepMs` | 10 ms | Main loop sleep interval |
| | `clientDisconnectNoticeDelayMs` | 50 ms | Delay before disconnect notice |
| **Host** | `hostAckTimeoutMs` | 2000 ms | Timeout before retransmitting |
| | `hostMaxAckRetries` | 5 | Maximum retransmission attempts |
| | `hostReliabilityDelayMs` | 50 ms | Delay before sending session config |
| | `hostGracefulShutdownTimeoutMs` | 2000 ms | Max time to wait for pending ACKs |
| | `hostSessionTokenTimeoutMs` | 300000 ms | Reconnection window (5 minutes) |
| | `hostSocketTimeoutMs` | 100 ms | Non-blocking socket timeout |
| | `hostProcessingLoopSleepMs` | 10 ms | Main loop sleep interval |
| **Relay** | `relayPort` | 7777 | Default relay server port |
| | `relayCleanupIntervalMs` | 5000 ms | Session cleanup interval |
| | `relayClientTimeoutMs` | 15000 ms | Stale client cleanup timeout |
| | `relaySocketTimeoutMs` | 100 ms | Non-blocking socket timeout |
| | `relayMainLoopSleepMs` | 1 ms | Main loop sleep interval |
| | `relayPendingConnectionTimeoutMs` | 30000 ms | Pending connection timeout |
| **Limits** | `maxPacketsPerSecond` | 100 | Rate limit per client |
| | `maxClientsPerSession` | 32 | Maximum clients per session |
| | `maxTotalConnections` | 1000 | Maximum total relay connections |
| | `maxPendingConnections` | 100 | Maximum pending connections |
| | `maxRateLimiters` | 2000 | Maximum rate limiter instances |
| **Rate Limiting** | `floodThreshold` | 3 | Violations before throttling |
| | `floodWindowMs` | 10000 ms | Flood detection time window |
| | `throttlePenaltyDivisor` | 2 | Rate reduction factor when throttled |
| | `tokenRefillIntervalMs` | 1000 ms | Token bucket refill interval |
| **Reliable Packets** | `reliablePacketTimeoutMs` | 2000 ms | Timeout for reliable packets |
| | `reliablePacketMaxRetries` | 5 | Max retries for reliable packets |
| **Protocol Limits** | `maxNameLength` | 64 chars | Maximum name length (protocol) |
| | `maxDescriptionLength` | 256 chars | Maximum description length |
| | `maxPacketCount` | 100 | Maximum packet types/ACKs per packet |
| | `maxPayloadSize` | 65507 bytes | Maximum UDP payload size |

**Tuning Recommendations:**

- **Fast-paced games on reliable networks:** Lower timeouts (clientPingIntervalMs: 2000, hostAckTimeoutMs: 1000)
- **Turn-based games or unreliable networks:** Higher timeouts (clientPingIntervalMs: 10000, hostAckTimeoutMs: 5000)
- **High-bandwidth data transfer:** Larger buffers (bufferSize: 4096 or 8192)
- **Public servers:** Stricter rate limits (maxPacketsPerSecond: 50, maxClientsPerSession: 16)
- **Private/LAN servers:** Relaxed limits (maxPacketsPerSecond: 200, maxClientsPerSession: 128)

**Note:** Protocol limit parameters (`maxNameLength`, `maxDescriptionLength`, `maxPacketCount`, `maxPayloadSize`) are part of the wire format specification and should generally not be changed as they ensure protocol compatibility.

### C/C++ Integration via JNI

For integrating with C/C++ applications (Unreal Engine, Unity, custom engines):

**Required Files:**
- `libneon_jni.so` (Linux) / `neon_jni.dll` (Windows) / `libneon_jni.dylib` (macOS)
- `project_neon.h` (located in `src/main/native/`)
- `project-neon-0.2.0.jar`

**Building JNI Library:**

```bash
# Generate JNI headers
cd src/main/java
javac -h ../native com/quietterminal/projectneon/jni/*.java

# Compile native library (Linux)
cd ../native
gcc -shared -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux \
    -o libneon_jni.so neon_jni.c

# Install library
sudo cp libneon_jni.so /usr/local/lib/
sudo ldconfig
```

**Basic C Usage:**

```c
#include "project_neon.h"

// Create and connect a client
NeonClientHandle* client = neon_client_new("PlayerName");
if (neon_client_connect(client, 12345, "127.0.0.1:7777")) {
    printf("Connected! Client ID: %u\n", neon_client_get_id(client));
}

// In your game loop
while (game_running) {
    neon_client_process_packets(client);
    // Your game logic here
}

// Cleanup
neon_client_free(client);
```

**Linking in Your Build System:**

**CMake:**
```cmake
target_include_directories(YourProject PRIVATE ${NEON_INCLUDE_DIR})
target_link_libraries(YourProject neon_jni)
```

**Unreal Engine (.Build.cs):**
```csharp
PublicIncludePaths.Add(Path.Combine(ModuleDirectory, "ThirdParty/Neon/include"));
PublicAdditionalLibraries.Add(Path.Combine(ModuleDirectory, "ThirdParty/Neon/lib/libneon_jni.so"));
```

**Manual GCC:**
```bash
gcc -o mygame main.c -I./include -L./lib -lneon_jni -lpthread
```

### Testing Your Setup

```bash
# Build the project
mvn clean package

# Run the integration test
mvn test-compile
java -cp target/classes:target/test-classes NeonTest
```

The test program will create a relay, host, and two clients, demonstrating the full connection flow.

---

## Security Considerations

**IMPORTANT: Project Neon is designed for trusted local networks or controlled environments. Read this section carefully before deploying.**

### No Encryption

Project Neon uses **plaintext UDP** - all packets are transmitted unencrypted. This means:

- Any network observer can read packet contents
- Packet contents can be modified in transit
- Sensitive data (passwords, personal info) will be exposed

**Recommendations:**
- Deploy on isolated/trusted networks only
- Use VPN or SSH tunnels for internet deployment
- Never transmit sensitive data without application-layer encryption
- Consider implementing TLS/DTLS at the application layer if needed

### No Built-in Authentication

Project Neon has **no authentication mechanism**:

- Anyone can connect to any session if they know the session ID
- Session IDs are just integers - easily guessable
- No password protection for sessions
- No verification of client identity

**Recommendations:**
- Implement authentication at the application layer
- Use the `gameIdentifier` field in `ConnectRequest` as a shared secret/token
- Add password validation in your host's connection callback
- Use external authentication services (OAuth, etc.) before allowing connection
- Consider session tokens or invite codes for matchmaking

### Denial of Service Protection

Project Neon includes basic DoS protections (as of v0.2.0):

- Per-client rate limiting (configurable packets per second)
- Maximum connections per session
- Maximum total connections to relay
- Packet flood detection and throttling
- Memory usage limits for packet queues

**However, sophisticated attacks may still succeed:**
- UDP amplification attacks
- Resource exhaustion through many sessions
- Malformed packet fuzzing

**Recommendations:**
- Run relay behind a firewall with rate limiting
- Use fail2ban or similar tools to block abusive IPs
- Monitor relay resource usage
- Implement application-layer abuse detection

### Input Validation

Project Neon validates core protocol inputs (as of v0.2.0):

- Buffer overflow protection on all packet types
- String length limits (names, descriptions)
- Packet count limits
- Session ID validation

**Your application must validate game packets:**
- All game-defined packets (0x10+) are forwarded raw
- The relay does not inspect or validate game packet payloads
- Malicious clients can send arbitrary data

**Recommendations:**
- Validate all game packet fields before processing
- Use bounds checking on array indices and sizes
- Sanitize user-provided strings
- Implement server-side authority for game state

### UDP Unreliability

UDP provides **no delivery guarantees**:

- Packets may be lost, duplicated, or arrive out-of-order
- No built-in congestion control
- NAT/firewall traversal issues

**Recommendations:**
- Implement reliability for critical game events (optional ACK layer)
- Design game logic to tolerate packet loss
- Use sequence numbers for ordering (provided in header)
- Consider STUN/TURN for NAT traversal in internet deployment
- Test on realistic network conditions (packet loss, latency)

### Game Developer Responsibilities

**As a game developer using Project Neon, you MUST:**

1. Implement authentication if your game requires it
2. Encrypt sensitive data at the application layer
3. Validate all incoming game packets (0x10+)
4. Implement server-side authority for game state
5. Rate-limit game actions to prevent abuse
6. Design for UDP unreliability (packet loss tolerance)
7. Never trust client-provided data without validation

### Deployment Recommendations

**For Local/LAN Gaming:**
- Project Neon is ideal for trusted local networks
- Minimal security overhead for low-latency gameplay

**For Internet Deployment:**
- Run relay behind firewall/VPN
- Implement application-layer authentication
- Encrypt sensitive data before sending
- Use monitoring and logging
- Consider DDoS protection services
- Read [SECURITY.md](SECURITY.md) for detailed threat model

### Security Disclosure

If you discover a security vulnerability in Project Neon's core protocol, please report it via email to kohanmathersmcgonnell@gmail.com

See [SECURITY.md](SECURITY.md) for detailed threat model, attack scenarios, and mitigations.

---

## Future Possibilities

- **Universal Game Protocol Library**: Common packet types (movement, chat, etc.)
- **Cross-Game Standards**: Agreed-upon packet IDs for interoperability
- **Protocol Bridges**: Translate between different games' packet formats
- **Visual Packet Inspector**: Debug tool that uses PacketTypeRegistry