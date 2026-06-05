"""QTI Neon — minimal, game-agnostic, relay-based UDP multiplayer library.

```
Client A ←──── UDP ────→ Relay ←──── UDP ────→ Host
Client B ←──── UDP ────→ Relay
```

Clients never communicate directly. The relay routes packets by destination ID
in the packet header, keeping NAT traversal trivial and host addresses private.
The host is just another participant — it has no special network position, only
a special protocol role.

## Quick start

### 1. Run a relay

```python
import threading
from qti_neon import NeonRelay, NeonConfig

relay = NeonRelay("0.0.0.0", NeonConfig())
threading.Thread(target=relay.start_and_run, daemon=True).start()
```

### 2. Start a host

```python
from qti_neon import NeonHost

host = NeonHost(session_id=42, relay_address="127.0.0.1:7777")
host.set_client_connect_callback(
    lambda cid, name, sid: print(f"{name} joined as {cid}")
)
host.set_unhandled_packet_callback(
    lambda ptype, sender: handle_game_packet(ptype, sender)
)
threading.Thread(target=host.start_and_run, daemon=True).start()
```

### 3. Connect a client

```python
from qti_neon import NeonClient

client = NeonClient("player1")
client.set_session_config_callback(
    lambda sc: print(f"Tick rate: {sc.tick_rate}")
)
client.set_unhandled_packet_callback(
    lambda ptype, sender: handle_game_packet(ptype, sender)
)
if client.connect(session_id=42, relay_address="127.0.0.1:7777"):
    threading.Thread(target=client.run, daemon=True).start()
```

### 4. Send a packet

```python
PACKET_POSITION = 0x10
data = encode_position(x, y, z)
client.send_packet(data, PACKET_POSITION, dest_id=0)  # 0 = broadcast
```

### 5. Reconnect after a drop

```python
client.stop()
# … later …
ok = client.reconnect()
```

## DTLS encryption

DTLS is opt-in and requires ``pip install qti-neon[dtls]``.

```python
from qti_neon import DtlsConfig, NeonConfig, NeonRelay, NeonClient

relay_cfg = NeonConfig(dtls_config=DtlsConfig.from_key_store("relay.crt", "relay.key"))
relay = NeonRelay("0.0.0.0", relay_cfg)

client_cfg = NeonConfig(dtls_config=DtlsConfig.insecure_trust_all())  # dev only
client = NeonClient("player1", client_cfg)
```

## Client ID conventions

| ID      | Role                   |
| ------- | ---------------------- |
| ``0``   | Broadcast / unassigned |
| ``1``   | Host                   |
| ``2–254`` | Connected clients    |
| ``255`` | Reserved               |
"""

from ._config import NeonConfig
from ._protocol import (
    MAGIC,
    VERSION,
    HEADER_SIZE,
    PacketType,
    PacketHeader,
    NeonPacket,
    ConnectRequest,
    ConnectAccept,
    ConnectDeny,
    SessionConfig,
    PacketTypeEntry,
    PacketTypeRegistry,
    HostRegister,
    Ping,
    Pong,
    DisconnectNotice,
    Ack,
    ReconnectRequest,
    GamePacket,
)
from ._registry import GamePacketRegistry, GamePacketDescriptor
from ._reliable import ReliablePacketManager
from .dtls import DtlsConfig
from .relay import NeonRelay
from .host import NeonHost
from .client import NeonClient

__all__ = [
    # Config
    "NeonConfig",
    # Protocol
    "MAGIC",
    "VERSION",
    "HEADER_SIZE",
    "PacketType",
    "PacketHeader",
    "NeonPacket",
    "ConnectRequest",
    "ConnectAccept",
    "ConnectDeny",
    "SessionConfig",
    "PacketTypeEntry",
    "PacketTypeRegistry",
    "HostRegister",
    "Ping",
    "Pong",
    "DisconnectNotice",
    "Ack",
    "ReconnectRequest",
    "GamePacket",
    # Registry
    "GamePacketRegistry",
    "GamePacketDescriptor",
    # Reliable delivery
    "ReliablePacketManager",
    # DTLS
    "DtlsConfig",
    # Components
    "NeonRelay",
    "NeonHost",
    "NeonClient",
]
