# Neon Wire Protocol

Version 1 — all multi-byte fields are **little-endian**.

## Packet Structure

Every packet is a fixed 8-byte header followed by a variable-length payload.

```
Offset  Size  Field
──────────────────────────────────────────────
  0      2    Magic        (0x4E45 "NE")
  2      1    Version      (currently 0x01)
  3      1    Packet Type  (see table below)
  4      2    Sequence     (uint16, wraps 65535→0)
  6      1    Client ID    (sender)
  7      1    Destination  (0=broadcast, 1=host, 2-254=client)
```

## Packet Types

| Byte   | Name                 | Sender  | Description |
|--------|----------------------|---------|-------------|
| `0x01` | `CONNECT_REQUEST`    | Client  | Join a session |
| `0x02` | `CONNECT_ACCEPT`     | Relay / Host | Connection approved |
| `0x03` | `CONNECT_DENY`       | Relay / Host | Connection rejected |
| `0x04` | `SESSION_CONFIG`     | Host    | Tick rate, max packet size (reliably delivered) |
| `0x05` | `PACKET_TYPE_REGISTRY` | Host  | Advertise game packet type names |
| `0x06` | `HOST_REGISTER`      | Host    | Register a new session with the relay |
| `0x0B` | `PING`               | Any     | Keepalive request |
| `0x0C` | `PONG`               | Any     | Keepalive response |
| `0x0D` | `DISCONNECT_NOTICE`  | Any     | Clean disconnect notification |
| `0x0E` | `ACK`                | Client  | Acknowledge reliably-delivered packets |
| `0x0F` | `RECONNECT_REQUEST`  | Client  | Rejoin with session token |
| `0x10+` | `GAME_PACKET`       | Any     | Application-defined; type byte ≥ 0x10 |

## Connection Flow

```
Client                Relay                 Host
  │                     │                     │
  │──CONNECT_REQUEST────►│                     │
  │                     │──CONNECT_REQUEST────►│
  │                     │◄─CONNECT_ACCEPT──────│  (assigns clientId, token)
  │◄────CONNECT_ACCEPT──│                     │
  │                     │◄─SESSION_CONFIG──────│  (reliably, dest=clientId)
  │◄────SESSION_CONFIG──│                     │
  │──ACK────────────────►│                     │
  │                     │──ACK────────────────►│
  │                     │◄─PACKET_TYPE_REGISTRY│
  │◄─PACKET_TYPE_REG────│                     │
  │                     │                     │
  │          [session active]                 │
```

## Host Registration Flow

```
Host                  Relay
  │                     │
  │──HOST_REGISTER──────►│  (sessionId, hostToken)
  │◄────CONNECT_ACCEPT───│  (clientId=1 signals relay acceptance)
  │                     │
  │    [ready to accept clients]
```

## Reconnect Flow

The relay buffers the new address without updating session state until the host validates the token.

```
Client (new addr)     Relay                 Host
  │                     │                     │
  │──RECONNECT_REQUEST──►│                     │
  │                     │──RECONNECT_REQUEST──►│  (forwarded to host)
  │                     │◄─CONNECT_ACCEPT──────│  (host validates token)
  │                     │  (relay updates peer address)
  │◄────CONNECT_ACCEPT──│                     │
  │                     │                     │
  │         [session resumed]                 │
```

If the host sends `CONNECT_DENY` instead, the relay discards the buffered address and the client's old mapping is preserved.

## Disconnect Flow

```
Client                Relay                 Host / Peers
  │                     │                     │
  │──DISCONNECT_NOTICE──►│                     │
  │                     │──DISCONNECT_NOTICE──►│  (broadcast to all session peers)
  │                     │                     │
  │      [session slot freed; token retained for reconnect window]
```

## Routing Rules (Relay)

| Destination ID | Action |
|----------------|--------|
| `0`            | Broadcast to all session peers except sender |
| `1`            | Unicast to host |
| `2-254`        | Unicast to that client |

Packets for an unknown destination are silently discarded and logged at `FINE`.

## DTLS

All Neon traffic can be optionally encrypted with DTLS 1.2/1.3. DTLS is relay-terminated: each
peer performs a separate handshake with the relay. The Neon packet exchange is identical whether
DTLS is enabled or not — encryption is transparent to the protocol.

When DTLS is active, the connection flow gains a handshake step before `CONNECT_REQUEST` /
`HOST_REGISTER`:

```
Host / Client         Relay
      │                  │
      │──ClientHello────►│
      │◄─HelloVerifyReq──│
      │──ClientHello────►│  (with cookie)
      │◄─ServerHello─────│
      │◄─Certificate─────│
      │◄─ServerHelloDone─│
      │──ClientKeyExch──►│
      │──ChangeCipher───►│
      │──Finished───────►│
      │◄─ChangeCipher────│
      │◄─Finished────────│
      │                  │
      │  [all subsequent Neon packets encrypted as DTLS application_data]
```

DTLS records are distinguished from Neon packets by their first byte: `0x14–0x17` (DTLS 1.2
content types) or `0x20–0x3F` (DTLS 1.3 unified header). The Neon magic bytes `0x4E 0x45`
("NE") fall outside both ranges, so there is no ambiguity.

## Sequence Numbers

Sequence numbers are unsigned 16-bit integers that wrap `65535 → 0`. Duplicate detection uses signed subtraction: a packet with sequence `s` is a duplicate of last-seen `l` when `(short)(s - l) ≤ 0`. This correctly handles wrap-around.

## Rate Limiting

The relay enforces a per-source packet rate limit (default 100 pps). Sources exceeding this limit are silently dropped. After 10 consecutive violations the source is considered throttled.

## Reliable Delivery

Only `SESSION_CONFIG` uses reliability by default. Reliability is opt-in for game packets via `ReliablePacketManager`:

- Sender tracks unACKed packets with a timeout
- On timeout: retransmit (up to `reliablePacketMaxRetries` times)
- On exhaustion: fire `onDeliveryFailed` callback
- Receiver deduplicates using per-sender sequence tracking
