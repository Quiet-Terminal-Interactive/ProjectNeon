"""Full-stack integration tests: relay + host + client over loopback.

These tests use real UDP sockets and real NeonRelay / NeonHost / NeonClient
instances communicating over 127.0.0.1.
"""

import socket
import threading
import time
import pytest

from qti_neon import (
    NeonConfig,
    NeonRelay,
    NeonHost,
    NeonClient,
    SessionConfig,
    PacketTypeRegistry,
)
from qti_neon._registry import GamePacketRegistry


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def stack():
    """Spin up a relay + host + one connected client, tear down after the test."""
    port = _free_port()
    cfg = NeonConfig(
        relay_port=port,
        relay_main_loop_sleep_ms=1,
        host_processing_loop_sleep_ms=5,
        client_processing_loop_sleep_ms=5,
        host_ack_timeout_ms=500,
        client_connection_timeout_ms=2000,
        client_ping_interval_ms=30000,  # disable auto-ping during tests
    )

    relay = NeonRelay("127.0.0.1", cfg)
    relay_thread = threading.Thread(target=relay.start_and_run, daemon=True)
    relay_thread.start()
    time.sleep(0.05)

    host = NeonHost(session_id=1, relay_address=f"127.0.0.1:{port}", config=cfg)
    host_thread = threading.Thread(target=host.start_and_run, daemon=True)
    host_thread.start()
    time.sleep(0.1)

    client = NeonClient("player1", cfg)
    ok = client.connect(session_id=1, relay_address=f"127.0.0.1:{port}")
    assert ok, "client.connect() failed"
    client_thread = threading.Thread(target=client.run, daemon=True)
    client_thread.start()
    time.sleep(0.1)

    yield relay, host, client, cfg

    if client.is_running:
        client.stop()
    if host.is_running:
        host.stop()
    if relay.is_running:
        relay.stop()


class TestIntegrationBasic:
    def test_client_assigned_id(self, stack):
        _, host, client, _ = stack
        assert client.client_id == 2

    def test_host_sees_client(self, stack):
        _, host, client, _ = stack
        clients = host.connected_clients()
        assert 2 in clients
        assert clients[2] == "player1"

    def test_session_config_delivered(self, stack):
        port = _free_port()
        cfg = NeonConfig(
            relay_port=port,
            relay_main_loop_sleep_ms=1,
            host_processing_loop_sleep_ms=5,
            client_processing_loop_sleep_ms=5,
            client_connection_timeout_ms=2000,
            host_session_tick_rate=30,
            client_ping_interval_ms=30000,
        )
        relay = NeonRelay("127.0.0.1", cfg)
        threading.Thread(target=relay.start_and_run, daemon=True).start()
        time.sleep(0.05)

        host = NeonHost(1, f"127.0.0.1:{port}", cfg)
        threading.Thread(target=host.start_and_run, daemon=True).start()
        time.sleep(0.1)

        configs: list[SessionConfig] = []
        client = NeonClient("p", cfg)
        client.set_session_config_callback(configs.append)
        ok = client.connect(1, f"127.0.0.1:{port}")
        assert ok
        threading.Thread(target=client.run, daemon=True).start()
        time.sleep(0.2)

        assert len(configs) == 1
        assert configs[0].tick_rate == 30

        client.stop()
        host.stop()
        relay.stop()

    def test_registry_delivered(self, stack):
        port = _free_port()
        cfg = NeonConfig(
            relay_port=port,
            relay_main_loop_sleep_ms=1,
            host_processing_loop_sleep_ms=5,
            client_processing_loop_sleep_ms=5,
            client_connection_timeout_ms=2000,
            client_ping_interval_ms=30000,
        )
        relay = NeonRelay("127.0.0.1", cfg)
        threading.Thread(target=relay.start_and_run, daemon=True).start()
        time.sleep(0.05)

        reg = GamePacketRegistry()
        reg.register(0x10, "MOVE", "Player movement")

        host = NeonHost(1, f"127.0.0.1:{port}", cfg)
        host.set_game_packet_registry(reg)
        threading.Thread(target=host.start_and_run, daemon=True).start()
        time.sleep(0.1)

        registries: list[PacketTypeRegistry] = []
        client = NeonClient("p", cfg)
        client.set_packet_type_registry_callback(registries.append)
        ok = client.connect(1, f"127.0.0.1:{port}")
        assert ok
        threading.Thread(target=client.run, daemon=True).start()
        time.sleep(0.2)

        assert len(registries) == 1
        assert len(registries[0].entries) == 1
        assert registries[0].entries[0].name == "MOVE"

        client.stop()
        host.stop()
        relay.stop()


class TestIntegrationGamePackets:
    def test_client_to_host_game_packet(self, stack):
        _, host, client, _ = stack

        received: list = []
        host.set_unhandled_packet_callback(lambda pt, sid: received.append((pt, sid)))

        client.send_packet(b"\xDE\xAD", packet_type=0x10, dest_id=1)
        time.sleep(0.2)

        assert len(received) >= 1
        ptype, sender = received[0]
        assert ptype == 0x10
        assert sender == 2

    def test_broadcast_to_two_clients(self, stack):
        relay, host, client1, cfg = stack

        received_by_c2: list = []
        client2 = NeonClient("player2", cfg)
        client2.set_unhandled_packet_callback(lambda pt, sid: received_by_c2.append((pt, sid)))
        ok = client2.connect(session_id=1, relay_address=f"127.0.0.1:{cfg.relay_port}")
        assert ok
        threading.Thread(target=client2.run, daemon=True).start()
        time.sleep(0.1)

        # client1 broadcasts to everyone (dest_id=0)
        client1.send_packet(b"\x01\x02", packet_type=0x11, dest_id=0)
        time.sleep(0.2)

        assert len(received_by_c2) >= 1
        assert received_by_c2[0][0] == 0x11

        if client2.is_running:
            client2.stop()


class TestIntegrationReconnect:
    def test_reconnect_after_stop(self, stack):
        relay, host, client, cfg = stack

        assert client.client_id == 2
        client.stop()
        time.sleep(0.1)

        ok = client.reconnect(max_attempts=3)
        assert ok
        assert client.client_id == 2


class TestIntegrationDisconnect:
    def test_host_notified_on_client_disconnect(self, stack):
        _, host, client, _ = stack

        disconnected: list[int] = []
        host.set_client_disconnect_callback(disconnected.append)

        client.stop()
        time.sleep(0.2)

        assert 2 in disconnected

    def test_disconnect_callback_on_client(self, stack):
        relay, host, client, cfg = stack

        dc_events: list[int] = []

        # Connect a second client
        client2 = NeonClient("player2", cfg)
        client2.set_disconnect_callback(dc_events.append)
        ok = client2.connect(session_id=1, relay_address=f"127.0.0.1:{cfg.relay_port}")
        assert ok
        threading.Thread(target=client2.run, daemon=True).start()
        time.sleep(0.1)

        # client1 disconnects — client2 should receive a DISCONNECT_NOTICE
        client.stop()
        time.sleep(0.3)

        assert len(dc_events) >= 1

        if client2.is_running:
            client2.stop()


class TestIntegrationMultipleClients:
    def test_six_clients_connect(self, stack):
        relay, host, client1, cfg = stack
        clients = [client1]

        for i in range(5):
            c = NeonClient(f"player{i + 2}", cfg)
            ok = c.connect(session_id=1, relay_address=f"127.0.0.1:{cfg.relay_port}")
            assert ok, f"client {i + 2} failed to connect"
            threading.Thread(target=c.run, daemon=True).start()
            clients.append(c)

        time.sleep(0.2)
        assert len(host.connected_clients()) == 6

        for c in clients[1:]:
            if c.is_running:
                c.stop()
