package com.quietterminal.projectneon.reliability;

import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.host.NeonHost;
import com.quietterminal.projectneon.relay.NeonRelay;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for disconnect handling (section 4.1 of roadmap).
 */
public class DisconnectHandlingTest {
    private static final int TEST_SESSION_ID = 12345;
    private static final String RELAY_ADDRESS = "localhost:17778";
    private static final int SETUP_DELAY_MS = 150;

    private NeonRelay relay;
    private ExecutorService executor;

    @BeforeEach
    public void setUp() throws IOException, InterruptedException {
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
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (relay != null) {
            relay.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        Thread.sleep(200);
    }

    @Test
    @DisplayName("Client sends DISCONNECT_NOTICE on close")
    public void testClientSendsDisconnectNotice() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicInteger disconnectedClientId = new AtomicInteger(-1);

        host.setClientDisconnectCallback(clientId -> {
            disconnectedClientId.set(clientId);
            disconnectLatch.countDown();
        });

        NeonClient client = new NeonClient("TestClient");
        assertTrue(client.connect(TEST_SESSION_ID, RELAY_ADDRESS));
        byte clientId = client.getClientId().orElseThrow();

        Thread.sleep(SETUP_DELAY_MS);

        client.close();

        assertTrue(disconnectLatch.await(2, TimeUnit.SECONDS),
            "Host should receive disconnect callback");
        assertEquals(clientId, disconnectedClientId.get(),
            "Disconnected client ID should match");

        host.close();
    }

    @Test
    @DisplayName("Host sends DISCONNECT_NOTICE on close")
    public void testHostSendsDisconnectNotice() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        NeonClient client = new NeonClient("TestClient");
        assertTrue(client.connect(TEST_SESSION_ID, RELAY_ADDRESS));

        CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.setDisconnectCallback(clientId -> disconnectLatch.countDown());

        Thread.sleep(SETUP_DELAY_MS);

        host.close();

        for (int i = 0; i < 10; i++) {
            client.processPackets();
            Thread.sleep(20);
        }

        assertTrue(disconnectLatch.await(1, TimeUnit.SECONDS),
            "Client should receive disconnect notice from host");

        client.close();
    }

    @Test
    @DisplayName("Relay removes client on DISCONNECT_NOTICE")
    public void testRelayRemovesClientOnDisconnect() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        NeonClient client1 = new NeonClient("Client1");
        NeonClient client2 = new NeonClient("Client2");

        assertTrue(client1.connect(TEST_SESSION_ID, RELAY_ADDRESS));
        assertTrue(client2.connect(TEST_SESSION_ID, RELAY_ADDRESS));

        Thread.sleep(SETUP_DELAY_MS);

        assertEquals(2, host.getClientCount());

        client1.close();

        Thread.sleep(SETUP_DELAY_MS);

        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertEquals(1, host.getClientCount(),
            "Host should have one client after disconnect");

        client2.close();
        host.close();
    }

    @Test
    @DisplayName("Host cleanup on disconnect")
    public void testHostCleanupOnDisconnect() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        NeonClient client = new NeonClient("TestClient");
        assertTrue(client.connect(TEST_SESSION_ID, RELAY_ADDRESS));
        byte clientId = client.getClientId().orElseThrow();

        Thread.sleep(SETUP_DELAY_MS);

        assertEquals(1, host.getClientCount());
        assertTrue(host.getConnectedClients().containsKey(clientId));

        client.close();

        for (int i = 0; i < 10; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertFalse(host.getConnectedClients().containsKey(clientId),
            "Host should remove client from connected clients map");

        host.close();
    }

    @Test
    @DisplayName("Graceful shutdown waits for pending ACKs")
    public void testGracefulShutdownWaitForAcks() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        NeonClient client = new NeonClient("TestClient");
        assertTrue(client.connect(TEST_SESSION_ID, RELAY_ADDRESS));

        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    client.processPackets();
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                // Expected when interrupted
            }
        });

        Thread.sleep(SETUP_DELAY_MS);

        long startTime = System.currentTimeMillis();
        host.close();
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertTrue(elapsedTime < 3000,
            "Graceful shutdown should complete within timeout");

        client.close();
    }

    @Test
    @DisplayName("Multiple clients disconnect handling")
    public void testMultipleClientsDisconnect() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        AtomicInteger disconnectCount = new AtomicInteger(0);
        host.setClientDisconnectCallback(clientId -> disconnectCount.incrementAndGet());

        NeonClient[] clients = new NeonClient[3];
        for (int i = 0; i < 3; i++) {
            clients[i] = new NeonClient("Client" + i);
            assertTrue(clients[i].connect(TEST_SESSION_ID, RELAY_ADDRESS));
        }

        Thread.sleep(SETUP_DELAY_MS);
        assertEquals(3, host.getClientCount());

        for (NeonClient client : clients) {
            client.close();
            Thread.sleep(50);
        }

        for (int i = 0; i < 10; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertEquals(3, disconnectCount.get(),
            "Host should receive disconnect callbacks for all clients");
        assertEquals(0, host.getClientCount(),
            "Host should have no connected clients");

        host.close();
    }
}
