"""Tests for NeonHost with a mock relay."""

import socket
import threading
import time
import pytest

from qti_neon import (
    NeonConfig,
    NeonHost,
    NeonPacket,
    PacketType,
    ConnectRequest,
    ConnectAccept,
    ConnectDeny,
    SessionConfig,
    PacketTypeRegistry,
    HostRegister,
    Ping,
    Pong,
    Ack,
    DisconnectNotice,
)
from qti_neon._registry import GamePacketRegistry


class MockRelay:
    """A minimal UDP server that acts as a relay stub."""

    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(2.0)
        self.received: list[tuple[NeonPacket, tuple]] = []
        self._lock = threading.Lock()

    @property
    def address(self) -> tuple[str, int]:
        return self.sock.getsockname()

    def recv_one(self, timeout=2.0) -> tuple[NeonPacket, tuple] | None:
        self.sock.settimeout(timeout)
        try:
            data, addr = self.sock.recvfrom(65535)
            pkt = NeonPacket.from_bytes(data)
            with self._lock:
                self.received.append((pkt, addr))
            return pkt, addr
        except socket.timeout:
            return None

    def send(self, packet: NeonPacket, addr: tuple) -> None:
        self.sock.sendto(packet.to_bytes(), addr)

    def close(self) -> None:
        self.sock.close()


@pytest.fixture
def relay_and_host():
    relay = MockRelay()
    host_port = relay.address[1]
    cfg = NeonConfig(
        host_ack_timeout_ms=500,
        host_max_ack_retries=2,
        host_processing_loop_sleep_ms=5,
    )
    host = NeonHost(session_id=1, relay_address=f"127.0.0.1:{host_port}", config=cfg)

    def run_host():
        # Simulate relay ack'ing the HOST_REGISTER
        result = relay.recv_one()
        assert result is not None
        reg_pkt, host_addr = result
        assert PacketType.from_byte(reg_pkt.header.packet_type) is PacketType.HOST_REGISTER
        # Send CONNECT_ACCEPT(client_id=1) back to complete registration
        relay.send(
            NeonPacket.create(PacketType.CONNECT_ACCEPT, 0, 0, 1, ConnectAccept(1, 1, 0)),
            host_addr,
        )

    t_setup = threading.Thread(target=run_host)
    t_setup.start()

    host_thread = threading.Thread(target=host.start_and_run, daemon=True)
    host_thread.start()
    t_setup.join(timeout=3.0)
    time.sleep(0.05)

    yield relay, host

    if host.is_running:
        host.stop()
    relay.close()


class TestHostRegistration:
    def test_host_registers_on_start(self, relay_and_host):
        relay, host = relay_and_host
        assert host.is_running

    def test_connected_clients_empty_initially(self, relay_and_host):
        _, host = relay_and_host
        assert host.connected_clients() == {}


class TestHostConnectRequest:
    def test_accept_client(self, relay_and_host):
        relay, host = relay_and_host
        host_addr = host.local_address()

        connected: list = []
        host.set_client_connect_callback(lambda cid, name, sid: connected.append((cid, name)))

        # Relay forwards a CONNECT_REQUEST to the host
        relay.send(
            NeonPacket.create(PacketType.CONNECT_REQUEST, 1, 0, 1, ConnectRequest(1, "alice", 1, 0)),
            host_addr,
        )

        # Host should send CONNECT_ACCEPT back to relay
        result = relay.recv_one()
        assert result is not None
        pkt, _ = result
        assert PacketType.from_byte(pkt.header.packet_type) is PacketType.CONNECT_ACCEPT
        assert pkt.payload.client_id == 2

        time.sleep(0.05)
        assert len(connected) == 1
        assert connected[0][1] == "alice"

        # Host should also send SESSION_CONFIG
        result = relay.recv_one()
        assert result is not None
        pkt, _ = result
        assert PacketType.from_byte(pkt.header.packet_type) is PacketType.SESSION_CONFIG

    def test_deny_duplicate_name(self, relay_and_host):
        relay, host = relay_and_host
        host_addr = host.local_address()

        relay.send(
            NeonPacket.create(PacketType.CONNECT_REQUEST, 1, 0, 1, ConnectRequest(1, "alice", 1, 0)),
            host_addr,
        )
        relay.recv_one()  # CONNECT_ACCEPT
        relay.recv_one()  # SESSION_CONFIG
        relay.recv_one()  # PACKET_TYPE_REGISTRY

        # Second connection with same name should be denied
        relay.send(
            NeonPacket.create(PacketType.CONNECT_REQUEST, 2, 0, 1, ConnectRequest(1, "alice", 1, 0)),
            host_addr,
        )
        result = relay.recv_one()
        assert result is not None
        pkt, _ = result
        assert PacketType.from_byte(pkt.header.packet_type) is PacketType.CONNECT_DENY
        assert "taken" in pkt.payload.reason.lower()


class TestHostAck:
    def test_ack_stops_retransmit(self, relay_and_host):
        relay, host = relay_and_host
        host_addr = host.local_address()

        relay.send(
            NeonPacket.create(PacketType.CONNECT_REQUEST, 1, 0, 1, ConnectRequest(1, "bob", 1, 0)),
            host_addr,
        )
        relay.recv_one()  # CONNECT_ACCEPT
        cfg_pkt, _ = relay.recv_one()  # SESSION_CONFIG
        relay.recv_one()  # PACKET_TYPE_REGISTRY

        cfg_seq = cfg_pkt.header.sequence

        # Send ACK for the SESSION_CONFIG
        relay.send(
            NeonPacket.create(PacketType.ACK, 0, 2, 1, Ack((cfg_seq,))),
            host_addr,
        )
        time.sleep(0.1)
        # No retransmit should occur — the count of outgoing SESSION_CONFIGs stays at 1.
        session_configs = [
            p for p, _ in relay.received
            if PacketType.from_byte(p.header.packet_type) is PacketType.SESSION_CONFIG
        ]
        assert len(session_configs) == 1


class TestHostPing:
    def test_responds_with_pong(self, relay_and_host):
        relay, host = relay_and_host
        host_addr = host.local_address()

        relay.send(
            NeonPacket.create(PacketType.PING, 0, 2, 1, Ping(12345)),
            host_addr,
        )
        result = relay.recv_one()
        assert result is not None
        pkt, _ = result
        assert PacketType.from_byte(pkt.header.packet_type) is PacketType.PONG
        assert pkt.payload.original_timestamp == 12345


class TestHostGamePacketRegistry:
    def test_registry_sent_on_connect(self, relay_and_host):
        relay, host = relay_and_host
        host_addr = host.local_address()

        reg = GamePacketRegistry()
        reg.register(0x10, "POSITION", "Player position")
        host.set_game_packet_registry(reg)

        relay.send(
            NeonPacket.create(PacketType.CONNECT_REQUEST, 1, 0, 1, ConnectRequest(1, "carl", 1, 0)),
            host_addr,
        )
        relay.recv_one()  # CONNECT_ACCEPT
        relay.recv_one()  # SESSION_CONFIG

        result = relay.recv_one()
        assert result is not None
        pkt, _ = result
        assert PacketType.from_byte(pkt.header.packet_type) is PacketType.PACKET_TYPE_REGISTRY
        assert len(pkt.payload.entries) == 1
        assert pkt.payload.entries[0].name == "POSITION"
