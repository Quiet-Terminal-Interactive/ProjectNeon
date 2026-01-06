# Security Policy

**Project Neon Security Documentation**
Version: 0.2.0
Last Updated: 2026-01-06

---

## Overview

Project Neon is a **UDP-based, relay-mediated multiplayer protocol** designed for **trusted local networks and controlled environments**. This document provides a comprehensive threat model, attack scenarios, implemented mitigations, and security best practices.

**Key Security Characteristics:**
- ❌ No encryption (plaintext UDP)
- ❌ No built-in authentication
- ✅ Input validation on core packets
- ✅ Basic DoS protections
- ✅ Minimal trusted computing base

---

## Threat Model

### Trust Boundaries

```
┌─────────────────────────────────────────────────────────┐
│                   Untrusted Zone                        │
│  - Internet                                             │
│  - Public networks                                      │
│  - Malicious actors                                     │
└─────────────────────────────────────────────────────────┘
                          │
                    UDP Packets
                          │
┌─────────────────────────────────────────────────────────┐
│              Trust Boundary - Network Layer             │
│  Recommended: Firewall, VPN, or isolated LAN            │
└─────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────────────────────────────────────┐
│                    Relay Server                         │
│  - Validates core packet structure                      │
│  - Enforces rate limits                                 │
│  - Routes packets (payload-agnostic)                    │
│  Trusts: Network isolation                              │
│  Does NOT trust: Packet payloads, client behavior       │
└─────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────────────────────────────────────┐
│                  Host / Clients                         │
│  - Process game packets (0x10+)                         │
│  - Implement game logic                                 │
│  Trusts: Core protocol validation                       │
│  Does NOT trust: Game packet contents                   │
└─────────────────────────────────────────────────────────┘
                          │
┌─────────────────────────────────────────────────────────┐
│              Application Layer (Your Game)              │
│  - MUST implement authentication                        │
│  - MUST validate all game packets                       │
│  - MUST implement authorization                         │
│  - SHOULD encrypt sensitive data                        │
└─────────────────────────────────────────────────────────┘
```

### Assumptions

**What Project Neon ASSUMES:**

1. **Network Trust**: The network layer provides isolation (LAN, VPN, or firewall)
2. **Physical Security**: Relay server is not physically compromised
3. **Application-Layer Security**: Games implement their own authentication/authorization
4. **Operator Trust**: Relay operator is trusted (no malicious relay)

**What Project Neon DOES NOT ASSUME:**

1. **Client Trust**: Clients may be malicious
2. **Network Privacy**: Network traffic may be observed
3. **Network Integrity**: Packets may be modified in transit
4. **Session Privacy**: Session IDs are not secret

---

## Attack Scenarios & Mitigations

### 1. Eavesdropping (Confidentiality)

**Attack:** Passive network observer reads packet contents.

**Impact:** 🔴 **HIGH**
- Game data exposed (player positions, chat, actions)
- Session IDs revealed
- Client names visible
- Potential exposure of sensitive game data

**Mitigations Implemented:** ❌ None (plaintext UDP by design)

**Application-Layer Mitigations:**
- ✅ Encrypt sensitive data before sending (AES, ChaCha20)
- ✅ Use secure channels for authentication (HTTPS, WSS)
- ✅ Never transmit passwords or secrets via Neon packets

**Deployment Mitigations:**
- ✅ Use VPN or SSH tunnels for internet deployment
- ✅ Deploy on isolated LANs only
- ✅ Use WPA3 on WiFi networks

---

### 2. Packet Tampering (Integrity)

**Attack:** Active attacker modifies packets in transit.

**Impact:** 🟡 **MEDIUM**
- Game state manipulation (teleportation, item duplication)
- Session disruption (modify destination IDs)
- Potential privilege escalation

**Mitigations Implemented:** ❌ None (no integrity checks)

**Application-Layer Mitigations:**
- ✅ Implement HMAC for critical packets
- ✅ Use sequence numbers to detect replay attacks (header provides sequence field)
- ✅ Implement server-side authority (validate all state changes)
- ✅ Use checksums for critical data

**Deployment Mitigations:**
- ✅ Network isolation (prevent MITM attacks)
- ✅ Use switches instead of hubs (prevent ARP poisoning)

---

### 3. Unauthorized Access (Authentication Bypass)

**Attack:** Attacker connects to a session without permission.

**Impact:** 🔴 **HIGH**
- Anyone can join if they know the session ID
- Session IDs are 32-bit integers (easily guessable)
- No password protection

**Mitigations Implemented:** ❌ None (no authentication)

**Application-Layer Mitigations:**
- ✅ Use `gameIdentifier` field as a shared secret/token
- ✅ Implement password validation in host's `onClientConnect` callback
- ✅ Use external authentication (OAuth, Steam, etc.) before connecting
- ✅ Implement invite-only sessions with cryptographic tokens
- ✅ Use large random session IDs (not sequential integers)

**Example Host-Side Authentication:**
```java
host.setClientConnectCallback((clientId, name, sessionId) -> {
    if (!isAuthorized(name)) {
        // Send CONNECT_DENY via application packet
        // Or disconnect immediately
        return false;
    }
    return true;
});
```

---

### 4. Denial of Service (Availability)

**Attack:** Flood relay or session with packets to exhaust resources.

**Impact:** 🟡 **MEDIUM**

#### 4.1 Packet Flood

**Mitigations Implemented:** ✅ Partial

- ✅ Per-client rate limiting (configurable max packets/second)
- ✅ Packet flood detection and throttling
- ✅ Maximum connections per session
- ✅ Maximum total connections to relay
- ✅ Memory usage limits for packet queues

**Remaining Risks:**
- ❌ UDP amplification attacks (relay could be used as amplifier)
- ❌ Distributed attacks from many sources
- ❌ Resource exhaustion via many sessions

**Additional Mitigations:**
- ✅ Deploy relay behind firewall with rate limiting
- ✅ Use fail2ban or iptables to block abusive IPs
- ✅ Monitor relay CPU/memory usage
- ✅ Implement connection throttling (max new connections/second)

#### 4.2 Memory Exhaustion

**Mitigations Implemented:** ✅ Yes

- ✅ Bounded packet queues (max size limits)
- ✅ Client timeout and cleanup (removes stale connections)
- ✅ Maximum connection limits

#### 4.3 CPU Exhaustion

**Mitigations Implemented:** ✅ Partial

- ✅ Rate limiting prevents processing too many packets
- ❌ Complex packet deserialization could be expensive

**Additional Mitigations:**
- ✅ Profile packet processing costs
- ✅ Set CPU resource limits (cgroups, Docker limits)

---

### 5. Buffer Overflow (Memory Safety)

**Attack:** Send malformed packets with oversized fields to corrupt memory.

**Impact:** 🔴 **CRITICAL** (if successful)
- Code execution
- Relay crash
- Information disclosure

**Mitigations Implemented:** ✅ Yes (as of v0.2.0)

- ✅ All string lengths validated before reading
- ✅ Packet count limits enforced
- ✅ ByteBuffer bounds checking on all reads
- ✅ Session ID validation
- ✅ Maximum payload size enforcement

**Protected Locations:**
- ✅ `PacketPayload.deserializeConnectRequest()` - Name/description length checks
- ✅ `PacketPayload.deserializePacketTypeRegistry()` - Entry count and string limits
- ✅ `PacketPayload.deserializeAck()` - Packet count validation
- ✅ All ByteBuffer operations - Bounds checking

**Remaining Risks:**
- ⚠️ Game packets (0x10+) are NOT validated by core protocol
- Application layer must validate game packet fields

---

### 6. Replay Attacks

**Attack:** Capture and retransmit valid packets to duplicate actions.

**Impact:** 🟡 **MEDIUM**
- Duplicate actions (e.g., spawn multiple items)
- Reuse of old packets after state change

**Mitigations Implemented:** ✅ Partial

- ✅ Sequence numbers in packet header (can be used for replay detection)
- ❌ No automatic replay detection (application must implement)

**Application-Layer Mitigations:**
- ✅ Track received sequence numbers per client
- ✅ Reject out-of-order or duplicate sequences for critical packets
- ✅ Use timestamps and validate freshness
- ✅ Implement nonces for one-time actions

---

### 7. Session Hijacking

**Attack:** Impersonate a connected client by guessing their client ID.

**Impact:** 🟡 **MEDIUM**
- Client IDs are sequential bytes (0-255)
- No session tokens or authentication

**Mitigations Implemented:** ❌ None

**Application-Layer Mitigations:**
- ✅ Implement session tokens (generated on connect, validated per-packet)
- ✅ Bind client ID to source IP address (validate source on relay)
- ✅ Use application-layer encryption with per-client keys

**Relay Consideration:**
- The relay does track client addresses internally
- Adding IP validation would break NAT traversal
- Consider as optional configuration

---

### 8. Malformed Packet Fuzzing

**Attack:** Send malformed packets to find parsing bugs.

**Impact:** 🟡 **MEDIUM**
- Relay crash (DoS)
- Potential memory corruption
- Client/host crash

**Mitigations Implemented:** ✅ Partial

- ✅ Magic number validation (rejects non-Neon packets)
- ✅ Version validation (rejects incompatible versions)
- ✅ ByteBuffer exception handling (catches underflows)
- ✅ Input validation on all core packet types

**Remaining Risks:**
- ⚠️ Game packets (0x10+) are forwarded raw
- ⚠️ Malicious host can send arbitrary PACKET_TYPE_REGISTRY
- ⚠️ Untested edge cases in deserialization

**Additional Mitigations:**
- ✅ Run relay with fuzzing tests (AFL, libFuzzer)
- ✅ Implement crash reporting and monitoring
- ✅ Use memory-safe language for critical components (future: Rust?)

---

### 9. Resource Exhaustion (Sessions)

**Attack:** Create many sessions to exhaust relay resources.

**Impact:** 🟡 **MEDIUM**

**Mitigations Implemented:** ✅ Yes

- ✅ Maximum total connections enforced
- ✅ Session timeout and cleanup

**Additional Mitigations:**
- ✅ Limit maximum concurrent sessions (not yet implemented)
- ✅ Require authentication to create sessions
- ✅ Monitor session creation rate

---

### 10. Protocol Downgrade

**Attack:** Force use of older, vulnerable protocol version.

**Impact:** 🟢 **LOW**
- Version is single byte (0-255)
- Relay validates version and rejects incompatible clients

**Mitigations Implemented:** ✅ Yes

- ✅ Version validation in packet header
- ✅ Relay rejects mismatched versions
- ✅ Client/host can enforce minimum version

---

## Security Controls Summary

| Control                    | Status | Layer     | Since  |
| -------------------------- | ------ | --------- | ------ |
| **Confidentiality**        |        |           |        |
| Encryption                 | ❌      | Not impl. | -      |
| **Integrity**              |        |           |        |
| Packet signing (HMAC)      | ❌      | Not impl. | -      |
| Checksums                  | ❌      | Not impl. | -      |
| **Authentication**         |        |           |        |
| Client authentication      | ❌      | App layer | -      |
| Session passwords          | ❌      | App layer | -      |
| **Authorization**          |        |           |        |
| Access control             | ❌      | App layer | -      |
| **Input Validation**       |        |           |        |
| Core packet validation     | ✅      | Protocol  | v0.2.0 |
| String length limits       | ✅      | Protocol  | v0.2.0 |
| Buffer overflow protection | ✅      | Protocol  | v0.2.0 |
| Session ID validation      | ✅      | Protocol  | v0.2.0 |
| **Availability**           |        |           |        |
| Per-client rate limiting   | ✅      | Relay     | v0.2.0 |
| Connection limits          | ✅      | Relay     | v0.2.0 |
| Packet flood detection     | ✅      | Relay     | v0.2.0 |
| Memory limits              | ✅      | Relay     | v0.2.0 |
| **Audit & Monitoring**     |        |           |        |
| Logging                    | ⚠️      | Partial   | v0.1.0 |
| Metrics                    | ❌      | Not impl. | -      |

---

## Deployment Security Checklist

### For Local/LAN Gaming ✅

- [x] Deploy on isolated network (no internet access)
- [x] Trust all participants
- [x] Minimal security overhead for low latency

### For Internet Deployment ⚠️

**Network Security:**
- [ ] Deploy relay behind firewall
- [ ] Configure firewall rules (allow UDP port only)
- [ ] Use VPN or SSH tunnel for relay access
- [ ] Enable DDoS protection (Cloudflare Spectrum, AWS Shield)
- [ ] Use fail2ban or similar for rate limiting

**Application Security:**
- [ ] Implement authentication at application layer
- [ ] Validate all game packets (0x10+)
- [ ] Encrypt sensitive data before sending
- [ ] Use server-side authority for game state
- [ ] Implement session tokens or invite codes
- [ ] Rate-limit game actions (not just packets)

**Monitoring:**
- [ ] Log all connections and disconnections
- [ ] Monitor relay CPU/memory usage
- [ ] Set up alerts for unusual traffic patterns
- [ ] Track packet rates per client
- [ ] Monitor session creation rate

**Hardening:**
- [ ] Run relay with minimal privileges (non-root user)
- [ ] Use resource limits (ulimit, cgroups)
- [ ] Keep Java runtime updated
- [ ] Disable unnecessary features
- [ ] Use security-focused Linux kernel (grsecurity, SELinux)

---

## Application Developer Guidelines

### Implementing Authentication

**Option 1: Pre-shared Key**
```java
// Host validates gameIdentifier as password hash
host.setClientConnectCallback((clientId, name, sessionId, gameId) -> {
    if (gameId != expectedPasswordHash) {
        sendConnectDeny(clientId, "Invalid password");
        return false;
    }
    return true;
});
```

**Option 2: External Authentication**
```java
// Client authenticates with external service first
String token = authenticateWithServer(username, password);

// Use token as gameIdentifier
client.connect(sessionId, relayAddress, token);

// Host validates token
host.setClientConnectCallback((clientId, name, sessionId, gameId) -> {
    return validateToken(gameId);
});
```

**Option 3: Challenge-Response**
```java
// Host sends challenge via game packet after connect
// Client signs challenge with private key
// Host validates signature
```

### Validating Game Packets

```java
void onGamePacket(byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload);

    // Always validate before reading
    if (buf.remaining() < 4) {
        throw new InvalidPacketException("Packet too small");
    }

    int actorId = buf.getInt();
    if (actorId < 0 || actorId > MAX_ACTORS) {
        throw new InvalidPacketException("Invalid actor ID");
    }

    // Validate array sizes before allocating
    if (buf.remaining() < 1) {
        throw new InvalidPacketException("Missing item count");
    }
    int itemCount = buf.get();
    if (itemCount > MAX_ITEMS || itemCount < 0) {
        throw new InvalidPacketException("Invalid item count");
    }

    // Check enough data for array
    if (buf.remaining() < itemCount * 4) {
        throw new InvalidPacketException("Insufficient data for items");
    }

    // Now safe to read
    int[] items = new int[itemCount];
    for (int i = 0; i < itemCount; i++) {
        items[i] = buf.getInt();
    }
}
```

### Encrypting Sensitive Data

```java
// Use AES-GCM for authenticated encryption
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

byte[] encryptSensitiveData(byte[] plaintext, byte[] key) {
    SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

    byte[] nonce = new byte[12];
    SecureRandom.getInstanceStrong().nextBytes(nonce);

    GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

    byte[] ciphertext = cipher.doFinal(plaintext);

    // Prepend nonce to ciphertext
    return concat(nonce, ciphertext);
}
```

---

## Security Roadmap

### Planned Improvements (Future)

**High Priority:**
- [ ] Implement optional DTLS support (encrypted UDP)
- [ ] Add optional HMAC packet signing
- [ ] Improve logging framework (structured logging)
- [ ] Add metrics endpoint (Prometheus)
- [ ] Implement session persistence (survive relay restart)

**Medium Priority:**
- [ ] Add optional authentication framework
- [ ] Implement IP address validation (opt-in)
- [ ] Add packet replay detection (opt-in)
- [ ] Connection throttling (max new connections/second)
- [ ] Session creation limits

**Low Priority:**
- [ ] Security audit by third party
- [ ] Fuzzing test suite
- [ ] Formal security testing
- [ ] Bug bounty program

---

## Reporting Security Vulnerabilities

If you discover a security vulnerability in Project Neon, please report it responsibly:

### Reporting Process

1. **Do NOT** open a public GitHub issue for security vulnerabilities
2. **Email** the maintainer: kohanmathersmcgonnell@gmail.com
3. **Include:**
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### Response Timeline

- **24 hours**: Initial acknowledgment
- **7 days**: Preliminary assessment
- **30 days**: Fix developed and tested
- **60 days**: Public disclosure (coordinated)

### Hall of Fame

Security researchers who responsibly disclose vulnerabilities will be credited here (with permission).

---

## License & Disclaimer

Project Neon is provided **AS-IS** with **NO WARRANTY**. The maintainers are not responsible for security incidents resulting from:

- Deploying on untrusted networks
- Not implementing application-layer security
- Using default configurations in production
- Ignoring security recommendations in this document

**Use at your own risk. You are responsible for your deployment's security.**

---

**Document Version:** 1.0
**Last Updated:** 2026-01-06
**Maintained by:** Kohan Mathers
