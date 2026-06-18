# QTI Neon — Godot Addon

Relay-based UDP multiplayer library for Godot 4.

A `NeonRelay` server routes packets between a `NeonHost` and any number of `NeonClients`. Clients never connect to each other directly, so NAT traversal is trivial and host addresses stay private.

## Classes

| Class | Role |
|---|---|
| `NeonRelay` | Relay server — run this on a VPS |
| `NeonHost` | Game session host |
| `NeonClient` | Game session client |
| `NeonConfig` | Shared configuration |
| `DtlsConfig` | Optional DTLS encryption |
| `GamePacketRegistry` | Register game packet type IDs |
| `BatchAckManager` | Batch-acknowledge reliable packets |
| `ReliablePacketManager` | Reliable delivery with retransmit |

## Quick start

```gdscript
# Host
var host := NeonHost.new(session_id, "relay.example.com:7777")
host.set_client_connect_callback(func(cid, name, sid): print("connected: ", name))
host.set_unhandled_packet_callback(func(type, sender, payload): print("packet from ", sender))
Thread.new().start(func(): host.start_and_run())

# Client
var client := NeonClient.new("PlayerName")
client.set_unhandled_packet_callback(func(type, sender, payload): print("packet from ", sender))
if client.join(session_id, "relay.example.com:7777"):
    Thread.new().start(func(): client.run())
    client.send_packet(PackedByteArray([0x01, 0x02]), 0x10, 1)
```

## License

MIT — see [LICENSE](LICENSE).

Full documentation and source: https://github.com/quiet-terminal-interactive/qtineon
