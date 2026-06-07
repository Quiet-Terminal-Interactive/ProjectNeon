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

| Byte    | Name                   | Sender       | Description                                     |
| ------- | ---------------------- | ------------ | ----------------------------------------------- |
| `0x01`  | `CONNECT_REQUEST`      | Client       | Join a session                                  |
| `0x02`  | `CONNECT_ACCEPT`       | Relay / Host | Connection approved                             |
| `0x03`  | `CONNECT_DENY`         | Relay / Host | Connection rejected                             |
| `0x04`  | `SESSION_CONFIG`       | Host         | Tick rate, max packet size (reliably delivered) |
| `0x05`  | `PACKET_TYPE_REGISTRY` | Host         | Advertise game packet type names                |
| `0x06`  | `HOST_REGISTER`        | Host         | Register a new session with the relay           |
| `0x0B`  | `PING`                 | Any          | Keepalive request                               |
| `0x0C`  | `PONG`                 | Any          | Keepalive response                              |
| `0x0D`  | `DISCONNECT_NOTICE`    | Any          | Clean disconnect notification                   |
| `0x0E`  | `ACK`                  | Any          | Acknowledge reliably-delivered packets          |
| `0x0F`  | `RECONNECT_REQUEST`    | Client       | Rejoin with session token                       |
| `0x10+` | `GAME_PACKET`          | Any          | Application-defined; type byte ≥ 0x10           |

## Payload Formats

All payloads follow the 8-byte header. All multi-byte fields are little-endian. Offsets are relative to the first payload byte (byte 8 of the full packet). `N`, `M`, `K` denote variable lengths determined by the preceding length field.

### CONNECT_REQUEST (0x01)

| Offset | Size | Field           | Type   | Notes       |
| ------ | ---- | --------------- | ------ | ----------- |
| 0      | 1    | `clientVersion` | uint8  |             |
| 1      | 2    | `nameLen`       | uint16 | 1–64        |
| 3      | N    | `name`          | UTF-8  | N = nameLen |
| 3+N    | 4    | `sessionId`     | int32  |             |
| 7+N    | 4    | `gameId`        | int32  |             |

### CONNECT_ACCEPT (0x02)

| Offset | Size | Field       | Type  | Notes                       |
| ------ | ---- | ----------- | ----- | --------------------------- |
| 0      | 1    | `clientId`  | uint8 | assigned by host/relay      |
| 1      | 4    | `sessionId` | int32 |                             |
| 5      | 8    | `token`     | int64 | session token for reconnect |

### CONNECT_DENY (0x03)

| Offset | Size | Field       | Type   | Notes         |
| ------ | ---- | ----------- | ------ | ------------- |
| 0      | 2    | `reasonLen` | uint16 | 1–256         |
| 2      | N    | `reason`    | UTF-8  | N = reasonLen |

### SESSION_CONFIG (0x04)

Reliably delivered; clients ACK this packet.

| Offset | Size | Field           | Type  | Notes |
| ------ | ---- | --------------- | ----- | ----- |
| 0      | 1    | `version`       | uint8 |       |
| 1      | 2    | `tickRate`      | int16 |       |
| 3      | 2    | `maxPacketSize` | int16 |       |

### PACKET_TYPE_REGISTRY (0x05)

| Offset | Size | Field        | Type   | Notes                        |
| ------ | ---- | ------------ | ------ | ---------------------------- |
| 0      | 2    | `entryCount` | uint16 | 0–100                        |
| 2      | …    | entries      | —      | entryCount × PacketTypeEntry |

Each **PacketTypeEntry**:

| Offset | Size | Field         | Type  | Notes       |
| ------ | ---- | ------------- | ----- | ----------- |
| 0      | 1    | `packetId`    | uint8 |             |
| 1      | 1    | `nameLen`     | uint8 | 0–64        |
| 2      | M    | `name`        | UTF-8 | M = nameLen |
| 2+M    | 1    | `descLen`     | uint8 | 0–255       |
| 3+M    | K    | `description` | UTF-8 | K = descLen |

### HOST_REGISTER (0x06)

| Offset | Size | Field       | Type  | Notes |
| ------ | ---- | ----------- | ----- | ----- |
| 0      | 4    | `sessionId` | int32 |       |
| 4      | 8    | `hostToken` | int64 |       |

### PING (0x0B)

| Offset | Size | Field       | Type  | Notes                |
| ------ | ---- | ----------- | ----- | -------------------- |
| 0      | 8    | `timestamp` | int64 | sender wall-clock ms |

### PONG (0x0C)

| Offset | Size | Field               | Type  | Notes            |
| ------ | ---- | ------------------- | ----- | ---------------- |
| 0      | 8    | `originalTimestamp` | int64 | echoed from PING |

### DISCONNECT_NOTICE (0x0D)

Empty payload (0 bytes).

### ACK (0x0E)

| Offset | Size | Field       | Type    | Notes     |
| ------ | ---- | ----------- | ------- | --------- |
| 0      | 2    | `count`     | uint16  | 0–100     |
| 2      | 2×K  | `sequences` | int16[] | K = count |

### RECONNECT_REQUEST (0x0F)

| Offset | Size | Field              | Type  | Notes                        |
| ------ | ---- | ------------------ | ----- | ---------------------------- |
| 0      | 8    | `token`            | int64 | from original CONNECT_ACCEPT |
| 8      | 4    | `sessionId`        | int32 |                              |
| 12     | 1    | `previousClientId` | uint8 |                              |

### GAME_PACKET (0x10–0xFF)

| Offset | Size | Field     | Type      | Notes               |
| ------ | ---- | --------- | --------- | ------------------- |
| 0      | N    | `payload` | raw bytes | application-defined |

Any packet type byte ≥ `0x10` is a game packet. The payload is opaque to the relay; its structure is defined by the application and advertised via `PACKET_TYPE_REGISTRY`.

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

If the host sends `CONNECT_DENY` instead, the current relay does not attach that denial to the
buffered reconnect attempt. The denial is routed like any other packet and may be dropped as
unroutable if the old client mapping has already been removed. The buffered reconnect address is
discarded later by relay cleanup.

## Disconnect Flow

```
Client                Relay                 Host / Peers
  │                     │                     │
  │──DISCONNECT_NOTICE──►│                     │
  │                     │──DISCONNECT_NOTICE──►│  (broadcast to all session peers)
  │                     │                     │
  │      [relay slot freed; host retains token for reconnect window]
```

## Routing Rules (Relay)

| Destination ID | Action                                       |
| -------------- | -------------------------------------------- |
| `0`            | Broadcast to all session peers except sender |
| `1`            | Unicast to host                              |
| `2-255`        | Unicast to that client, if registered        |

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

The relay enforces a per-source packet rate limit (default 100 pps). Sources exceeding this limit are
silently dropped and logged at `FINE`. `RateLimiter` tracks violations and reports throttled after
more than 10 violations, but the relay currently does not apply separate behavior for throttled
sources beyond continuing to drop packets while tokens are exhausted.

## Reliable Delivery

Only `SESSION_CONFIG` uses reliability by default. Its retransmission is host-managed with
`AckStateMachine`, and clients automatically ACK received `SESSION_CONFIG` packets. Reliability is
also opt-in for game packets via `ReliablePacketManager`:

- Sender tracks unACKed packets with a timeout
- On timeout: retransmit (up to `reliablePacketMaxRetries` times)
- On exhaustion: fire `onDeliveryFailed` callback
- Receiver deduplicates using per-sender sequence tracking
