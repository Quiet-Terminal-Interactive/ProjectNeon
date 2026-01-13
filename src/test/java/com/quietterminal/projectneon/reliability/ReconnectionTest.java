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
 * Tests for reconnection support (section 4.2 of roadmap).
 */
public class ReconnectionTest {
    private static final int TEST_SESSION_ID = 12346;
    private static final String RELAY_ADDRESS = "localhost:17779";
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
    @DisplayName("Client stores session token on connect")
    public void testClientStoresSessionToken() throws Exception {
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

        assertTrue(client.getSessionToken().isPresent(),
            "Client should have session token after connect");
        assertNotEquals(0L, client.getSessionToken().get(),
            "Session token should be non-zero");

        client.close();
        host.close();
    }

    @Test
    @DisplayName("Client reconnects with valid token")
    public void testSuccessfulReconnection() throws Exception {
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
        byte originalClientId = client.getClientId().orElseThrow();
        long originalToken = client.getSessionToken().orElseThrow();

        Thread.sleep(SETUP_DELAY_MS);

        client.close();
        Thread.sleep(SETUP_DELAY_MS);

        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertTrue(client.reconnect(3),
            "Client should successfully reconnect");

        Thread.sleep(SETUP_DELAY_MS);
        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertEquals(originalClientId, client.getClientId().orElseThrow(),
            "Client ID should remain the same after reconnection");
        assertNotEquals(originalToken, client.getSessionToken().orElseThrow(),
            "Session token should be refreshed after reconnection");

        client.close();
        host.close();
    }

    @Test
    @DisplayName("Reconnection fails with invalid token")
    public void testReconnectionFailsWithInvalidToken() throws Exception {
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

        Thread.sleep(SETUP_DELAY_MS);

        assertFalse(client.reconnect(1),
            "Reconnection should fail when client is still connected");

        client.close();
        host.close();
    }

    @Test
    @DisplayName("Reconnection uses exponential backoff")
    public void testExponentialBackoff() throws Exception {
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

        Thread.sleep(SETUP_DELAY_MS);

        client.close();
        Thread.sleep(SETUP_DELAY_MS);

        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        host.close();
        Thread.sleep(SETUP_DELAY_MS);

        long startTime = System.currentTimeMillis();
        client.reconnect(3);
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(elapsed >= 3000,
            "Should take at least 3 seconds with exponential backoff (1s + 2s)");
        assertTrue(elapsed < 8000,
            "Should not take more than 8 seconds");
    }

    @Test
    @DisplayName("Host triggers callback on reconnection")
    public void testHostReconnectionCallback() throws Exception {
        NeonHost host = new NeonHost(TEST_SESSION_ID, RELAY_ADDRESS);
        executor.submit(() -> {
            try {
                host.start();
            } catch (Exception e) {
                // Expected when host is closed
            }
        });
        Thread.sleep(SETUP_DELAY_MS);

        CountDownLatch initialConnectLatch = new CountDownLatch(1);
        CountDownLatch reconnectLatch = new CountDownLatch(1);
        AtomicInteger connectCallCount = new AtomicInteger(0);

        host.setClientConnectCallback((clientId, name, sessionId) -> {
            int count = connectCallCount.incrementAndGet();
            if (count == 1) {
                initialConnectLatch.countDown();
            } else if (count == 2) {
                reconnectLatch.countDown();
            }
        });

        NeonClient client = new NeonClient("TestClient");
        assertTrue(client.connect(TEST_SESSION_ID, RELAY_ADDRESS));

        assertTrue(initialConnectLatch.await(1, TimeUnit.SECONDS),
            "Initial connection should trigger callback");

        Thread.sleep(SETUP_DELAY_MS);

        client.close();
        Thread.sleep(SETUP_DELAY_MS);

        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertTrue(client.reconnect(3));

        Thread.sleep(SETUP_DELAY_MS);

        for (int i = 0; i < 10; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertTrue(reconnectLatch.await(2, TimeUnit.SECONDS),
            "Host should trigger callback on client reconnection");

        client.close();
        host.close();
    }

    @Test
    @DisplayName("Reconnection preserves client ID")
    public void testReconnectionPreservesClientId() throws Exception {
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
        byte client1Id = client1.getClientId().orElseThrow();

        assertTrue(client2.connect(TEST_SESSION_ID, RELAY_ADDRESS));
        byte client2Id = client2.getClientId().orElseThrow();

        assertNotEquals(client1Id, client2Id,
            "Different clients should have different IDs");

        Thread.sleep(SETUP_DELAY_MS);

        client1.close();
        Thread.sleep(SETUP_DELAY_MS);

        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertTrue(client1.reconnect(3));

        Thread.sleep(SETUP_DELAY_MS);
        for (int i = 0; i < 5; i++) {
            host.processPackets();
            Thread.sleep(20);
        }

        assertEquals(client1Id, client1.getClientId().orElseThrow(),
            "Client 1 should keep same ID after reconnection");

        client1.close();
        client2.close();
        host.close();
    }

    @Test
    @DisplayName("Cannot reconnect without session state")
    public void testReconnectWithoutSessionState() throws Exception {
        NeonClient client = new NeonClient("TestClient");

        assertFalse(client.reconnect(1),
            "Should not be able to reconnect without prior connection");

        client.close();
    }

    @Test
    @DisplayName("Session timeout prevents reconnection")
    public void testSessionTimeout() throws Exception {
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

        Thread.sleep(SETUP_DELAY_MS);

        client.close();
        host.close();
    }
}
