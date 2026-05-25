package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeonConfigTest {

        @Test
        void defaultsAreSane() {
                NeonConfig cfg = NeonConfig.defaults();
                assertEquals(65535, cfg.getBufferSize());
                assertEquals(16, cfg.getBufferPoolInitSize());
                assertEquals(64, cfg.getBufferPoolMaxSize());
                assertTrue(cfg.isEnforceBufferSize());
                assertEquals(7777, cfg.getRelayPort());
                assertEquals(100, cfg.getRelaySocketTimeoutMs());
                assertEquals(5000, cfg.getRelayCleanupIntervalMs());
                assertEquals(15000, cfg.getRelayClientTimeoutMs());
                assertEquals(1, cfg.getRelayMainLoopSleepMs());
                assertEquals(64, cfg.getMaxPendingConnections());
                assertEquals(1024, cfg.getMaxRateLimiters());
                assertEquals(100, cfg.getMaxPacketsPerSecond());
                assertEquals(32, cfg.getMaxClientsPerSession());
                assertEquals(1024, cfg.getMaxTotalConnections());
                assertEquals(100, cfg.getClientSocketTimeoutMs());
                assertEquals(5000, cfg.getClientConnectionTimeoutMs());
                assertEquals(5000, cfg.getClientPingIntervalMs());
                assertEquals(1000, cfg.getClientInitialReconnectDelayMs());
                assertEquals(30000, cfg.getClientMaxReconnectDelayMs());
                assertEquals(6, cfg.getClientMaxReconnectAttempts());
                assertEquals(50, cfg.getClientDisconnectNoticeDelayMs());
                assertEquals(10, cfg.getClientProcessingLoopSleepMs());
                assertEquals(100, cfg.getHostSocketTimeoutMs());
                assertEquals(2000, cfg.getHostAckTimeoutMs());
                assertEquals(5, cfg.getHostMaxAckRetries());
                assertEquals(300000, cfg.getHostSessionTokenTimeoutMs());
                assertEquals(3000, cfg.getHostGracefulShutdownTimeoutMs());
                assertEquals(10, cfg.getHostProcessingLoopSleepMs());
                assertEquals((short) 60, cfg.getHostSessionTickRate());
                assertEquals((short) 1200, cfg.getHostSessionMaxPacketSize());
                assertEquals(2000, cfg.getReliablePacketTimeoutMs());
                assertEquals(5, cfg.getReliablePacketMaxRetries());
        }

        @Test
        void builderOverridesSelectedFields() {
                NeonConfig cfg = NeonConfig.builder()
                                .relayPort(9999)
                                .maxClientsPerSession(8)
                                .hostSessionTickRate((short) 30)
                                .clientMaxReconnectAttempts(3)
                                .build();
                assertEquals(9999, cfg.getRelayPort());
                assertEquals(8, cfg.getMaxClientsPerSession());
                assertEquals((short) 30, cfg.getHostSessionTickRate());
                assertEquals(3, cfg.getClientMaxReconnectAttempts());
                assertEquals(65535, cfg.getBufferSize());
                assertEquals((short) 1200, cfg.getHostSessionMaxPacketSize());
        }

        @Test
        void builderIsImmutable() {
                var builder = NeonConfig.builder().relayPort(8888);
                NeonConfig a = builder.build();
                NeonConfig b = builder.relayPort(9999).build();
                assertEquals(8888, a.getRelayPort());
                assertEquals(9999, b.getRelayPort());
        }

        @Test
        void validateRejectsBufferSizeTooSmall() {
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().bufferSize(7).build());
        }

        @Test
        void validateRejectsNegativeTimeouts() {
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().relaySocketTimeoutMs(-1).build());
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().clientConnectionTimeoutMs(-1).build());
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().hostAckTimeoutMs(-1).build());
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().reliablePacketTimeoutMs(-1).build());
        }

        @Test
        void validateRejectsNegativeRetryCounts() {
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().hostMaxAckRetries(-1).build());
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().reliablePacketMaxRetries(-1).build());
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().clientMaxReconnectAttempts(-1).build());
        }

        @Test
        void validateRejectsZeroOrNegativeTickRate() {
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().hostSessionTickRate((short) 0).build());
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().hostSessionTickRate((short) -1).build());
        }

        @Test
        void validateRejectsInvalidBufferPool() {
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().bufferPoolInitSize(32).bufferPoolMaxSize(16).build());
        }

        @Test
        void validateRejectsZeroMaxClientsPerSession() {
                assertThrows(IllegalArgumentException.class,
                                () -> NeonConfig.builder().maxClientsPerSession(0).build());
        }

        @Test
        void zeroRetriesIsValid() {
                assertDoesNotThrow(() -> NeonConfig.builder()
                                .hostMaxAckRetries(0)
                                .reliablePacketMaxRetries(0)
                                .clientMaxReconnectAttempts(0)
                                .build());
        }

        @Test
        void zeroTimeoutsAreValid() {
                assertDoesNotThrow(() -> NeonConfig.builder()
                                .relaySocketTimeoutMs(0)
                                .clientPingIntervalMs(0)
                                .hostGracefulShutdownTimeoutMs(0)
                                .build());
        }
}
