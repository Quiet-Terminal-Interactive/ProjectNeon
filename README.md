# Project Neon Protocol Specification

![Version](https://img.shields.io/badge/version-1.1.0-blue.svg)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)
![Java](https://img.shields.io/badge/java-21+-orange.svg)
![License](https://img.shields.io/badge/license-TBD-lightgrey.svg)
![Coverage](https://img.shields.io/badge/coverage-80%25-green.svg)

**Version:** 1.1.0
**Author:** Kohan Mathers
**API Documentation:** [JavaDoc](https://quietterminal.github.io/ProjectNeon/)

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
git clone https://github.com/Quiet-Terminal-interactive/ProjectNeon
cd ProjectNeon

# Build with Maven
mvn clean package

# Standalone executable JARs will be created in target/:
# - neon-relay.jar (relay server)
# - neon-host.jar (example host)
# - neon-client.jar (example client)
# - project-neon-1.1.0.jar (library for integration)
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
    <version>1.1.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'com.quietterminal:project-neon:1.1.0'
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

| Category             | Parameters                        | Default     | Description                          |
| -------------------- | --------------------------------- | ----------- | ------------------------------------ |
| **Socket**           | `bufferSize`                      | 1024 bytes  | UDP receive buffer size              |
| **Client**           | `clientPingIntervalMs`            | 5000 ms     | How often to send keep-alive pings   |
|                      | `clientConnectionTimeoutMs`       | 10000 ms    | Timeout for connection attempts      |
|                      | `clientMaxReconnectAttempts`      | 5           | Maximum automatic reconnection tries |
|                      | `clientInitialReconnectDelayMs`   | 1000 ms     | Initial reconnection backoff         |
|                      | `clientMaxReconnectDelayMs`       | 30000 ms    | Maximum reconnection backoff         |
|                      | `clientSocketTimeoutMs`           | 100 ms      | Non-blocking socket timeout          |
|                      | `clientProcessingLoopSleepMs`     | 10 ms       | Main loop sleep interval             |
|                      | `clientDisconnectNoticeDelayMs`   | 50 ms       | Delay before disconnect notice       |
| **Host**             | `hostAckTimeoutMs`                | 2000 ms     | Timeout before retransmitting        |
|                      | `hostMaxAckRetries`               | 5           | Maximum retransmission attempts      |
|                      | `hostReliabilityDelayMs`          | 50 ms       | Delay before sending session config  |
|                      | `hostGracefulShutdownTimeoutMs`   | 2000 ms     | Max time to wait for pending ACKs    |
|                      | `hostSessionTokenTimeoutMs`       | 300000 ms   | Reconnection window (5 minutes)      |
|                      | `hostSocketTimeoutMs`             | 100 ms      | Non-blocking socket timeout          |
|                      | `hostProcessingLoopSleepMs`       | 10 ms       | Main loop sleep interval             |
| **Relay**            | `relayPort`                       | 7777        | Default relay server port            |
|                      | `relayCleanupIntervalMs`          | 5000 ms     | Session cleanup interval             |
|                      | `relayClientTimeoutMs`            | 15000 ms    | Stale client cleanup timeout         |
|                      | `relaySocketTimeoutMs`            | 100 ms      | Non-blocking socket timeout          |
|                      | `relayMainLoopSleepMs`            | 1 ms        | Main loop sleep interval             |
|                      | `relayPendingConnectionTimeoutMs` | 30000 ms    | Pending connection timeout           |
| **Limits**           | `maxPacketsPerSecond`             | 100         | Rate limit per client                |
|                      | `maxClientsPerSession`            | 32          | Maximum clients per session          |
|                      | `maxTotalConnections`             | 1000        | Maximum total relay connections      |
|                      | `maxPendingConnections`           | 100         | Maximum pending connections          |
|                      | `maxRateLimiters`                 | 2000        | Maximum rate limiter instances       |
| **Rate Limiting**    | `floodThreshold`                  | 3           | Violations before throttling         |
|                      | `floodWindowMs`                   | 10000 ms    | Flood detection time window          |
|                      | `throttlePenaltyDivisor`          | 2           | Rate reduction factor when throttled |
|                      | `tokenRefillIntervalMs`           | 1000 ms     | Token bucket refill interval         |
| **Reliable Packets** | `reliablePacketTimeoutMs`         | 2000 ms     | Timeout for reliable packets         |
|                      | `reliablePacketMaxRetries`        | 5           | Max retries for reliable packets     |
| **Protocol Limits**  | `maxNameLength`                   | 64 chars    | Maximum name length (protocol)       |
|                      | `maxDescriptionLength`            | 256 chars   | Maximum description length           |
|                      | `maxPacketCount`                  | 100         | Maximum packet types/ACKs per packet |
|                      | `maxPayloadSize`                  | 65507 bytes | Maximum UDP payload size             |

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
- `project-neon-1.1.0.jar`

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

Project Neon includes basic DoS protections (as of v1.1.0):

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

Project Neon validates core protocol inputs (as of v1.1.0):

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

- **Packet Loss**: UDP does not guarantee packets will arrive. Expect 0.1%-5% loss on typical networks, up to 30% on poor connections.
- **Out-of-Order Delivery**: Packets may arrive in a different order than sent. Use the sequence number field in the packet header for ordering.
- **Duplication**: The same packet may be delivered multiple times. Track sequence numbers to detect duplicates.
- **No Congestion Control**: UDP does not slow down when the network is congested. Applications must implement their own flow control.
- **NAT/Firewall Issues**: UDP requires port forwarding or NAT traversal for internet deployment.

**Recommendations:**
- **Design for packet loss**: Make game logic tolerant of missing updates. Use interpolation/extrapolation for player positions.
- **Implement reliability selectively**: Use the optional `ReliablePacketManager` or ACK mechanism for critical events (player death, item pickup, score updates).
- **Use sequence numbers**: The packet header includes a sequence field - use it to detect out-of-order packets and duplicates.
- **Test on realistic conditions**: Use network emulation tools (tc, netem, Clumsy) to simulate packet loss and latency during development.
- **Consider STUN/TURN**: For internet deployment with NAT traversal, integrate STUN/TURN servers (not included in Project Neon).
- **Send redundant data**: For critical state, send updates multiple times (e.g., send player position every frame, so loss of one packet doesn't matter).

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

## Troubleshooting

### Build Issues

**Problem: Maven build fails with "invalid target release: 21"**
```
Solution: Ensure Java 21 or later is installed and JAVA_HOME is set correctly.
  $ java -version  # Should show Java 21+
  $ export JAVA_HOME=/path/to/java21
```

**Problem: JNI headers not generated**
```
Solution: Ensure maven-compiler-plugin is configured with -h flag in pom.xml.
  This should be automatic - check <compilerArgs><arg>-h</arg></compilerArgs>
```

**Problem: Tests fail with "Address already in use"**
```
Solution: Another process is using port 7777. Kill it or change the relay port:
  $ lsof -i :7777  # Find process using port
  $ kill <PID>     # Kill the process
  Or configure a different port in NeonConfig.
```

### Connection Issues

**Problem: Client cannot connect to relay**
```
Symptoms: Connection timeout, "No response from relay"
Causes:
  1. Relay not running - Start relay first: java -jar target/neon-relay.jar
  2. Firewall blocking UDP port 7777 - Add firewall rule to allow UDP 7777
  3. Wrong relay address - Verify address matches relay bind address
  4. Network isolation - Ensure client can reach relay (ping the host)

Solutions:
  $ java -jar target/neon-relay.jar  # Start relay
  $ sudo ufw allow 7777/udp          # Linux firewall
  $ netstat -an | grep 7777          # Verify relay is listening
```

**Problem: Client connects but times out**
```
Symptoms: Client connects, gets ID, then disconnects after 10-15 seconds
Cause: Host not sending pong responses, or client not processing packets

Solutions:
  - Ensure host is running and registered with same session ID
  - Call client.processPackets() in your game loop regularly
  - Check host logs for errors processing ping packets
  - Increase clientConnectionTimeoutMs in NeonConfig if on slow network
```

**Problem: Host cannot register with relay**
```
Symptoms: "Failed to register with relay", "Connection timeout"
Causes:
  1. Relay not running
  2. Session ID already in use
  3. Network issues

Solutions:
  - Start relay first
  - Use a different session ID (avoid common numbers like 1, 12345)
  - Check relay logs for error messages
```

### Packet Issues

**Problem: Packets not being received**
```
Symptoms: Client/host connected but game packets don't arrive
Causes:
  1. Not calling processPackets() regularly
  2. Destination ID incorrect (use 0 for broadcast, 1 for host, 2+ for specific clients)
  3. Packet loss (UDP is unreliable)
  4. Rate limiting triggered (flooding)

Solutions:
  - Call processPackets() at least every 10-50ms in your game loop
  - Verify destination ID in packet header
  - Implement ACK/retry for critical packets (see ReliablePacketManager)
  - Check relay logs for rate limit warnings
  - Test with packet loss simulation tools
```

**Problem: "Buffer overflow" or "Packet validation failed"**
```
Symptoms: PacketValidationException, packets rejected
Cause: Packet too large or malformed

Solutions:
  - Keep payloads under 65,507 bytes (UDP maximum)
  - For large data, split into multiple packets
  - Validate your serialization code (ensure ByteBuffer has enough space)
  - Check that packet fields match the protocol specification
```

**Problem: High latency or lag**
```
Symptoms: Ping times > 100ms on LAN, noticeable delay
Causes:
  1. Processing loop sleep too long
  2. Blocking operations in packet callbacks
  3. Network congestion
  4. CPU saturation

Solutions:
  - Reduce clientProcessingLoopSleepMs (default 10ms) in NeonConfig
  - Never block in packet callbacks (offload to separate thread if needed)
  - Use packet aggregation (send multiple game updates per packet)
  - Profile your code to find performance bottlenecks
  - Monitor CPU usage on relay server
```

### JNI / Native Integration Issues

**Problem: UnsatisfiedLinkError when loading native library**
```
Symptoms: "no neon_jni in java.library.path"
Cause: Native library not found or not in library path

Solutions:
  Linux:
    $ sudo cp libneon_jni.so /usr/local/lib/
    $ sudo ldconfig
  Or set library path:
    $ java -Djava.library.path=./native -jar yourapp.jar

  Windows:
    Copy neon_jni.dll to same directory as executable
    Or add to PATH environment variable

  macOS:
    $ sudo cp libneon_jni.dylib /usr/local/lib/
    Or set DYLD_LIBRARY_PATH
```

**Problem: JVM crashes when calling JNI functions**
```
Cause: Incorrect JNI usage, null pointers, or memory corruption

Solutions:
  - Always check return values from JNI functions (handle can be NULL)
  - Call neon_client_free() / neon_host_free() before exit
  - Ensure Java heap size is sufficient (-Xmx2G)
  - Check JNI error codes: neon_get_last_error()
  - Run with -Xcheck:jni for debugging
```

### Performance Issues

**Problem: Relay CPU usage very high**
```
Cause: Too many connections, packet flood, or inefficient processing

Solutions:
  - Reduce maxTotalConnections in NeonConfig
  - Lower maxPacketsPerSecond rate limit (default 100)
  - Enable rate limiting on firewall level
  - Profile relay with VisualVM or Java Flight Recorder
  - Consider horizontal scaling (multiple relay servers)
```

**Problem: Memory leak / OutOfMemoryError**
```
Cause: Packet queues growing unbounded, connections not cleaned up

Solutions:
  - Ensure clients/hosts are properly closed (call close())
  - Check relayCleanupIntervalMs (default 5000ms) - may need to decrease
  - Monitor with jconsole or VisualVM
  - Increase heap size: java -Xmx4G -jar relay.jar
  - Look for unclosed NeonClient/NeonHost instances in your code
```

### Game-Specific Issues

**Problem: Game state desync between clients**
```
Symptoms: Players see different positions, actions not reflected
Causes:
  1. Packet loss causing missed updates
  2. No server authority (client-side prediction without reconciliation)
  3. Race conditions in packet processing
  4. Incorrect use of sequence numbers

Solutions:
  - Implement server-authoritative game logic (host validates all actions)
  - Use reliable packets for critical state changes
  - Send full state updates periodically (not just deltas)
  - Implement client-side prediction with server reconciliation
  - Use sequence numbers to detect and handle out-of-order packets
```

**Problem: Custom game packets not received**
```
Symptoms: Game packets (0x10+) sent but callbacks never called
Causes:
  1. No callback registered for game packets
  2. PacketType is < 0x10 (reserved for core)
  3. Incorrect deserialization throwing exception

Solutions:
  - Set callback: client.setPacketCallback((packet) -> { ... })
  - Use packet types >= 0x10 (PacketType.GAME_PACKET.getValue() = 0x10)
  - Add try-catch in your deserialization code and log errors
  - Check relay is forwarding packets (should see them in logs with FINE level)
```

### Getting Help

If you're still stuck:

1. **Check Logs**: Enable detailed logging with `java.util.logging` level FINE or ALL
2. **Check GitHub Issues**: https://github.com/Quiet-Terminal-interactive/ProjectNeon/issues
3. **Consult Documentation**: Read [ARCHITECTURE.md](ARCHITECTURE.md) for design details
4. **Ask Questions**: Open a GitHub discussion or issue
5. **Email Support**: kohanmathersmcgonnell@gmail.com

---

## FAQ

### General Questions

**Q: What is Project Neon?**
A: Project Neon is a minimal, game-agnostic UDP-based multiplayer protocol with relay architecture. It provides only connection management - games define their own packet types and logic.

**Q: Why use Project Neon instead of [Photon/Mirror/Netcode]?**
A: Project Neon is for developers who want:
- Full control over packet format and game logic
- Minimal protocol overhead (no built-in RPCs, state sync, etc.)
- Cross-engine compatibility (works with any Java/C/C++ engine)
- No vendor lock-in or licensing fees
- Educational purposes (learn networking from first principles)

If you need high-level features like automatic state sync, use a game networking library instead.

**Q: Is Project Neon production-ready?**
A: Project Neon is currently **v1.1.0 (Beta)**. It has comprehensive tests and security hardening, but is not yet 1.0. See [ROADMAP.md](ROADMAP.md) for status. Use in production at your own risk.

**Q: What license is Project Neon?**
A: [License not yet specified - see issue #TODO]

**Q: Can I use this for commercial games?**
A: Once a license is added, yes. The protocol is designed for any use case. Just ensure you implement proper authentication and security (see [SECURITY.md](SECURITY.md)).

### Technical Questions

**Q: Does Project Neon support encryption?**
A: No, Project Neon uses plaintext UDP. You must implement application-layer encryption (AES, TLS, etc.) or deploy on trusted networks (LAN/VPN). DTLS support is planned post-1.0.

**Q: Does Project Neon support TCP?**
A: No, only UDP. TCP adds latency and head-of-line blocking which are unacceptable for real-time games. If you need reliability, use the optional `ReliablePacketManager` for specific packets.

**Q: What's the maximum number of clients per session?**
A: Default is 32 clients (configurable up to 254, since client IDs are bytes). This is a protocol limitation. For more clients, use multiple sessions or extend the protocol.

**Q: What's the maximum packet size?**
A: 65,507 bytes (UDP maximum payload). Recommended to stay under 1,200 bytes to avoid IP fragmentation. For large data (maps, assets), use TCP or HTTP separately.

**Q: How much latency does the relay add?**
A: Minimal - typically < 1ms on LAN, 5-20ms on internet (depends on relay proximity). The relay does not parse payloads, just forwards raw bytes.

**Q: Can clients connect directly (peer-to-peer) without a relay?**
A: Not with current protocol. Project Neon requires a relay for NAT traversal and session management. Direct connections would require STUN/TURN integration (not implemented).

**Q: Does Project Neon handle NAT traversal?**
A: Partially - the relay acts as a meeting point, which handles most NAT scenarios. However, symmetric NAT or strict firewalls may still block connections. Full NAT traversal (STUN/TURN) is a future feature.

**Q: What game genres is Project Neon suitable for?**
A:
- ✅ **Great for**: FPS, racing, fighting games, real-time strategy, sports games, party games
- ✅ **Good for**: Turn-based games, card games, board games (though TCP might be simpler)
- ⚠️ **Challenging**: MMOs with hundreds of players (need multiple sessions/sharding)
- ❌ **Not suitable**: Games requiring strong consistency (use TCP/database)

**Q: Can I use Project Neon with [Unreal/Unity/Godot]?**
A: Yes! Use the JNI bridge:
- **Unreal Engine**: Link `libneon_jni.so` as ThirdParty library, call C API from C++ GameMode
- **Unity**: Use JNI via AndroidJavaObject (Android) or DllImport (PC), or run Java directly
- **Godot**: Use GDNative/GDExtension to call C API
- See [INTEGRATION.md](src/main/native/INTEGRATION.md) for detailed guides

**Q: Is Project Neon faster than TCP?**
A: UDP has lower latency than TCP because it skips acknowledgments and retransmission. However, raw speed depends on your implementation. Project Neon adds minimal overhead (~8 byte header), so performance is close to raw UDP.

**Q: How do I send reliable packets?**
A: Three options:
1. Use `ReliablePacketManager` utility class (application-layer ACK/retry)
2. Implement your own ACK mechanism using sequence numbers
3. Send redundantly (send same packet 3 times, probability of all 3 lost is very low)

**Q: How do I authenticate clients?**
A: Project Neon has no built-in auth. You must implement at application layer:
- Option 1: Use `gameIdentifier` field in ConnectRequest as password hash
- Option 2: Authenticate externally (OAuth, Steam) and pass token as gameIdentifier
- Option 3: Implement challenge-response via game packets after connection
- See [SECURITY.md](SECURITY.md) for examples

**Q: Can I modify the protocol (add fields, change packet layout)?**
A: Yes, but you'll break compatibility with standard relays. For custom protocols:
1. Fork Project Neon and modify PacketHeader/PacketPayload
2. Use game packets (0x10+) for custom data (recommended - no fork needed)
3. Implement a protocol translation layer

**Q: How do I test with packet loss?**
A: Use network emulation tools:
- **Linux**: `tc qdisc add dev eth0 root netem loss 10%`
- **Windows**: Clumsy (https://jagt.github.io/clumsy/)
- **macOS**: Network Link Conditioner (Xcode)
- **Cross-platform**: Toxiproxy, Comcast

**Q: How do I debug packet issues?**
A: Enable detailed logging:
```java
import java.util.logging.Level;
import com.quietterminal.projectneon.util.LoggerConfig;

LoggerConfig.setLevel(Level.FINE); // or Level.ALL for everything
```
This logs every packet sent/received with full details.

### Integration Questions

**Q: Can I use Project Neon with Spring Boot / web servers?**
A: Yes, but run the relay as a separate process (not embedded). The relay uses blocking I/O, which doesn't fit well with web server thread models. Communicate via REST API or messaging queue.

**Q: Can I run multiple relays for horizontal scaling?**
A: Not easily - sessions are bound to a single relay. For scaling:
1. Use a load balancer with consistent hashing (session ID → relay)
2. Implement session migration (not yet supported)
3. Use multiple relays for different game modes/regions

**Q: Can I persist sessions across relay restarts?**
A: Not yet. Session persistence is a planned post-1.0 feature. Currently, relay restart disconnects all clients.

**Q: How do I monitor relay health?**
A: Currently: Parse logs. Planned: Prometheus metrics endpoint (see [ROADMAP.md](ROADMAP.md)). For now:
```bash
# Monitor relay logs
tail -f relay.log | grep ERROR

# Monitor resource usage
top -p $(pgrep -f neon-relay)
```

**Q: Can I use Project Neon in Docker/Kubernetes?**
A: Yes. Expose UDP port 7777 and ensure proper networking:
```dockerfile
FROM openjdk:21
COPY target/neon-relay.jar /app/
EXPOSE 7777/udp
CMD ["java", "-jar", "/app/neon-relay.jar"]
```

**Q: Can I contribute to Project Neon?**
A: Yes! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines. All contributions welcome: code, docs, tests, examples, bug reports.

---

## Future Possibilities

- **Universal Game Protocol Library**: Common packet types (movement, chat, etc.)
- **Cross-Game Standards**: Agreed-upon packet IDs for interoperability
- **Protocol Bridges**: Translate between different games' packet formats
- **Visual Packet Inspector**: Debug tool that uses PacketTypeRegistry