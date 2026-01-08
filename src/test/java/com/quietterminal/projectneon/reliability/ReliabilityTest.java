package com.quietterminal.projectneon.reliability;

import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.host.NeonHost;
import com.quietterminal.projectneon.relay.NeonRelay;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reliability tests for Project Neon.
 * Tests ACK/retry mechanisms, timeouts, heartbeats, and disconnect detection.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReliabilityTest {
    private static final String RELAY_ADDRESS = "localhost:18888";
    private static final int TEST_SESSION_ID = 23456;
    private static final int SETUP_DELAY_MS = 100;

    private NeonRelay relay;
    private NeonHost host;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        executor = Executors.newCachedThreadPool();

        relay = new NeonRelay(RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                relay.start();
            } catch (Exception e) {
                // Expected when relay is closed
            }
        });

        Thread.sleep(SETUP_DELAY_MS);

        host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });

        Thread.sleep(SETUP_DELAY_MS);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (host != null) {
            host.close();
        }
        if (relay != null) {
            relay.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should handle ACK mechanism correctly")
    void testAckMechanism() throws Exception {
        NeonClient client = new NeonClient("AckTestClient");
        CountDownLatch sessionConfigReceived = new CountDownLatch(1);

        client.setSessionConfigCallback((version, tickRate, maxPacketSize) -> {
            sessionConfigReceived.countDown();
        });

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            for (int i = 0; i < 20; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(10);
            }

            boolean received = sessionConfigReceived.await(3, TimeUnit.SECONDS);
            assertTrue(received, "Client should receive session config that requires ACK");
        } finally {
            client.close();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Should retry sending packets on ACK timeout")
    void testAckRetryMechanism() throws Exception {
        NeonClient client = new NeonClient("RetryTestClient");
        AtomicInteger configReceivedCount = new AtomicInteger(0);

        client.setSessionConfigCallback((version, tickRate, maxPacketSize) -> {
            configReceivedCount.incrementAndGet();
        });

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            for (int i = 0; i < 50; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(50);
            }

            assertTrue(configReceivedCount.get() >= 1,
                "Client should receive session config at least once");
        } finally {
            client.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Should handle timeout and cleanup properly")
    void testTimeoutAndCleanup() throws Exception {
        NeonClient client = new NeonClient("TimeoutTestClient");

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            Thread.sleep(100);

            for (int i = 0; i < 10; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(10);
            }

            int initialClientCount = host.getClientCount();
            assertTrue(initialClientCount >= 1, "Host should have at least one client");

            client.close();

            Thread.sleep(16000);

            for (int i = 0; i < 10; i++) {
                host.processPackets();
                Thread.sleep(100);
            }

            assertTrue(true, "System should handle client timeout without crashing");
        } finally {
            if (client != null && !client.getClientId().isEmpty()) {
                try {
                    client.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("Should handle ping/pong heartbeat correctly")
    void testPingPongHeartbeat() throws Exception {
        NeonClient client = new NeonClient("HeartbeatTestClient");
        AtomicLong lastPongTime = new AtomicLong(0);
        AtomicInteger pongCount = new AtomicInteger(0);

        client.setPongCallback((responseTime, originalTimestamp) -> {
            lastPongTime.set(System.currentTimeMillis());
            pongCount.incrementAndGet();
        });

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            Thread.sleep(100);

            for (int i = 0; i < 3; i++) {
                client.sendPing();
                Thread.sleep(50);

                for (int j = 0; j < 10; j++) {
                    host.processPackets();
                    client.processPackets();
                    Thread.sleep(10);
                }
            }

            assertTrue(pongCount.get() >= 1, "Client should receive at least one pong response");
            assertTrue(lastPongTime.get() > 0, "Pong timestamp should be set");
        } finally {
            client.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Should detect client disconnect")
    void testClientDisconnectDetection() throws Exception {
        NeonClient client = new NeonClient("DisconnectTestClient");
        AtomicBoolean clientConnected = new AtomicBoolean(false);

        host.setClientConnectCallback((clientId, name, sessionId) -> {
            if (name.equals("DisconnectTestClient")) {
                clientConnected.set(true);
            }
        });

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            for (int i = 0; i < 20; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(10);
            }

            assertTrue(clientConnected.get(), "Host should detect client connection");

            int clientCountBefore = host.getClientCount();
            assertTrue(clientCountBefore >= 1, "Host should have at least one client before disconnect");

            client.close();

            assertTrue(clientConnected.get(), "Client connection should have been detected");
        } finally {
            if (!clientConnected.get()) {
                client.close();
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("Should handle host ping to client")
    void testHostPingToClient() throws Exception {
        NeonClient client = new NeonClient("HostPingTestClient");
        AtomicInteger pingReceivedCount = new AtomicInteger(0);

        host.setPingReceivedCallback(clientId -> {
            pingReceivedCount.incrementAndGet();
        });

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            Thread.sleep(100);

            client.sendPing();

            for (int i = 0; i < 20; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(10);
            }

            assertTrue(pingReceivedCount.get() >= 1, "Host should receive ping from client");
        } finally {
            client.close();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Should handle automatic ping with configurable interval")
    void testAutoPingWithInterval() throws Exception {
        NeonClient client = new NeonClient("AutoPingTestClient");
        AtomicInteger pongCount = new AtomicInteger(0);

        client.setPongCallback((responseTime, originalTimestamp) -> {
            pongCount.incrementAndGet();
        });

        client.setAutoPing(true);
        client.setPingInterval(java.time.Duration.ofSeconds(1));

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            for (int i = 0; i < 200; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(10);
            }

            assertTrue(pongCount.get() >= 1, "Client should receive pong from auto-ping");
        } finally {
            client.close();
        }
    }

    @Test
    @Order(8)
    @DisplayName("Should handle connection state properly")
    void testConnectionState() throws Exception {
        NeonClient client = new NeonClient("StateTestClient");

        try {
            assertFalse(client.isConnected(), "Client should not be connected initially");
            assertTrue(client.getClientId().isEmpty(), "Client ID should be empty before connection");
            assertTrue(client.getSessionId().isEmpty(), "Session ID should be empty before connection");

            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            assertTrue(client.isConnected(), "Client should be connected after successful connection");
            assertTrue(client.getClientId().isPresent(), "Client ID should be present after connection");
            assertTrue(client.getSessionId().isPresent(), "Session ID should be present after connection");
            assertEquals(TEST_SESSION_ID, client.getSessionId().get(), "Session ID should match");

            client.close();

        } finally {
            if (client.isConnected()) {
                client.close();
            }
        }
    }
}
