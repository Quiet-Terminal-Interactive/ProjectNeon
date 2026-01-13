# Project Neon JNI Examples

This directory contains example C programs demonstrating how to use the Project Neon JNI layer for C/C++ integration.

## Examples

### 1. Client Example (`client_example.c`)

Demonstrates:
- Creating a NeonClient instance
- Connecting to a relay server
- Setting up callbacks for events
- Processing packets in a game loop
- Sending pings manually
- Proper cleanup

**Usage:**
```bash
./client_example [client_name] [session_id] [relay_addr]

# Examples:
./client_example                                    # Defaults: TestClient, 12345, 127.0.0.1:7777
./client_example Player1                            # Custom name
./client_example Player2 99999                      # Custom name and session
./client_example Player3 12345 192.168.1.100:7777   # All custom
```

### 2. Host Example (`host_example.c`)

Demonstrates:
- Creating a NeonHost instance
- Setting up callbacks for client events
- Processing packets manually
- Monitoring connected clients
- Proper cleanup

**Usage:**
```bash
./host_example [session_id] [relay_addr]

# Examples:
./host_example                        # Defaults: 12345, 127.0.0.1:7777
./host_example 99999                  # Custom session
./host_example 12345 192.168.1.100:7777   # All custom
```

## Building the Examples

### Prerequisites

1. Build the Project Neon Java library:
   ```bash
   cd ProjectNeon
   mvn clean compile
   ```

2. Build the native JNI library:
   ```bash
   cd src/main/native
   mkdir build && cd build
   cmake ..
   make
   ```

### Build Examples

```bash
cd src/main/native/examples
mkdir build && cd build
cmake ..
make
```

This creates two executables in the `build` directory:
- `client_example`
- `host_example`

## Running the Examples

### Complete Test Scenario

Follow these steps to run a complete test:

#### Terminal 1: Start Relay Server
```bash
cd ProjectNeon
java -jar target/neon-relay.jar 7777
```

Output should show:
```
Relay server started on port 7777
```

#### Terminal 2: Start Host
```bash
cd src/main/native/examples/build
./host_example 12345 127.0.0.1:7777
```

Output should show:
```
=== Neon Host JNI Example ===
Session ID: 12345
Relay address: 127.0.0.1:7777

Host created successfully
Callbacks set
Session ID: 12345
Initial client count: 0

Running for 60 seconds (processing packets manually)...
```

#### Terminal 3: Start Client(s)
```bash
cd src/main/native/examples/build
./client_example Player1 12345 127.0.0.1:7777
```

You should see output from both the host and client showing:
- Client connection
- Session configuration
- Packet type registry
- Pings and pongs

Try running multiple clients in different terminals to test multi-client scenarios.

## Expected Output

### Client Output Example
```
=== Neon Client JNI Example ===
Client name: Player1
Session ID: 12345
Relay address: 127.0.0.1:7777

Client created successfully
Connecting to relay...
Connected! Waiting for connection confirmation...
Processed 3 packets
Client is connected!
Client ID: 1
Session ID: 12345

[CLIENT] Session config received:
  Version: 1
  Tick rate: 60
  Max packet size: 1024

Running for 30 seconds (processing packets)...
Processed 1 packets (iteration 1)
[CLIENT] Received pong! Response time: 12 ms (timestamp: 1234567890)
...
```

### Host Output Example
```
=== Neon Host JNI Example ===
Session ID: 12345
Relay address: 127.0.0.1:7777

Host created successfully
Callbacks set
Session ID: 12345
Initial client count: 0

Running for 60 seconds (processing packets manually)...
Processed 2 packets (iteration 5)
Current client count: 1
[HOST] Client connected:
  Client ID: 1
  Name: Player1
  Session ID: 12345
[HOST] Ping received from client 1
...
```

## Callback Functions

Both examples demonstrate how to set up callback functions that are invoked when events occur:

### Client Callbacks
- `on_pong`: Called when a pong response is received
- `on_session_config`: Called when session configuration is received
- `on_packet_type_registry`: Called when packet type registry is received
- `on_unhandled_packet`: Called when an unhandled packet type is received
- `on_wrong_destination`: Called when a packet arrives for the wrong destination

### Host Callbacks
- `on_client_connect`: Called when a client connects
- `on_client_deny`: Called when a client connection is denied
- `on_ping_received`: Called when a ping is received from a client
- `on_unhandled_packet`: Called when an unhandled packet type is received

## Integrating into Your Game

To integrate Project Neon into your C/C++ game:

1. **Include the header:**
   ```c
   #include "project_neon.h"
   ```

2. **Initialize client/host** during game startup:
   ```c
   NeonClientHandle* client = neon_client_new("PlayerName");
   neon_client_connect(client, session_id, "relay.example.com:7777");
   ```

3. **Process packets** in your game loop:
   ```c
   void game_update() {
       int packets = neon_client_process_packets(client);
       // ... rest of game logic
   }
   ```

4. **Set up callbacks** to respond to events:
   ```c
   neon_client_set_pong_callback(client, my_pong_handler);
   neon_client_set_session_config_callback(client, my_config_handler);
   ```

5. **Clean up** on exit:
   ```c
   neon_client_free(client);
   ```

## Common Issues

### Library Not Found
If you get `error while loading shared libraries: libneon_jni.so`:

**Linux:**
```bash
export LD_LIBRARY_PATH=../build:$LD_LIBRARY_PATH
./client_example
```

**macOS:**
```bash
export DYLD_LIBRARY_PATH=../build:$DYLD_LIBRARY_PATH
./client_example
```

**Windows:**
```cmd
set PATH=..\build\Release;%PATH%
client_example.exe
```

### Connection Timeout
If clients fail to connect:
- Ensure the relay server is running
- Check firewall settings
- Verify the relay address is correct
- Ensure the session ID matches on host and clients

### JVM Not Found
If you get JVM initialization errors:
- Ensure `JAVA_HOME` is set correctly
- Ensure the JVM shared library is in your library path
- On Linux: Check `/usr/lib/jvm/`
- On macOS: Check `/Library/Java/JavaVirtualMachines/`
- On Windows: Check `%JAVA_HOME%\bin\server\`

## Performance Notes

- The examples process packets synchronously in the main thread
- For production games, consider processing packets on a dedicated thread
- The `neon_host_start()` function is blocking - always run it in a separate thread
- Alternatively, use `neon_host_process_packets()` for manual control

## Thread Safety

- All JNI functions are thread-safe
- The JVM automatically attaches/detaches threads as needed
- Callbacks are invoked on the thread that calls `process_packets()`
- Ensure your callback functions are thread-safe if using multiple threads

## Next Steps

- Explore the full API in [project_neon.h](../project_neon.h)
- Read the integration guide in [INTEGRATION.md](../INTEGRATION.md)
- Check the build documentation in [BUILD.md](../BUILD.md)
- Review the Java API documentation for advanced features

## Support

For issues or questions, please open an issue at:
https://github.com/Quiet-Terminal-Interactive/ProjectNeon/issues
