# QTI Neon

Minimal, game-agnostic, relay-based UDP multiplayer protocol library.

```
Client A ←──── UDP ────→ Relay ←──── UDP ────→ Host
Client B ←──── UDP ────→ Relay
```

Clients never communicate directly. The relay routes packets by destination ID in the packet header, keeping NAT traversal trivial and host addresses private. The host is just another participant — it has no special network position, only a special protocol role.

## Features

- Relay-mediated UDP with automatic NAT traversal
- Connection handshake with host-assigned client IDs and session tokens
- Token-based reconnection (5-minute window by default)
- Opt-in reliable delivery with retransmit and duplicate detection
- Per-source rate limiting (100 pps default)
- Auto-ping keepalive
- Optional DTLS 1.2/1.3 encryption — relay-terminated, transparent to game code
- Zero game-specific logic in the relay or library

## Implementations

Each language implementation lives in its own subdirectory at the repository root, alongside its generated documentation under `docs/<language>/`.

All implementations conform to the same protocol spec — a client or host written in any language is fully interoperable with a relay or peer written in any other.

See [PROTOCOL.md](PROTOCOL.md) for the wire format specification that all implementations share.

## Documentation

See [ARCHITECTURE.md](ARCHITECTURE.md) for a full description of the relay topology, session lifecycle, and reconnect flow.

See [PROTOCOL.md](PROTOCOL.md) for the wire format specification.

See [CONFIGURATION.md](CONFIGURATION.md) for the complete configuration reference.

API documentation for each implementation is generated from source and lives under `docs/<language>/`.

## Quick Start

### 1. Run a relay

<details>
<summary>Java</summary>

```java
NeonConfig cfg = NeonConfig.defaults(); // relay port 7777
NeonRelay relay = new NeonRelay("0.0.0.0", cfg);
Thread.ofVirtual().start(relay::startAndRun);
```

</details>

### 2. Start a host

<details>
<summary>Java</summary>

```java
NeonHost host = new NeonHost(42, "relay.example.com:7777", cfg);
host.setClientConnectCallback((id, name, sid) ->
    System.out.println(name + " joined as " + (id & 0xFF)));
host.setUnhandledPacketCallback((type, from) ->
    handleGamePacket(type, from));
Thread.ofVirtual().start(() -> host.startAndRun());
```

</details>

### 3. Connect a client

<details>
<summary>Java</summary>

```java
NeonClient client = new NeonClient("player1", cfg);
client.setSessionConfigCallback(sc ->
    System.out.println("Tick rate: " + sc.tickRate()));
client.setUnhandledPacketCallback((type, from) ->
    handleGamePacket(type, from));

if (client.connect(42, "relay.example.com:7777")) {
    Thread.ofVirtual().start(client::run);
}
```

</details>

### 4. Send a packet

<details>
<summary>Java</summary>

```java
byte[] data = encodePosition(x, y, z);
client.sendPacket(data, PACKET_POSITION, (byte) 0); // 0 = broadcast
```

</details>

### 5. Reconnect after drop

<details>
<summary>Java</summary>

```java
client.stop(); // or socket dies ungracefully
// ...
boolean ok = client.reconnect(); // uses stored session token
```

</details>

## DTLS Encryption

DTLS is opt-in — the relay, host, and client handle the handshake automatically before any Neon packets are exchanged.

<details>
<summary>Java</summary>

```java
// Relay — load a PKCS12 keystore with the relay's certificate
KeyStore ks = KeyStore.getInstance("PKCS12");
try (var is = new FileInputStream("relay.p12")) {
    ks.load(is, "password".toCharArray());
}
SSLContext relayCtx = DtlsConfig.fromKeyStore(ks, "password".toCharArray());

NeonConfig relayCfg = NeonConfig.builder()
    .sslContext(relayCtx)
    .build();

// Host / Client — trust the relay certificate (or use a proper TrustManager)
SSLContext clientCtx = DtlsConfig.insecureTrustAll(); // dev only
NeonConfig clientCfg = NeonConfig.builder()
    .sslContext(clientCtx)
    .build();
```

</details>

`insecureTrustAll()` is for development and testing only — it accepts any certificate.
For production, supply a trust manager that pins the relay's certificate.

When DTLS is not configured (the default), packets are sent in plaintext.

## Installation

<details>
<summary>Java (Maven)</summary>

```xml
<dependency>
    <groupId>com.quietterminal</groupId>
    <artifactId>qti-neon</artifactId>
    <version>1.0.0</version>
</dependency>
```

</details>

## Building

<details>
<summary>Java</summary>

```bash
mvn verify
```

Tests that open UDP sockets (most of them) must run outside a sandbox:

```bash
mvn test
```

Generate docs:

```bash
mvn javadoc:javadoc
# output: target/reports/apidocs/index.html
```

</details>

## Client IDs

| ID      | Role                   |
| ------- | ---------------------- |
| `0`     | Broadcast / unassigned |
| `1`     | Host                   |
| `2–254` | Connected clients      |
| `255`   | Reserved               |

## License

MIT — see [LICENSE](LICENSE).