# Reliability Guide for Project Neon

## Overview

Project Neon is built on UDP, which is inherently unreliable. This guide explains when to use reliable vs unreliable packet delivery for game data.

## Quick Decision Guide

| Data Type | Reliability | Why |
|-----------|------------|-----|
| Player position updates | **Unreliable** | Outdated data is useless; next update will arrive soon |
| Player health/score | **Reliable** | Critical state that must not be lost |
| Chat messages | **Reliable** | Must be delivered in order |
| Weapon fire events | **Unreliable** | Fast, frequent events; missing one is acceptable |
| Game state sync | **Reliable** | Critical for game consistency |
| Particle effects | **Unreliable** | Cosmetic; missing data has no gameplay impact |

## Unreliable Packets (Default)

**When to use:**
- High-frequency updates (player movement, animations)
- Data that becomes obsolete quickly
- Cosmetic/non-critical information
- Events where missing data is acceptable

**Advantages:**
- Lower latency
- No bandwidth overhead from ACKs
- No CPU overhead from retransmissions
- Simpler code

**Example:**
```java
// Direct packet sending - unreliable by default
NeonPacket packet = NeonPacket.create(
    PacketType.GAME_PACKET, sequence++, clientId, destinationId,
    new PacketPayload.GamePacket(positionData)
);
socket.sendPacket(packet, relayAddr);
```

## Reliable Packets (Opt-in)

**When to use:**
- Critical game state changes
- Player actions with consequences (buying items, leveling up)
- Messages that must arrive (chat, notifications)
- Data that must be ordered (damage events)

**Disadvantages:**
- Higher latency (waiting for ACKs)
- Increased bandwidth (ACK packets)
- CPU overhead (tracking and retransmissions)
- More complex code

**Example:**
```java
// Using ReliablePacketManager for guaranteed delivery
ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, clientId);

// Send critical data reliably
byte[] criticalData = ...;
reliableManager.sendReliable(criticalData, destinationId);

// In your game loop
reliableManager.processRetransmissions();

// Handle ACKs when received
reliableManager.handleAck(ackPacket.acknowledgedSequences());
```

## Best Practices

### 1. Use Unreliable for Movement
Player position updates happen 20-60 times per second. If one packet is lost, the next update will arrive within milliseconds. Using reliable delivery would increase latency and bandwidth with no benefit.

```java
// Good: Unreliable position updates
void sendPositionUpdate() {
    byte[] posData = serializePosition(x, y, z, rotation);
    sendUnreliablePacket(posData);
}
```

### 2. Use Reliable for State Changes
When a player's health changes, that information is critical and must not be lost.

```java
// Good: Reliable health updates
void onHealthChanged(int newHealth) {
    byte[] healthData = serializeHealth(newHealth);
    reliableManager.sendReliable(healthData, ALL_CLIENTS);
}
```

### 3. Combine Approaches
Send frequent unreliable updates with occasional reliable synchronization:

```java
// Frequent unreliable position updates
void update() {
    sendPositionUpdate(); // Unreliable, every frame

    if (tickCount % 60 == 0) { // Every second
        sendFullStateSyncReliable(); // Reliable state correction
    }
}
```

### 4. Consider Bandwidth
Each reliable packet requires:
- Original packet
- ACK packet (return trip)
- Potential retransmissions

For a 100-byte reliable packet with 2 retransmissions:
- Total bandwidth: ~300 bytes
- Latency: ~200ms+ (including retries)

An unreliable 100-byte packet:
- Total bandwidth: 100 bytes
- Latency: ~50ms (one-way trip)

### 5. Implement Idempotency
Design your game logic so that receiving duplicate packets (from retransmissions) doesn't cause bugs:

```java
// Bad: Not idempotent
void handleDamage(int amount) {
    health -= amount; // Duplicate packet = double damage!
}

// Good: Idempotent with sequence tracking
void handleDamage(int amount, short sequence) {
    if (sequence <= lastProcessedSequence) return; // Ignore duplicate
    health -= amount;
    lastProcessedSequence = sequence;
}
```

## Performance Considerations

### Latency Impact
- **Unreliable**: ~20-50ms (one-way network latency)
- **Reliable**: ~100-500ms (ACK wait + potential retries)

### Bandwidth Impact
For a typical multiplayer game sending 30 packets/second:
- **All unreliable**: ~60 KB/s
- **All reliable**: ~180 KB/s (3x overhead from ACKs/retries)
- **Mixed (90% unreliable)**: ~75 KB/s

### CPU Impact
- **Unreliable**: Negligible (direct send)
- **Reliable**: Moderate (tracking, timers, retransmissions)

## Common Patterns

### Pattern 1: Movement with Validation
```java
// Unreliable position updates
sendPositionUpdate(x, y, z); // 20 times/second

// Reliable validation every second
if (needsValidation()) {
    reliableManager.sendReliable(fullStateData, SERVER);
}
```

### Pattern 2: Event + State
```java
// Unreliable fast event
sendWeaponFireEvent(); // Immediate visual feedback

// Reliable damage confirmation
reliableManager.sendReliable(damageData, TARGET_CLIENT);
```

### Pattern 3: Hybrid Chat
```java
// Reliable message delivery
reliableManager.sendReliable(chatMessageBytes, ALL_CLIENTS);

// Unreliable typing indicator
sendTypingIndicator(); // OK if lost
```

## Summary

**Default to unreliable** for most game data. UDP is fast and efficient for real-time games.

**Use reliable sparingly** for:
- Critical state that must not be lost
- Actions with permanent consequences
- Data where order matters

**Never use reliable for**:
- High-frequency position updates
- Cosmetic effects
- Data that becomes obsolete quickly

The key to great networked gameplay is understanding that **most data doesn't need reliability**. Embrace UDP's speed and accept that occasional packet loss is fine for most game data.
