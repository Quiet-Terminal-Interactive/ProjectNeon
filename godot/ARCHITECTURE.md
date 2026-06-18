# Architecture — Godot (GDScript)

This document covers the Godot 4 / GDScript implementation internals. For the protocol topology, session lifecycle, and handshake sequences see [ARCHITECTURE.md](../ARCHITECTURE.md).

## Component Responsibilities

### NeonRelay

Routes packets by destination ID. Handles `HOST_REGISTER`, `CONNECT_REQUEST`, `CONNECT_ACCEPT`, `CONNECT_DENY`, `RECONNECT_REQUEST`, and `DISCONNECT_NOTICE` directly; all other valid packets are routed opaquely.

- `_session.gd` (`_SessMod`) — stores host and client `peer_key` mappings per session, plus a reverse `peer_key → session_id` map for fast lookup
- `_rate_limiter.gd` (`_RateMod`) — per-source token-bucket rate enforcement; one instance per `peer_key`
- `_pending_connections` — `Dictionary[peer_key, {session_id, timestamp}]` of in-flight connect requests
- `_pending_by_session` — `Dictionary[session_id, Array[peer_key]]` FIFO queue of pending client keys per session
- `_pending_reconnects` — `Dictionary["sid:cid", {new_peer_key, timestamp}]`

### NeonHost

- Assigns client IDs via a monotonically incremented `_next_client_id` counter (starts at 2; host is always 1)
- Reserves client names in `_connected_names` dict — safe because name reservation and ID assignment are each guarded by `Mutex`
- Generates cryptographically random session tokens via `Crypto.generate_random_bytes(8)`
- Sends `SESSION_CONFIG` reliably, tracked per-client by `_ack.gd` (`_AckMod`)
- Maintains `_connected_clients` and `_disconnected_clients` dicts guarded by `Mutex`

### NeonClient

- Performs a blocking handshake loop in `_do_connect()` using `OS.delay_msec()` for polling
- Stores `_token`, `_client_id`, and `_session_id` for reconnect; access is `Mutex`-guarded
- Drives the packet loop via `run()` (blocking, call from `Thread`) or `process_packets()` (call each frame)
- Sends `DISCONNECT_NOTICE` on `stop()` before closing the socket
- Recreates `_NeonPeerSocket` on reconnect if the previous socket is closed

## Threading Model

All three components are designed for a single dedicated `Thread`:

```gdscript
var relay_thread := Thread.new()
relay_thread.start(func(): relay.start_and_run())

var host_thread := Thread.new()
host_thread.start(func(): host.start_and_run())

var client_thread := Thread.new()
client_thread.start(func(): client.run())
```

The processing loops sleep between iterations using `OS.delay_msec()`. All state shared between the application thread and the processing thread (connected clients, session token, client ID) is guarded by `Mutex`. The relay's `_pending_connections`, `_pending_by_session`, and `_pending_reconnects` are accessed only from the single relay processing thread and are not locked.

As an alternative to threading, `process_packets()` (host/client) and `process()` (relay) can be called directly from `_process()` in a Godot `Node`, integrating with the scene tree's frame loop.

## Socket Layer

### _socket.gd (_NeonPeerSocket)

Used by host and client — connects to a single remote (the relay).

- Non-DTLS: `PacketPeerUDP.bind(0)` (OS-assigned local port) + `connect_to_host(relay_ip, relay_port)`
- DTLS: `PacketPeerDTLS.connect_to_peer(udp_peer, relay_hostname, TLSOptions)` using `TLSOptions.client()` or `TLSOptions.client_unsafe()`

### _relay_socket.gd (_NeonRelaySocket)

Used by the relay — accepts packets from multiple peers on a single port.

- Non-DTLS: `UDPServer.listen(port)`. Each unique source address gets its own `PacketPeerUDP` via `UDPServer.take_connection()`. The peer reference is stored in `_udp_peers[peer_key]` and used for both receiving and sending.
- DTLS: `UDPServer` + `DTLSServer.setup(TLSOptions.server(...))`. New DTLS connections are taken via `DTLSServer.take_connection()`, promoted through `_dtls_handshaking` once STATUS_CONNECTED, then stored in `_dtls_peers["dtls:<instance_id>"]`. Because Godot's `PacketPeerDTLS` does not expose the underlying remote address, DTLS peer keys use the instance ID rather than `"ip:port"`. The `SessionManager` stores these keys opaquely — routing is by key reference, not by address.

## DTLS Implementation

DTLS is relay-terminated. Each peer (`NeonHost` or `NeonClient`) maintains a separate DTLS session with the relay.

```
NeonClient ──── DTLS ────► NeonRelay ◄──── DTLS ──── NeonHost
```

When DTLS is enabled (`NeonConfig.dtls_config != null`):

- **Relay** calls `_relay_socket.bind(port, dtls_cfg)` on startup. Inbound DTLS handshakes from new peers are handled automatically by `DTLSServer.poll()`. Each handshaking `PacketPeerDTLS` is polled until `STATUS_CONNECTED`, then registered in `_dtls_peers`.
- **Host / Client** calls `_NeonPeerSocket.open(relay_host, relay_port, dtls_cfg)` then `wait_for_handshake(timeout_ms)` during startup. `wait_for_handshake` polls `PacketPeerDTLS` until `STATUS_CONNECTED`.
- After the handshake, `send()` and `receive()` on both socket types are transparent to the caller.

`DtlsConfig` provides factory methods that wrap Godot's `TLSOptions`:

- `DtlsConfig.from_key_store(cert_path, key_path)` → `TLSOptions.server(key, cert)`
- `DtlsConfig.with_trust_store(ca_path)` → `TLSOptions.client(hostname, ca_cert)`
- `DtlsConfig.insecure_trust_all()` → `TLSOptions.client_unsafe(hostname)` — dev/test only

## Packet Processing Loop

```gdscript
# Relay / Host / Client all follow this pattern:
while _running:
    process_or_process_packets()    # drain inbound, handle each
    _check_pending_acks()           # host only — _AckMod per connected client
    _perform_cleanup()              # relay only — stale session eviction
    _check_auto_ping()              # client only
    OS.delay_msec(loop_sleep_ms)
```

## Key Classes

| Class / Script              | File                     | Role                                                          |
| --------------------------- | ------------------------ | ------------------------------------------------------------- |
| `NeonRelay`                 | `NeonRelay.gd`           | Relay processing loop and packet routing                      |
| `NeonHost`                  | `NeonHost.gd`            | Host processing loop and client lifecycle                     |
| `NeonClient`                | `NeonClient.gd`          | Client connect, reconnect, packet dispatch, auto-ping         |
| `NeonConfig`                | `NeonConfig.gd`          | Mutable configuration with all defaults                       |
| `DtlsConfig`                | `DtlsConfig.gd`          | DTLS configuration with factory methods                       |
| `ReliablePacketManager`     | `ReliablePacketManager.gd` | Opt-in reliable delivery for game packets                   |
| `GamePacketRegistry`        | `GamePacketRegistry.gd`  | Registry of application-defined game packet types             |
| `_NeonPeerSocket`           | `_socket.gd`             | Host/client UDP socket with optional DTLS (internal)          |
| `_NeonRelaySocket`          | `_relay_socket.gd`       | Relay multi-peer UDP socket with optional DTLS (internal)     |
| `_SessionManager` (script)  | `_session.gd`            | Peer address map per session (relay-internal)                 |
| `_AckStateMachine` (script) | `_ack.gd`                | Reliable delivery for `SESSION_CONFIG` (host-internal)        |
| `_RateLimiter` (script)     | `_rate_limiter.gd`       | Per-source token-bucket rate limiter (relay-internal)         |
| `_Protocol` (script)        | `_protocol.gd`           | Static packet parse/build functions and wire-format constants |
