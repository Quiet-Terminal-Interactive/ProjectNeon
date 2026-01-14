# Custom Packet Types Example

This example demonstrates how to define, serialize, and deserialize custom game packets using Project Neon.

## Overview

Project Neon reserves packet types 0x01-0x0F for core protocol functions. Games can use 0x10-0xFF (246 packet types) for custom game logic.

This example shows:
- Defining custom packet types
- Efficient serialization with ByteBuffer
- Deserialization with validation
- Versioning strategies
- Subtype patterns
- Best practices for packet design

## Packet Type Design

### Approach 1: One Type Per Message

**Simple and explicit**:
```java
// Define packet types
public class GamePackets {
    public static final byte PLAYER_MOVEMENT = (byte) 0x10;
    public static final byte PLAYER_ACTION = (byte) 0x11;
    public static final byte SPAWN_ENTITY = (byte) 0x12;
    public static final byte DAMAGE_EVENT = (byte) 0x13;
    public static final byte CHAT_MESSAGE = (byte) 0x14;
    // ... up to 0xFF
}
```

**Pros**:
- Clear and easy to understand
- Fast dispatch (simple switch statement)
- Each packet can have completely different format

**Cons**:
- Limited to 246 packet types
- Can't easily version individual packets

### Approach 2: Subtypes Within Packets

**Hierarchical organization**:
```java
public class GamePackets {
    // Main categories
    public static final byte PLAYER_PACKET = (byte) 0x10;
    public static final byte ENTITY_PACKET = (byte) 0x11;
    public static final byte WORLD_PACKET = (byte) 0x12;

    // Player subtypes (in payload)
    public static final byte PLAYER_MOVE = 0x01;
    public static final byte PLAYER_JUMP = 0x02;
    public static final byte PLAYER_ATTACK = 0x03;
}
```

**Pros**:
- More packet types possible (16 × 256 = 4096)
- Logical grouping
- Easier to add new packets in categories

**Cons**:
- Two-level dispatch (type then subtype)
- Slightly more complex parsing

### Approach 3: Versioned Packets

**Support protocol evolution**:
```java
// Payload format: [version][subtype][data]
public record PlayerPacket(
    byte version,
    byte subtype,
    byte[] data
) {
    public static PlayerPacket parse(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        byte version = buf.get();
        byte subtype = buf.get();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return new PlayerPacket(version, subtype, data);
    }
}
```

**Pros**:
- Backward compatibility
- Can evolve packet formats over time
- Clients/servers can negotiate versions

**Cons**:
- More complex
- Overhead (version + subtype bytes)

## Example: 2D Game Packets

Let's design packets for a simple 2D multiplayer game:

### Packet Type Definitions

```java
public class Game2DPackets {
    // Player packets (0x10-0x1F)
    public static final byte PLAYER_INPUT = (byte) 0x10;      // Client → Server
    public static final byte PLAYER_STATE = (byte) 0x11;      // Server → Client
    public static final byte PLAYER_SPAWN = (byte) 0x12;      // Server → Client
    public static final byte PLAYER_DESPAWN = (byte) 0x13;    // Server → Client

    // World packets (0x20-0x2F)
    public static final byte WORLD_STATE = (byte) 0x20;       // Server → Client (full snapshot)
    public static final byte SPAWN_ITEM = (byte) 0x21;        // Server → Client
    public static final byte COLLECT_ITEM = (byte) 0x22;      // Client → Server

    // Combat packets (0x30-0x3F)
    public static final byte ATTACK = (byte) 0x30;            // Client → Server
    public static final byte DAMAGE = (byte) 0x31;            // Server → Client
    public static final byte DEATH = (byte) 0x32;             // Server → Client
}
```

### Packet Formats

#### PLAYER_INPUT (0x10)

**Client sends input state** (unreliable, high frequency):

```java
public record PlayerInput(
    byte inputFlags,    // Bit flags: W=1, A=2, S=4, D=8, Jump=16, Attack=32
    short mouseX,       // Mouse position X (-32768 to 32767)
    short mouseY,       // Mouse position Y
    long timestamp      // Client timestamp for lag compensation
) {
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(11);
        buf.put(inputFlags);
        buf.putShort(mouseX);
        buf.putShort(mouseY);
        buf.putLong(timestamp);
        return buf.array();
    }

    public static PlayerInput deserialize(ByteBuffer buf) {
        byte inputFlags = buf.get();
        short mouseX = buf.getShort();
        short mouseY = buf.getShort();
        long timestamp = buf.getLong();
        return new PlayerInput(inputFlags, mouseX, mouseY, timestamp);
    }

    // Bit manipulation helpers
    public boolean isMovingForward() { return (inputFlags & 1) != 0; }
    public boolean isMovingLeft() { return (inputFlags & 2) != 0; }
    public boolean isMovingBackward() { return (inputFlags & 4) != 0; }
    public boolean isMovingRight() { return (inputFlags & 8) != 0; }
    public boolean isJumping() { return (inputFlags & 16) != 0; }
    public boolean isAttacking() { return (inputFlags & 32) != 0; }
}
```

**Size**: 11 bytes (very efficient)

#### PLAYER_STATE (0x11)

**Server sends authoritative player state** (unreliable, high frequency):

```java
public record PlayerState(
    byte playerId,
    float x, float y,       // Position (4 bytes each)
    float velocityX,        // Velocity X
    float velocityY,        // Velocity Y
    short rotation,         // Rotation angle (0-360 scaled to 0-65535)
    byte animationState,    // 0=idle, 1=walking, 2=jumping, etc.
    byte health             // 0-100
) {
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put(playerId);
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(velocityX);
        buf.putFloat(velocityY);
        buf.putShort(rotation);
        buf.put(animationState);
        buf.put(health);
        return buf.array();
    }

    public static PlayerState deserialize(ByteBuffer buf) {
        return new PlayerState(
            buf.get(),
            buf.getFloat(),
            buf.getFloat(),
            buf.getFloat(),
            buf.getFloat(),
            buf.getShort(),
            buf.get(),
            buf.get()
        );
    }
}
```

**Size**: 24 bytes

#### WORLD_STATE (0x20)

**Server sends full world snapshot** (unreliable, low frequency):

```java
public record WorldState(
    int tick,                   // Server tick number
    List<PlayerState> players,  // All players
    List<ItemState> items       // All items
) {
    public byte[] serialize() {
        // Calculate size
        int size = 4 + 1 + (players.size() * 24) + 1 + (items.size() * 12);
        ByteBuffer buf = ByteBuffer.allocate(size);

        buf.putInt(tick);

        // Players
        buf.put((byte) players.size());
        for (PlayerState player : players) {
            buf.put(player.serialize());
        }

        // Items
        buf.put((byte) items.size());
        for (ItemState item : items) {
            buf.put(item.serialize());
        }

        return buf.array();
    }

    public static WorldState deserialize(ByteBuffer buf) {
        int tick = buf.getInt();

        // Deserialize players
        byte playerCount = buf.get();
        List<PlayerState> players = new ArrayList<>(playerCount);
        for (int i = 0; i < playerCount; i++) {
            players.add(PlayerState.deserialize(buf));
        }

        // Deserialize items
        byte itemCount = buf.get();
        List<ItemState> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(ItemState.deserialize(buf));
        }

        return new WorldState(tick, players, items);
    }
}

public record ItemState(
    short itemId,
    byte itemType,      // 0=health, 1=ammo, 2=weapon, etc.
    float x, float y
) {
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(11);
        buf.putShort(itemId);
        buf.put(itemType);
        buf.putFloat(x);
        buf.putFloat(y);
        return buf.array();
    }

    public static ItemState deserialize(ByteBuffer buf) {
        return new ItemState(
            buf.getShort(),
            buf.get(),
            buf.getFloat(),
            buf.getFloat()
        );
    }
}
```

**Size**: Variable (4 + 1 + N×24 + 1 + M×11 bytes)

#### ATTACK (0x30)

**Client initiates attack** (reliable - use ReliablePacketManager):

```java
public record AttackAction(
    byte weaponSlot,    // Which weapon (0-3)
    float targetX,      // Target position X
    float targetY,      // Target position Y
    long timestamp      // Client timestamp
) {
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(17);
        buf.put(weaponSlot);
        buf.putFloat(targetX);
        buf.putFloat(targetY);
        buf.putLong(timestamp);
        return buf.array();
    }

    public static AttackAction deserialize(ByteBuffer buf) {
        return new AttackAction(
            buf.get(),
            buf.getFloat(),
            buf.getFloat(),
            buf.getLong()
        );
    }
}
```

**Size**: 17 bytes

#### DAMAGE (0x31)

**Server confirms damage dealt** (reliable):

```java
public record DamageEvent(
    byte attackerId,    // Who attacked
    byte victimId,      // Who was hit
    short damage,       // Damage amount (0-65535)
    byte hitLocation,   // 0=body, 1=head, 2=legs (for multipliers)
    boolean isCritical  // Was it a critical hit?
) {
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.put(attackerId);
        buf.put(victimId);
        buf.putShort(damage);
        buf.put(hitLocation);
        buf.put((byte) (isCritical ? 1 : 0));
        return buf.array();
    }

    public static DamageEvent deserialize(ByteBuffer buf) {
        return new DamageEvent(
            buf.get(),
            buf.get(),
            buf.getShort(),
            buf.get(),
            buf.get() != 0
        );
    }
}
```

**Size**: 6 bytes

## Serialization Best Practices

### 1. Use Fixed-Size Fields When Possible

```java
// Good: 4 bytes
buf.putInt(playerId);

// Less efficient: 1-5 bytes
writeVarInt(buf, playerId);  // Variable-length encoding
```

Fixed-size fields are faster to parse and easier to work with.

### 2. Pack Booleans into Bit Flags

```java
// Bad: 8 bytes for 8 booleans
buf.put((byte) (isJumping ? 1 : 0));
buf.put((byte) (isCrouching ? 1 : 0));
buf.put((byte) (isRunning ? 1 : 0));
// ... 5 more

// Good: 1 byte for 8 booleans
byte flags = 0;
if (isJumping) flags |= 0x01;
if (isCrouching) flags |= 0x02;
if (isRunning) flags |= 0x04;
if (isShooting) flags |= 0x08;
if (isReloading) flags |= 0x10;
if (isAiming) flags |= 0x20;
if (isInCover) flags |= 0x40;
if (isInjured) flags |= 0x80;
buf.put(flags);
```

### 3. Quantize Floats for Network

```java
// Bad: 4 bytes for rotation (0-360°)
buf.putFloat(rotation);

// Good: 2 bytes for rotation (0-360° mapped to 0-65535)
buf.putShort((short) (rotation * 65535 / 360));

// Even better: 1 byte (0-360° mapped to 0-255, precision ~1.4°)
buf.put((byte) (rotation * 255 / 360));
```

### 4. Use Appropriate Integer Sizes

```java
// Player count (max 254 clients)
buf.put((byte) playerCount);        // 1 byte

// Item ID (up to 65535 items)
buf.putShort(itemId);                // 2 bytes

// Timestamp (milliseconds since epoch)
buf.putLong(timestamp);              // 8 bytes
```

### 5. Validate on Deserialization

```java
public static PlayerState deserialize(ByteBuffer buf) {
    // Check buffer has enough data
    if (buf.remaining() < 24) {
        throw new PacketValidationException("Insufficient data for PlayerState");
    }

    byte playerId = buf.get();
    float x = buf.getFloat();
    float y = buf.getFloat();
    float vx = buf.getFloat();
    float vy = buf.getFloat();
    short rotation = buf.getShort();
    byte animation = buf.get();
    byte health = buf.get();

    // Validate ranges
    if (playerId < 1 || playerId > 254) {
        throw new PacketValidationException("Invalid player ID: " + playerId);
    }
    if (health < 0 || health > 100) {
        throw new PacketValidationException("Invalid health: " + health);
    }
    if (Float.isNaN(x) || Float.isInfinite(x)) {
        throw new PacketValidationException("Invalid position X");
    }

    return new PlayerState(playerId, x, y, vx, vy, rotation, animation, health);
}
```

### 6. Use Records for Immutability

```java
// Good: Immutable, thread-safe, equals/hashCode automatic
public record PlayerState(byte playerId, float x, float y, ...) {}

// Avoid: Mutable, boilerplate
public class PlayerState {
    private byte playerId;
    private float x, y;
    // ... getters, setters, equals, hashCode
}
```

## Packet Frequency Guidelines

| Packet Type | Frequency | Reliability | Size Limit |
|-------------|-----------|-------------|------------|
| Player Input | 20-60 Hz | Unreliable | < 50 bytes |
| Player State | 20-60 Hz | Unreliable | < 100 bytes |
| World Snapshot | 1-10 Hz | Unreliable | < 1200 bytes |
| Attack Action | On demand | **Reliable** | < 100 bytes |
| Damage Event | On demand | **Reliable** | < 50 bytes |
| Chat Message | On demand | Reliable | < 500 bytes |

### High-Frequency Packets (20-60 Hz)

- **MUST** be small (< 100 bytes)
- **MUST** be unreliable (no ACK overhead)
- **SHOULD** contain only essential data
- **SHOULD** use delta compression if possible

### Low-Frequency Packets (< 10 Hz)

- **CAN** be larger (< 1200 bytes)
- **CAN** be reliable if critical
- **CAN** contain more detailed data

### Event Packets (On Demand)

- **SHOULD** be reliable if critical (attacks, item pickup)
- **CAN** be unreliable if recoverable (animations, effects)
- **MUST** validate on server

## Handling Packet Loss

### Strategy 1: Redundancy

Send the same data multiple times:

```java
// Send player state every frame
// Loss of 1-2 packets is fine because next frame arrives soon
for (int i = 0; i < 60; i++) {  // 60 FPS
    sendPlayerState();
    Thread.sleep(16);  // ~16ms per frame
}
```

### Strategy 2: Sequence Numbers

Use sequence numbers to detect packet loss:

```java
public record PlayerState(
    byte playerId,
    short sequenceNumber,  // Incrementing counter
    // ... rest of fields
) {
    // Client tracks last received sequence
    private short lastSequence = -1;

    public void handlePlayerState(PlayerState state) {
        short expected = (short) (lastSequence + 1);
        if (state.sequenceNumber() != expected) {
            // Packet(s) lost - interpolate or request full state
            System.err.println("Lost " + (state.sequenceNumber() - expected) + " packets");
        }
        lastSequence = state.sequenceNumber();

        // Apply state
        updatePlayer(state);
    }
}
```

### Strategy 3: Delta Compression

Only send what changed:

```java
public record PlayerStateDelta(
    byte playerId,
    byte changedFlags,    // Bit flags: posX=1, posY=2, health=4, etc.
    float x,              // Only if changedFlags & 1
    float y,              // Only if changedFlags & 2
    byte health           // Only if changedFlags & 4
    // ...
) {
    // Reduces average packet size from 24 bytes to ~5-10 bytes
}
```

### Strategy 4: Reliable for Critical Events

Use `ReliablePacketManager` for must-deliver packets:

```java
// Attack must be delivered
ReliablePacketManager reliable = new ReliablePacketManager(socket, relayAddr, clientId, config);
reliable.sendReliable(ATTACK, destId, attackPacket.serialize(), (success) -> {
    if (success) {
        System.out.println("Attack confirmed");
    } else {
        System.err.println("Attack failed after retries");
    }
});
```

## Versioning Strategy

### Approach: Version Field in Payload

```java
public record VersionedPacket(
    byte version,
    byte subtype,
    byte[] data
) {
    public static VersionedPacket parse(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        byte version = buf.get();
        byte subtype = buf.get();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return new VersionedPacket(version, subtype, data);
    }

    public Object deserialize() {
        ByteBuffer buf = ByteBuffer.wrap(data);
        return switch (version) {
            case 1 -> deserializeV1(buf, subtype);
            case 2 -> deserializeV2(buf, subtype);
            default -> throw new UnsupportedOperationException("Unknown version: " + version);
        };
    }
}
```

**Migration Path**:
1. Add new version handlers
2. Clients/servers negotiate version on connect
3. Support both versions during transition
4. Deprecate old version after migration period

## Testing Custom Packets

```java
@Test
void testPlayerInputSerialization() {
    // Arrange
    PlayerInput original = new PlayerInput(
        (byte) 0b00101101,  // W + A + Jump + Attack
        (short) 1024,
        (short) 768,
        System.currentTimeMillis()
    );

    // Act
    byte[] serialized = original.serialize();
    PlayerInput deserialized = PlayerInput.deserialize(ByteBuffer.wrap(serialized));

    // Assert
    assertEquals(original, deserialized);
    assertTrue(deserialized.isMovingForward());
    assertTrue(deserialized.isMovingLeft());
    assertFalse(deserialized.isMovingBackward());
    assertTrue(deserialized.isJumping());
}

@Test
void testPacketSizeLimits() {
    // Ensure packets fit in UDP MTU (1200 bytes recommended)
    WorldState largeState = createMaxSizeWorldState();
    byte[] serialized = largeState.serialize();
    assertTrue(serialized.length < 1200, "Packet too large: " + serialized.length);
}
```

## See Also

- [Chat Example](../chat/) - Simple message broadcasting
- [Game Example](../game/) - Full game implementation
- [Project Neon README](../../README.md) - Protocol specification
- [ARCHITECTURE.md](../../ARCHITECTURE.md) - Design patterns
