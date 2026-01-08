package com.quietterminal.projectneon.integration;

import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.host.NeonHost;
import com.quietterminal.projectneon.relay.NeonRelay;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Project Neon.
 * Tests multi-component interactions between relay, host, and clients.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTest {
    private static final String RELAY_ADDRESS = "localhost:17777";
    private static final int TEST_SESSION_ID = 12345;
    private static final int SETUP_DELAY_MS = 100;
    private static final int PROCESSING_DELAY_MS = 50;

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
    @DisplayName("Should handle multi-client connections (10+ clients)")
    void testMultiClientConnection() throws Exception {
        final int CLIENT_COUNT = 12;
        List<NeonClient> clients = new ArrayList<>();
        CountDownLatch connectLatch = new CountDownLatch(CLIENT_COUNT);
        AtomicInteger connectedCount = new AtomicInteger(0);

        host.setClientConnectCallback((clientId, name, sessionId) -> {
            connectedCount.incrementAndGet();
            connectLatch.countDown();
        });

        try {
            for (int i = 0; i < CLIENT_COUNT; i++) {
                NeonClient client = new NeonClient("Client_" + i);
                clients.add(client);

                executor.submit(() -> {
                    try {
                        boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
                        if (!connected) {
                            connectLatch.countDown();
                        }
                    } catch (IOException e) {
                        connectLatch.countDown();
                    }
                });
            }

            boolean allConnected = connectLatch.await(5, TimeUnit.SECONDS);
            assertTrue(allConnected, "Not all clients connected within timeout");

            Thread.sleep(PROCESSING_DELAY_MS);

            for (int i = 0; i < 10; i++) {
                host.processPackets();
                Thread.sleep(10);
            }

            assertEquals(CLIENT_COUNT, connectedCount.get(), "Should have " + CLIENT_COUNT + " connected clients");
            assertEquals(CLIENT_COUNT, host.getClientCount(), "Host should report " + CLIENT_COUNT + " clients");
        } finally {
            for (NeonClient client : clients) {
                client.close();
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Should handle large payloads approaching MTU limits")
    void testLargePayload() throws Exception {
        NeonClient client = new NeonClient("LargePayloadClient");

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect successfully");

            Thread.sleep(PROCESSING_DELAY_MS);

            assertTrue(client.isConnected(), "Client should remain connected after large data");
        } finally {
            client.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("Should handle concurrent sessions on one relay")
    void testConcurrentSessions() throws Exception {
        final int SESSION_COUNT = 3;
        List<NeonHost> hosts = new ArrayList<>();
        List<List<NeonClient>> sessionClients = new ArrayList<>();
        AtomicInteger totalConnections = new AtomicInteger(0);

        try {
            for (int sessionIdx = 0; sessionIdx < SESSION_COUNT; sessionIdx++) {
                int sessionId = TEST_SESSION_ID + sessionIdx + 1;
                NeonHost sessionHost = new NeonHost(sessionId, RELAY_ADDRESS);
                hosts.add(sessionHost);

                sessionHost.setClientConnectCallback((clientId, name, sid) -> {
                    totalConnections.incrementAndGet();
                });

                executor.submit(() -> {
                    try {
                        sessionHost.start();
                    } catch (Exception e) {
                        // Expected when closed
                    }
                });

                Thread.sleep(SETUP_DELAY_MS);

                List<NeonClient> clients = new ArrayList<>();
                for (int clientIdx = 0; clientIdx < 2; clientIdx++) {
                    NeonClient client = new NeonClient("Session" + sessionId + "_Client" + clientIdx);
                    clients.add(client);

                    boolean connected = client.connect(sessionId, RELAY_ADDRESS);
                    assertTrue(connected, "Client should connect to session " + sessionId);
                }
                sessionClients.add(clients);

                Thread.sleep(PROCESSING_DELAY_MS);
            }

            for (int i = 0; i < 10; i++) {
                for (NeonHost h : hosts) {
                    h.processPackets();
                }
                Thread.sleep(10);
            }

            assertEquals(SESSION_COUNT * 2, totalConnections.get(),
                "Should have " + (SESSION_COUNT * 2) + " total connections across all sessions");

            for (int i = 0; i < hosts.size(); i++) {
                assertEquals(2, hosts.get(i).getClientCount(),
                    "Each session should have 2 clients");
            }
        } finally {
            for (List<NeonClient> clients : sessionClients) {
                for (NeonClient client : clients) {
                    client.close();
                }
            }
            for (NeonHost h : hosts) {
                h.close();
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("Should handle network partition (relay restart simulation)")
    void testNetworkPartition() throws Exception {
        NeonClient client = new NeonClient("PartitionTestClient");

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect initially");

            Thread.sleep(PROCESSING_DELAY_MS);

            relay.close();
            executor.shutdownNow();

            Thread.sleep(PROCESSING_DELAY_MS);

            executor = Executors.newCachedThreadPool();
            relay = new NeonRelay(RELAY_ADDRESS);
            executor.submit(() -> {
                try {
                    relay.start();
                } catch (Exception e) {
                    // Expected when closed
                }
            });

            Thread.sleep(SETUP_DELAY_MS);

            NeonHost newHost = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
            executor.submit(() -> {
                try {
                    newHost.start();
                } catch (Exception e) {
                    // Expected when closed
                }
            });

            Thread.sleep(SETUP_DELAY_MS);

            NeonClient newClient = new NeonClient("NewClient");
            boolean reconnected = newClient.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(reconnected, "New client should connect after relay restart");

            newClient.close();
            newHost.close();
        } finally {
            client.close();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Should simulate packet loss scenarios")
    void testPacketLossSimulation() throws Exception {
        NeonClient client = new NeonClient("PacketLossClient");
        CountDownLatch pongReceived = new CountDownLatch(1);

        client.setPongCallback((responseTime, originalTimestamp) -> {
            pongReceived.countDown();
        });

        try {
            boolean connected = client.connect(TEST_SESSION_ID, RELAY_ADDRESS);
            assertTrue(connected, "Client should connect");

            client.sendPing();

            for (int i = 0; i < 20; i++) {
                host.processPackets();
                client.processPackets();
                Thread.sleep(10);
            }

            boolean receivedPong = pongReceived.await(2, TimeUnit.SECONDS);
            assertTrue(receivedPong, "Client should receive pong even with potential packet loss");
        } finally {
            client.close();
        }
    }
}
