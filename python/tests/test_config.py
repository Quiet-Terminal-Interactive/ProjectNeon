"""Tests for NeonConfig validation."""

import pytest
from qti_neon import NeonConfig


class TestNeonConfigDefaults:
    def test_defaults_instantiate(self):
        cfg = NeonConfig()
        assert cfg.relay_port == 7777
        assert cfg.max_packets_per_second == 100
        assert cfg.host_session_tick_rate == 60
        assert cfg.client_max_reconnect_attempts == 6
        assert cfg.dtls_config is None
        assert not cfg.is_dtls_enabled

    def test_custom_values(self):
        cfg = NeonConfig(relay_port=9999, host_session_tick_rate=30)
        assert cfg.relay_port == 9999
        assert cfg.host_session_tick_rate == 30

    def test_frozen(self):
        cfg = NeonConfig()
        with pytest.raises((AttributeError, TypeError)):
            cfg.relay_port = 1234  # type: ignore[misc]


class TestNeonConfigValidation:
    def test_buffer_size_minimum(self):
        with pytest.raises(ValueError):
            NeonConfig(buffer_size=7)

    def test_pool_max_less_than_init_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(buffer_pool_init_size=10, buffer_pool_max_size=5)

    def test_relay_port_zero_is_allowed(self):
        # relay_port has no lower-bound restriction in the spec
        cfg = NeonConfig(relay_port=0)
        assert cfg.relay_port == 0

    def test_negative_cleanup_interval_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(relay_cleanup_interval_ms=-1)

    def test_max_pending_connections_zero_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(max_pending_connections=0)

    def test_tick_rate_zero_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(host_session_tick_rate=0)

    def test_max_packet_size_zero_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(host_session_max_packet_size=0)

    def test_negative_ack_timeout_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(host_ack_timeout_ms=-1)

    def test_negative_connection_timeout_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(client_connection_timeout_ms=-1)

    def test_negative_reliable_timeout_raises(self):
        with pytest.raises(ValueError):
            NeonConfig(reliable_packet_timeout_ms=-1)

    def test_zero_values_allowed_where_appropriate(self):
        # Zero timeouts are allowed (means "disable" or "immediate")
        cfg = NeonConfig(
            relay_cleanup_interval_ms=0,
            host_ack_timeout_ms=0,
            client_connection_timeout_ms=0,
        )
        assert cfg.relay_cleanup_interval_ms == 0


class TestNeonConfigDtls:
    def test_dtls_enabled_flag(self):
        from qti_neon import DtlsConfig
        dc = DtlsConfig.insecure_trust_all()
        cfg = NeonConfig(dtls_config=dc)
        assert cfg.is_dtls_enabled
        assert cfg.dtls_config is dc
