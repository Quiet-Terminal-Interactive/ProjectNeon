# Chat Application Example

A simple multiplayer chat application demonstrating Project Neon's core features.

## Features

- Multiple users can connect to a chat session
- Real-time message broadcasting
- Server notifications (user join/leave)
- User list display
- Simple command system (/help, /ping, /quit)

## Architecture

```
ChatServer (NeonHost)
    │
    ├─ Manages chat session
    ├─ Accepts client connections
    ├─ Broadcasts messages to all users
    └─ Tracks connected users

ChatClient (NeonClient)
    │
    ├─ Connects to chat session
    ├─ Sends messages to server
    ├─ Receives and displays messages
    └─ Handles user input
```

## Custom Packet Types

This example defines 3 custom packet types (0x10+):

| Type | Value | Purpose |
|------|-------|---------|
| CHAT_MESSAGE | 0x10 | User chat message (broadcast) |
| SERVER_MESSAGE | 0x11 | Server notification (join/leave/system) |
| USER_LIST | 0x12 | List of connected users (sent on join) |

### Packet Formats

**CHAT_MESSAGE (0x10)**:
```
[sender_name_length: 2 bytes]
[sender_name: variable UTF-8 bytes]
[message: variable UTF-8 bytes]
```

**SERVER_MESSAGE (0x11)**:
```
[message: variable UTF-8 bytes]
```

**USER_LIST (0x12)**:
```
[user_count: 1 byte]
For each user:
    [name_length: 1 byte]
    [name: variable UTF-8 bytes]
```

## Building

From the project root:

```bash
# Build Project Neon
mvn clean package

# Compile chat examples
javac -cp target/project-neon-0.2.0.jar \
    examples/chat/ChatServer.java \
    examples/chat/ChatClient.java
```

## Running

### 1. Start a Relay Server

```bash
java -jar target/neon-relay.jar
```

Output:
```
=== Project Neon Relay Server ===
Starting relay server...
Relay listening on /0.0.0.0:7777
```

### 2. Start the Chat Server

```bash
# Default session (12345)
java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatServer

# Or specify session ID
java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatServer 99999
```

Output:
```
=== Project Neon Chat Server ===
Session ID: 12345
Starting server...

[SERVER] Client connected: Alice (ID: 2)
[CHAT] Alice: Hello everyone!
```

### 3. Connect Clients

Open multiple terminals:

**Terminal 1 (Alice):**
```bash
java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatClient Alice
```

**Terminal 2 (Bob):**
```bash
java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatClient Bob
```

**Terminal 3 (Charlie):**
```bash
java -cp target/project-neon-0.2.0.jar:examples examples.chat.ChatClient Charlie 12345 127.0.0.1:7777
```

### Client Output

```
=== Project Neon Chat Client ===
Username: Alice
Connecting to session 12345 via 127.0.0.1:7777...

[SYSTEM] Connected to chat!
[SYSTEM] Your ID: 2
[SYSTEM] Type your message and press Enter to send.
[SYSTEM] Type '/quit' to exit.

[SERVER] Welcome to the chat, Alice!
[SERVER] Bob has joined the chat

[USERS] Online users (2):
  - Alice
  - Bob

Bob: Hi Alice!
```

## Usage

### Sending Messages

Simply type your message and press Enter:
```
Hello everyone!
```

### Commands

- `/help` - Show available commands
- `/ping` - Check connection latency
- `/quit` - Exit chat

### Example Session

```
Alice: Hi everyone!
Bob: Hey Alice!
[SERVER] Charlie has joined the chat
Charlie: What's up?
Alice: Welcome Charlie!
/ping
[SYSTEM] Ping sent (check server logs for latency)
Bob: I have to go, bye!
[SERVER] Bob has left the chat
/quit
[SYSTEM] Disconnecting...
```

## Key Concepts Demonstrated

### 1. Custom Packet Types

```java
private static final byte PACKET_CHAT_MESSAGE = (byte) 0x10;
private static final byte PACKET_SERVER_MESSAGE = (byte) 0x11;
```

Game packets use the 0x10+ range, which Project Neon forwards without parsing.

### 2. Broadcasting

```java
// Send to all clients (destination ID = 0)
NeonPacket packet = NeonPacket.create(
    PACKET_CHAT_MESSAGE,
    (short) 0,
    (byte) 1,  // From host
    (byte) 0,  // Broadcast to all
    new PacketPayload.GamePacket(payload)
);
```

### 3. Unicast (Direct Messages)

```java
// Send to specific client (destination ID = clientId)
NeonPacket packet = NeonPacket.create(
    PACKET_SERVER_MESSAGE,
    (short) 0,
    (byte) 1,  // From host
    clientId,  // To specific client
    new PacketPayload.GamePacket(payload)
);
```

### 4. Packet Processing Loop

```java
// Client-side packet processing
while (running && client.isConnected()) {
    client.processPackets();  // Handle incoming packets
    Thread.sleep(10);         // 10ms polling
}
```

### 5. Callbacks

```java
// Handle connection
client.setConnectedCallback(() -> {
    System.out.println("Connected!");
});

// Handle incoming packets
client.setPacketCallback((packet) -> {
    // Deserialize and process packet
});
```

### 6. Serialization

```java
// Serialize message: [name_length][name][message]
byte[] nameBytes = senderName.getBytes(StandardCharsets.UTF_8);
byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);

ByteBuffer buffer = ByteBuffer.allocate(2 + nameBytes.length + msgBytes.length);
buffer.putShort((short) nameBytes.length);
buffer.put(nameBytes);
buffer.put(msgBytes);

byte[] payload = buffer.array();
```

## Extending the Example

### Add Private Messages

```java
// Client sends /msg Bob Hello
if (input.startsWith("/msg ")) {
    String[] parts = input.split(" ", 3);
    String recipient = parts[1];
    String message = parts[2];
    sendPrivateMessage(recipient, message);
}

// Server routes to specific client
private void sendPrivateMessage(byte recipientId, String sender, String message) {
    // Format: [sender][message]
    // Packet type: PACKET_PRIVATE_MESSAGE (0x13)
}
```

### Add Chat History

```java
// Server maintains last N messages
private final Queue<ChatMessage> history = new LinkedList<>();

// Send to new clients on join
for (ChatMessage msg : history) {
    sendChatMessage(clientId, msg);
}
```

### Add User Colors

```java
// Assign color on join
record ClientInfo(String name, String color) {}

// Include in user list
private void sendUserList(byte clientId) {
    // Format: [count][[name_len][name][color_len][color]]...
}
```

### Add Emotes/Reactions

```java
// Client sends /react 😀
private static final byte PACKET_REACTION = (byte) 0x14;

// Server broadcasts emoji with sender name
```

## Performance Notes

- **Latency**: ~1-50ms on LAN (network RTT dominates)
- **Throughput**: Limited by `maxPacketsPerSecond` (default 100)
- **Scalability**: 32 clients per session (configurable)
- **Message Size**: Keep under 1200 bytes to avoid fragmentation

## Troubleshooting

### "Connection failed"

- Ensure relay server is running first
- Check firewall allows UDP port 7777
- Verify session ID matches server

### "Not connected to server"

- Wait for `[SYSTEM] Connected to chat!` message before typing
- Check network connectivity (try `ping <relay_host>`)

### Messages not appearing

- Ensure you call `processPackets()` regularly
- Check server logs for errors
- Verify packet types match (0x10, 0x11, 0x12)

### High latency

- Reduce `clientProcessingLoopSleepMs` (default 10ms)
- Check network quality (packet loss?)
- Profile server-side packet handling

## License

This example is part of Project Neon and follows the same license.

## See Also

- [Project Neon README](../../README.md) - Protocol specification
- [Basic Game Example](../game/) - Simple multiplayer game
- [Custom Packets Example](../custom-packets/) - Advanced packet serialization
