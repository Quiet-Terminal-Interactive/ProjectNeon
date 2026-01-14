# Project Neon Examples

This directory contains example applications demonstrating how to use Project Neon for multiplayer networking.

## Available Examples

### 1. [Chat Application](chat/)

A simple multiplayer chat room demonstrating basic Project Neon usage.

**Features**:
- Multiple users in one session
- Real-time message broadcasting
- User join/leave notifications
- Command system (/help, /ping, /quit)

**Demonstrates**:
- Client-server architecture
- Broadcasting packets
- Custom packet types (0x10+)
- Packet serialization/deserialization
- Callback handling

**Complexity**: ⭐ Beginner

### 2. [Custom Packet Types](custom-packets/)

Comprehensive guide to designing custom game packets.

**Topics Covered**:
- Packet type design patterns
- Efficient serialization with ByteBuffer
- Bit packing for booleans
- Quantization for network efficiency
- Validation and error handling
- Versioning strategies
- Packet loss handling

**Demonstrates**:
- Multiple packet format examples (2D game)
- Size optimization techniques
- Best practices for real-time games

**Complexity**: ⭐⭐ Intermediate

## Getting Started

### Prerequisites

- Java 21 or later
- Maven 3.6+ (for building Project Neon)
- Basic understanding of UDP networking

### Building

From the project root:

```bash
# Build Project Neon
mvn clean package

# The JAR will be at: target/project-neon-0.2.0.jar
```

### Running the Examples

Each example has its own README with detailed instructions. Generally:

1. **Start a Relay Server** (required for all examples):
   ```bash
   java -jar target/neon-relay.jar
   ```

2. **Run the example** (see example-specific README):
   ```bash
   # Chat example
   java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatServer
   java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatClient Alice
   ```

## Example Structure

Each example follows this structure:

```
example-name/
├── README.md           # Detailed documentation
├── Server.java         # Server/Host implementation (if applicable)
├── Client.java         # Client implementation (if applicable)
└── [Other classes]     # Supporting classes
```

## Key Concepts

### Client-Relay-Host Architecture

All examples use Project Neon's relay architecture:

```
┌─────────┐         ┌───────┐         ┌────────┐
│ Client  │◄───────►│ Relay │◄───────►│  Host  │
└─────────┘         └───────┘         └────────┘
     ▲                   ▲                  ▲
     │                   │                  │
     │                   │                  │
Other Clients      Central Router    Game Server
```

**Benefits**:
- NAT traversal (clients don't need open ports)
- Single authority (host manages game state)
- Multiple sessions on one relay

### Custom Packet Types

Project Neon reserves 0x01-0x0F for core protocol. Games use 0x10-0xFF:

```java
// Define your packet types
public class MyGamePackets {
    public static final byte PLAYER_MOVEMENT = (byte) 0x10;
    public static final byte SPAWN_ITEM = (byte) 0x11;
    public static final byte CHAT_MESSAGE = (byte) 0x12;
    // ... up to 0xFF (246 types available)
}
```

### Packet Serialization

Use `ByteBuffer` for efficient binary serialization:

```java
// Serialize
ByteBuffer buf = ByteBuffer.allocate(16);
buf.putInt(playerId);
buf.putFloat(x);
buf.putFloat(y);
buf.putInt(health);
byte[] payload = buf.array();

// Deserialize
ByteBuffer buf = ByteBuffer.wrap(payload);
int playerId = buf.getInt();
float x = buf.getFloat();
float y = buf.getFloat();
int health = buf.getInt();
```

### Sending Packets

```java
// Create packet
NeonPacket packet = NeonPacket.create(
    PLAYER_MOVEMENT,           // Packet type
    (short) sequenceNumber,    // Sequence number
    myClientId,                // Sender ID
    (byte) 0,                  // Destination (0 = broadcast)
    new PacketPayload.GamePacket(payload)
);

// Send via client or host
client.sendPacket(packet);
// or
host.sendPacket(packet);
```

### Receiving Packets

```java
// Set callback
client.setPacketCallback((packet) -> {
    byte type = packet.header().packetType();

    switch (type) {
        case PLAYER_MOVEMENT -> handleMovement(packet);
        case SPAWN_ITEM -> handleSpawnItem(packet);
        case CHAT_MESSAGE -> handleChat(packet);
    }
});

// Process packets in game loop
while (running) {
    client.processPackets();  // Dispatch callbacks
    // Your game logic here
    Thread.sleep(10);  // ~100 Hz processing
}
```

## Common Patterns

### Pattern 1: Broadcast Messages

Send to all clients in session:

```java
NeonPacket broadcast = NeonPacket.create(
    GAME_EVENT,
    (short) 0,
    myClientId,
    (byte) 0,  // Destination 0 = broadcast
    new PacketPayload.GamePacket(data)
);
host.sendPacket(broadcast);
```

### Pattern 2: Unicast (Direct Message)

Send to specific client:

```java
NeonPacket direct = NeonPacket.create(
    PRIVATE_MESSAGE,
    (short) 0,
    myClientId,
    targetClientId,  // Specific destination
    new PacketPayload.GamePacket(data)
);
host.sendPacket(direct);
```

### Pattern 3: Reliable Delivery

Use `ReliablePacketManager` for critical packets:

```java
ReliablePacketManager reliable = new ReliablePacketManager(
    socket, relayAddress, myClientId, config
);

reliable.sendReliable(IMPORTANT_EVENT, destId, data, (success) -> {
    if (success) {
        System.out.println("Delivered!");
    } else {
        System.err.println("Failed after retries");
    }
});

// In game loop
reliable.process();  // Handle ACKs and retransmits
```

### Pattern 4: Client-Side Prediction

Handle latency with prediction:

```java
// Client sends input
sendPlayerInput(keys, mousePos);

// Immediately predict result locally
predictMovement(keys);

// Server sends authoritative state
onPlayerState(serverState) {
    // Reconcile prediction with server
    if (Math.abs(localState.x - serverState.x) > THRESHOLD) {
        // Server correction needed
        localState = serverState;
    } else {
        // Prediction was good, smooth to server state
        smoothInterpolate(localState, serverState);
    }
}
```

### Pattern 5: Server Authority

Always validate on server:

```java
// Client: Request action
sendAttackRequest(targetId);

// Server: Validate and apply
onAttackRequest(clientId, targetId) {
    // Validate
    if (!isInRange(clientId, targetId)) {
        return;  // Ignore invalid request
    }
    if (isOnCooldown(clientId)) {
        return;  // Too soon
    }

    // Apply damage
    int damage = calculateDamage(clientId, targetId);
    applyDamage(targetId, damage);

    // Broadcast result
    broadcastDamageEvent(clientId, targetId, damage);
}
```

## Performance Tips

### Packet Size

- **Keep small**: < 100 bytes for high-frequency packets
- **Stay under MTU**: < 1200 bytes to avoid fragmentation
- **Use bit packing**: Pack booleans into bytes
- **Quantize floats**: Use shorts for angles/percentages

### Packet Frequency

- **Input**: 20-60 Hz (unreliable)
- **State updates**: 20-60 Hz (unreliable)
- **World snapshot**: 1-10 Hz (unreliable)
- **Events**: On-demand (reliable if critical)

### Rate Limiting

Default: 100 packets/sec per client. Configure if needed:

```java
NeonConfig config = new NeonConfig()
    .setMaxPacketsPerSecond(200);  // Allow 200 pkt/s

NeonClient client = new NeonClient("Player", config);
```

### Processing Loop

Balance responsiveness vs CPU usage:

```java
// High responsiveness (more CPU)
while (running) {
    client.processPackets();
    Thread.sleep(5);  // 200 Hz
}

// Balanced (recommended)
while (running) {
    client.processPackets();
    Thread.sleep(10);  // 100 Hz
}

// Low CPU (less responsive)
while (running) {
    client.processPackets();
    Thread.sleep(16);  // ~60 Hz
}
```

## Troubleshooting

### "Connection timeout"

- Ensure relay is running first
- Check firewall allows UDP port 7777
- Verify session ID matches between client and host

### "Packets not received"

- Call `processPackets()` regularly (every 10-50ms)
- Check packet type is correct
- Verify destination ID (0 = broadcast, 1 = host, 2+ = clients)

### "High latency"

- Reduce processing loop sleep time
- Check network quality (use /ping in chat example)
- Minimize work in packet callbacks

### "Packet validation errors"

- Validate buffer has enough bytes before reading
- Check serialization logic matches deserialization
- Ensure field types match (byte vs short vs int)

## Next Steps

1. **Try the chat example** - Simplest starting point
2. **Read custom packets guide** - Learn serialization best practices
3. **Read [ARCHITECTURE.md](../ARCHITECTURE.md)** - Understand protocol design
4. **Build your game** - Apply concepts to your project

## Additional Resources

- [README.md](../README.md) - Full protocol specification
- [QUICKSTART.md](../QUICKSTART.md) - 5-minute setup guide
- [ARCHITECTURE.md](../ARCHITECTURE.md) - Design decisions and patterns
- [CONTRIBUTING.md](../CONTRIBUTING.md) - Code style and contribution guide
- [SECURITY.md](../SECURITY.md) - Security considerations

## Contributing Examples

Have a cool example to share? We'd love to include it!

1. Fork the repository
2. Create your example in `examples/your-example/`
3. Include a comprehensive README.md
4. Follow the code style in [CONTRIBUTING.md](../CONTRIBUTING.md)
5. Submit a pull request

Good example ideas:
- Turn-based game (chess, tic-tac-toe)
- Physics synchronization
- Lobby/matchmaking system
- Spectator mode
- Replay system

## License

All examples are part of Project Neon and follow the same license.

---

**Questions?** Open an issue on [GitHub](https://github.com/QuietTerminal/ProjectNeon/issues) or email kohanmathersmcgonnell@gmail.com
