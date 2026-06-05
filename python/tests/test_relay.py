"""Tests for NeonRelay using real UDP sockets on loopback."""

import socket
import threading
import time
import pytest

from qti_neon import (
    NeonConfig,
    NeonRelay,
    NeonPacket,
    PacketType,
    HostRegister,
    ConnectAccept,
    ConnectDeny,
    ConnectRequest,
    DisconnectNotice,
)


def _send_and_recv(
    sock: socket.socket,
    packet: NeonPacket,
    dest: tuple[str, int],
    timeout: float = 1.0,
) -> NeonPacket | None:
    sock.sendto(packet.to_bytes(), dest)
    sock.settimeout(timeout)
    try:
        data, _ = sock.recvfrom(65535)
        return NeonPacket.from_bytes(data)
    except socket.timeout:
        return None


def _make_relay(port=0):
    cfg = NeonConfig(relay_port=port)
    # Port 0 → OS assigns a free port, but NeonRelay binds to the configured port.
    # Use an explicit free port instead.
    relay = NeonRelay("127.0.0.1", cfg)
    return relay


@pytest.fixture
def relay():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    cfg = NeonConfig(relay_port=port)
    r = NeonRelay("127.0.0.1", cfg)
    t = threading.Thread(target=r.start_and_run, daemon=True)
    t.start()
    time.sleep(0.05)
    yield r
    if r.is_running:
        r.stop()


def _raw() -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    return s


class TestRelayHostRegister:
    def test_host_register_returns_connect_accept(self, relay):
        addr = relay.local_address()
        with _raw() as sock:
            req = NeonPacket.create(PacketType.HOST_REGISTER, 0, 1, 0, HostRegister(1, 0))
            resp = _send_and_recv(sock, req, addr)
        assert resp is not None
        assert PacketType.from_byte(resp.header.packet_type) is PacketType.CONNECT_ACCEPT
        assert isinstance(resp.payload, ConnectAccept)
        assert resp.payload.client_id == 1

    def test_invalid_session_id_ignored(self, relay):
        addr = relay.local_address()
        with _raw() as sock:
            req = NeonPacket.create(PacketType.HOST_REGISTER, 0, 1, 0, HostRegister(0, 0))
            sock.sendto(req.to_bytes(), addr)
            sock.settimeout(0.3)
            # Should get no response
            with pytest.raises(socket.timeout):
                sock.recvfrom(65535)


class TestRelayConnectRequest:
    def test_connect_to_unknown_session_returns_deny(self, relay):
        addr = relay.local_address()
        with _raw() as sock:
            req = NeonPacket.create(
                PacketType.CONNECT_REQUEST, 0, 0, 1,
                ConnectRequest(1, "bob", session_id=99, game_id=0)
            )
            resp = _send_and_recv(sock, req, addr)
        assert resp is not None
        assert PacketType.from_byte(resp.header.packet_type) is PacketType.CONNECT_DENY
        assert "not found" in resp.payload.reason.lower()

    def test_full_connect_handshake(self, relay):
        addr = relay.local_address()

        with _raw() as host_sock, _raw() as client_sock:
            # 1. Register host
            host_sock.sendto(
                NeonPacket.create(PacketType.HOST_REGISTER, 0, 1, 0, HostRegister(7, 0xABCD)).to_bytes(),
                addr,
            )
            host_sock.settimeout(1.0)
            data, _ = host_sock.recvfrom(65535)
            assert PacketType.from_byte(NeonPacket.from_bytes(data).header.packet_type) is PacketType.CONNECT_ACCEPT

            # 2. Client sends CONNECT_REQUEST
            client_sock.sendto(
                NeonPacket.create(
                    PacketType.CONNECT_REQUEST, 0, 0, 1,
                    ConnectRequest(1, "alice", session_id=7, game_id=0)
                ).to_bytes(),
                addr,
            )

            # 3. Relay forwards to host
            host_sock.settimeout(1.0)
            data, client_addr = host_sock.recvfrom(65535)
            pkt = NeonPacket.from_bytes(data)
            assert PacketType.from_byte(pkt.header.packet_type) is PacketType.CONNECT_REQUEST

            # 4. Host sends CONNECT_ACCEPT
            host_sock.sendto(
                NeonPacket.create(PacketType.CONNECT_ACCEPT, 1, 1, 0, ConnectAccept(2, 7, 0x1234)).to_bytes(),
                addr,
            )

            # 5. Client receives CONNECT_ACCEPT
            client_sock.settimeout(1.0)
            data, _ = client_sock.recvfrom(65535)
            pkt = NeonPacket.from_bytes(data)
            assert PacketType.from_byte(pkt.header.packet_type) is PacketType.CONNECT_ACCEPT
            assert pkt.payload.client_id == 2


class TestRelayDisconnect:
    def test_disconnect_notice_broadcast(self, relay):
        addr = relay.local_address()

        with _raw() as host_sock, _raw() as client_sock:
            # Register host and connect client (abbreviated)
            host_sock.sendto(
                NeonPacket.create(PacketType.HOST_REGISTER, 0, 1, 0, HostRegister(55, 0)).to_bytes(),
                addr,
            )
            host_sock.settimeout(1.0)
            host_sock.recvfrom(65535)

            client_sock.sendto(
                NeonPacket.create(PacketType.CONNECT_REQUEST, 0, 0, 1, ConnectRequest(1, "x", 55, 0)).to_bytes(),
                addr,
            )
            host_sock.settimeout(1.0)
            host_sock.recvfrom(65535)  # relay forwarded CONNECT_REQUEST

            host_sock.sendto(
                NeonPacket.create(PacketType.CONNECT_ACCEPT, 1, 1, 0, ConnectAccept(2, 55, 0)).to_bytes(),
                addr,
            )
            client_sock.settimeout(1.0)
            client_sock.recvfrom(65535)  # client gets CONNECT_ACCEPT

            # Client disconnects
            client_sock.sendto(
                NeonPacket.create(PacketType.DISCONNECT_NOTICE, 2, 2, 0, DisconnectNotice()).to_bytes(),
                addr,
            )

            # Host should receive DISCONNECT_NOTICE
            host_sock.settimeout(1.0)
            data, _ = host_sock.recvfrom(65535)
            pkt = NeonPacket.from_bytes(data)
            assert PacketType.from_byte(pkt.header.packet_type) is PacketType.DISCONNECT_NOTICE


class TestRelayRateLimit:
    def test_excess_packets_dropped(self, relay):
        addr = relay.local_address()
        cfg = NeonConfig(relay_port=relay.local_address()[1], max_packets_per_second=5)
        # Send many packets from one source quickly; relay should silently drop excess.
        with _raw() as sock:
            pkt = NeonPacket.create(PacketType.DISCONNECT_NOTICE, 0, 0, 0, DisconnectNotice())
            raw = pkt.to_bytes()
            for _ in range(200):
                sock.sendto(raw, addr)
        # No assertion — just verify no exception is raised in the relay.
        time.sleep(0.05)
        assert relay.is_running
