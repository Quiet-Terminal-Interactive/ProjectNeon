# Project Neon - Quick Start Guide

This guide will get you up and running with Project Neon in 5 minutes.

## Prerequisites

- Java 21 or later
- Maven 3.6 or later

## 1. Build the Project

```bash
mvn clean package
```

This creates three executable JARs in the `target/` directory:
- `neon-relay.jar` - The relay server
- `neon-host.jar` - Example host
- `neon-client.jar` - Example client

## 2. Start the Relay

In terminal 1:

```bash
java -jar target/neon-relay.jar
```

You should see:
```
=== Project Neon Relay Server ===
Starting relay server...
Bind address: 0.0.0.0:7777
Relay listening on /0.0.0.0:7777
```

## 3. Start a Host

In terminal 2:

```bash
java -jar target/neon-host.jar 12345
```

You should see:
```
=== Project Neon Host ===
Starting host on session 12345
Host registered with session ID: 12345
```

## 4. Connect Clients

In terminal 3:

```bash
java -jar target/neon-client.jar
```

Follow the prompts:
- Enter your name: `Alice`
- Enter session ID: `12345`
- Enter relay address: (press Enter for default `127.0.0.1:7777`)

You should see:
```
Connected! Client ID: 2
Session ID: 12345
```

Repeat in terminal 4 with a different name like `Bob`.

## 5. Observe the Protocol in Action

Watch the terminals:
- Clients automatically ping the host every 5 seconds
- Host acknowledges with pong responses
- Session config is delivered reliably with ACK tracking
- Relay forwards all packets transparently

## Using in Your Game

### Java Integration

```java
import com.quietterminal.projectneon.client.NeonClient;

// In your game client
NeonClient client = new NeonClient("PlayerName");

// Set up callbacks
client.setPongCallback((responseTime, timestamp) -> {
    System.out.println("Latency: " + responseTime + "ms");
});

// Connect
if (client.connect(sessionId, "127.0.0.1:7777")) {
    // In your game loop
    while (running) {
        client.processPackets();  // Handle incoming packets
        updateGame();
        render();
    }
}
```

### C/C++ Integration

See `src/main/native/project_neon.h` for the complete C API.

```c
#include "project_neon.h"

NeonClientHandle* client = neon_client_new("PlayerName");
neon_client_connect(client, 12345, "127.0.0.1:7777");

// Game loop
while (running) {
    neon_client_process_packets(client);
    update_game();
    render();
}

neon_client_free(client);
```

## Next Steps

- Read [README.md](README.md) for the complete protocol specification
- Explore the source code in `src/main/java/com/quietterminal/projectneon/`
- Implement game-specific packets (0x10 and above)
- Deploy your relay server to a public server
- Build your multiplayer game!

## Architecture Overview

```
[Client] ───UDP───> [Relay] <───UDP─── [Host]
[Client] ───UDP───>    ↑
[Client] ───UDP───>    |
                       |
        Payload-agnostic forwarding
```

The relay is completely game-agnostic. All game logic lives in your clients and host.

## Troubleshooting

**Connection refused:**
- Make sure the relay is running first
- Check firewall settings (UDP port 7777)

**Clients can't connect:**
- Verify the host is registered (check relay output)
- Ensure session IDs match

**Build failures:**
- Verify Java 21 is installed: `java -version`
- Verify Maven is installed: `mvn -version`

## Performance Notes

- UDP-based, no guaranteed delivery for game packets
- Typical latency: < 50ms on LAN, varies on WAN
- Relay can handle hundreds of concurrent connections
- Session config uses ACK/retry for reliability

For more details, see the [README.md](README.md).
