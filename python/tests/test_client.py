"""Tests for NeonClient with a mock relay."""

import socket
import threading
import time
import pytest

from qti_neon import (
    NeonConfig,
    NeonClient,
    NeonPacket,
    PacketType,
    ConnectRequest,
    ConnectAccept,
    ConnectDeny,
    SessionConfig,
    PacketTypeRegistry,
    PacketTypeEntry,
    Ping,
    Pong,
    Ack,
    DisconnectNotice,
    ReconnectRequest,
    GamePacket,
    VERSION,
)


class MockRelay:
    """UDP server stub that continuously collects inbound packets."""

    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.received: list[tuple[NeonPacket, tuple]] = []
        self._lock = threading.Lock()
        self._running = True
        self._thread = threading.Thread(target=self._collect_loop, daemon=True)
        self._thread.start()

    @property
    def address(self) -> tuple[str, int]:
        return self.sock.getsockname()

    def _collect_loop(self) -> None:
        self.sock.settimeout(0.05)
        while self._running:
            try:
                data, addr = self.sock.recvfrom(65535)
                pkt = NeonPacket.from_bytes(data)
                with self._lock:
                    self.received.append((pkt, addr))
            except (socket.timeout, OSError):
                pass
            except Exception:
                pass

    def wait_for(self, ptype: PacketType, timeout: float = 2.0) -> NeonPacket | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._lock:
                for pkt, _ in self.received:
                    if PacketType.from_byte(pkt.header.packet_type) is ptype:
                        return pkt
            time.sleep(0.01)
        return None

    def all_of_type(self, ptype: PacketType) -> list[NeonPacket]:
        with self._lock:
            return [p for p, _ in self.received if PacketType.from_byte(p.header.packet_type) is ptype]

    def last_client_addr(self) -> tuple | None:
        with self._lock:
            return self.received[-1][1] if self.received else None

    def send(self, packet: NeonPacket, addr: tuple) -> None:
        self.sock.sendto(packet.to_bytes(), addr)

    def close(self) -> None:
        self._running = False
        self.sock.close()


@pytest.fixture
def relay():
    r = MockRelay()
    yield r
    r.close()


def _make_client(relay: MockRelay, name="player1", cfg=None) -> NeonClient:
    if cfg is None:
        cfg = NeonConfig(
            client_connection_timeout_ms=1000,
            client_processing_loop_sleep_ms=5,
            client_ping_interval_ms=30000,  # disable auto-ping
        )
    return NeonClient(name, cfg)


def _accept_connect_in_background(
    relay: MockRelay,
    client_id: int = 2,
    session_id: int = 1,
    token: int = 0x1234,
) -> threading.Event:
    """Wait for CONNECT_REQUEST then send CONNECT_ACCEPT; returns an event set on completion."""
    done = threading.Event()

    def _run():
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline:
            pkt = relay.wait_for(PacketType.CONNECT_REQUEST, timeout=2.0)
            if pkt is not None:
                with relay._lock:
                    addr = next(
                        a for p, a in relay.received
                        if PacketType.from_byte(p.header.packet_type) is PacketType.CONNECT_REQUEST
                    )
                relay.send(
                    NeonPacket.create(
                        PacketType.CONNECT_ACCEPT, 0, 1, 0,
                        ConnectAccept(client_id, session_id, token),
                    ),
                    addr,
                )
                done.set()
                return

    threading.Thread(target=_run, daemon=True).start()
    return done


class TestClientConnect:
    def test_connect_success(self, relay):
        done = _accept_connect_in_background(relay)
        client = _make_client(relay)
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        done.wait(2.0)
        assert ok
        assert client.client_id == 2
        client.stop()

    def test_connect_deny(self, relay):
        def deny():
            pkt = relay.wait_for(PacketType.CONNECT_REQUEST)
            if pkt:
                with relay._lock:
                    addr = next(a for p, a in relay.received
                                if PacketType.from_byte(p.header.packet_type) is PacketType.CONNECT_REQUEST)
                relay.send(
                    NeonPacket.create(PacketType.CONNECT_DENY, 0, 0, 0, ConnectDeny("Full")),
                    addr,
                )

        threading.Thread(target=deny, daemon=True).start()
        client = _make_client(relay)
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        assert not ok

    def test_connect_timeout(self):
        # Use a port with nothing listening
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        cfg = NeonConfig(client_connection_timeout_ms=200)
        client = NeonClient("p", cfg)
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{port}")
        assert not ok

    def test_connect_request_fields(self, relay):
        done = _accept_connect_in_background(relay)
        client = _make_client(relay, name="alice")
        ok = client.connect(session_id=42, relay_address=f"127.0.0.1:{relay.address[1]}")
        done.wait(2.0)
        assert ok

        req = relay.wait_for(PacketType.CONNECT_REQUEST)
        assert req is not None
        assert isinstance(req.payload, ConnectRequest)
        assert req.payload.name == "alice"
        assert req.payload.session_id == 42
        assert req.payload.client_version == VERSION
        client.stop()


class TestClientSessionConfig:
    def test_session_config_triggers_ack(self, relay):
        configs: list[SessionConfig] = []
        client = _make_client(relay)
        client.set_session_config_callback(configs.append)

        def run_relay():
            relay.wait_for(PacketType.CONNECT_REQUEST, timeout=2.0)
            with relay._lock:
                addr = next(a for p, a in relay.received
                            if PacketType.from_byte(p.header.packet_type) is PacketType.CONNECT_REQUEST)
            relay.send(
                NeonPacket.create(PacketType.CONNECT_ACCEPT, 0, 1, 0, ConnectAccept(2, 1, 0)),
                addr,
            )
            time.sleep(0.05)
            relay.send(
                NeonPacket.create(PacketType.SESSION_CONFIG, 5, 1, 2, SessionConfig(1, 60, 1200)),
                addr,
            )

        threading.Thread(target=run_relay, daemon=True).start()
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        assert ok
        threading.Thread(target=client.run, daemon=True).start()

        ack = relay.wait_for(PacketType.ACK, timeout=2.0)
        assert ack is not None
        assert 5 in ack.payload.sequences
        assert len(configs) == 1
        assert configs[0].tick_rate == 60
        client.stop()


class TestClientPing:
    def test_responds_to_ping_with_pong(self, relay):
        client = _make_client(relay)

        def run_relay():
            relay.wait_for(PacketType.CONNECT_REQUEST, timeout=2.0)
            with relay._lock:
                addr = next(a for p, a in relay.received
                            if PacketType.from_byte(p.header.packet_type) is PacketType.CONNECT_REQUEST)
            relay.send(
                NeonPacket.create(PacketType.CONNECT_ACCEPT, 0, 1, 0, ConnectAccept(2, 1, 0)),
                addr,
            )
            time.sleep(0.05)
            relay.send(
                NeonPacket.create(PacketType.PING, 0, 1, 2, Ping(99999)),
                addr,
            )

        threading.Thread(target=run_relay, daemon=True).start()
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        assert ok
        threading.Thread(target=client.run, daemon=True).start()

        pong = relay.wait_for(PacketType.PONG, timeout=2.0)
        assert pong is not None
        assert pong.payload.original_timestamp == 99999
        client.stop()


class TestClientAutoPing:
    def test_auto_ping_sent(self, relay):
        cfg = NeonConfig(
            client_ping_interval_ms=50,
            client_processing_loop_sleep_ms=5,
            client_connection_timeout_ms=1000,
        )
        client = NeonClient("p", cfg)

        done = _accept_connect_in_background(relay)
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        done.wait(2.0)
        assert ok
        threading.Thread(target=client.run, daemon=True).start()

        ping = relay.wait_for(PacketType.PING, timeout=2.0)
        assert ping is not None
        client.stop()


class TestClientDisconnect:
    def test_stop_sends_disconnect_notice(self, relay):
        done = _accept_connect_in_background(relay)
        client = _make_client(relay)
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        done.wait(2.0)
        assert ok
        client.stop()

        notice = relay.wait_for(PacketType.DISCONNECT_NOTICE, timeout=2.0)
        assert notice is not None


class TestClientSendPacket:
    def test_send_packet_game_type(self, relay):
        done = _accept_connect_in_background(relay)
        client = _make_client(relay)
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        done.wait(2.0)
        assert ok

        client.send_packet(b"\x01\x02\x03", packet_type=0x10, dest_id=0)

        pkt = relay.wait_for(PacketType.GAME_PACKET, timeout=2.0)
        assert pkt is not None
        assert pkt.payload.payload == b"\x01\x02\x03"
        client.stop()


class TestClientUnhandledCallback:
    def test_unhandled_callback_for_game_packets(self, relay):
        received: list = []
        cfg = NeonConfig(
            client_connection_timeout_ms=1000,
            client_processing_loop_sleep_ms=5,
            client_ping_interval_ms=30000,
        )
        client = NeonClient("p", cfg)
        client.set_unhandled_packet_callback(lambda pt, sid: received.append((pt, sid)))

        client_addr_holder: list = []

        def run_relay():
            relay.wait_for(PacketType.CONNECT_REQUEST, timeout=2.0)
            with relay._lock:
                addr = next(a for p, a in relay.received
                            if PacketType.from_byte(p.header.packet_type) is PacketType.CONNECT_REQUEST)
            client_addr_holder.append(addr)
            relay.send(
                NeonPacket.create(PacketType.CONNECT_ACCEPT, 0, 1, 0, ConnectAccept(2, 1, 0)),
                addr,
            )
            time.sleep(0.05)
            relay.send(
                NeonPacket.create(PacketType.GAME_PACKET, 0, 1, 2, GamePacket(b"game_data")),
                addr,
            )

        threading.Thread(target=run_relay, daemon=True).start()
        ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{relay.address[1]}")
        assert ok
        threading.Thread(target=client.run, daemon=True).start()

        deadline = time.monotonic() + 2.0
        while not received and time.monotonic() < deadline:
            time.sleep(0.01)

        assert len(received) >= 1
        assert received[0][0] == 0x10
        client.stop()
