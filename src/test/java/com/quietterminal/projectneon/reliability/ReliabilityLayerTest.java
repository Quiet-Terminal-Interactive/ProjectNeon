package com.quietterminal.projectneon.reliability;

import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.host.NeonHost;
import com.quietterminal.projectneon.relay.NeonRelay;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for optional reliability layer (section 4.3 of roadmap).
 */
public class ReliabilityLayerTest {
    private static final int TEST_SESSION_ID = 12347;
    private static final String RELAY_ADDRESS = "localhost:17780";
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
    @DisplayName("ReliablePacketManager sends packets")
    public void testSendReliablePacket() throws Exception {
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

        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, clientId);

        byte[] testData = "test payload".getBytes();
        short sequence = reliableManager.sendReliable(testData, (byte) 1);

        assertTrue(sequence >= 0, "Should return valid sequence number");
        assertEquals(1, reliableManager.getPendingCount(),
            "Should have one pending packet");

        socket.close();
        client.close();
        host.close();
    }

    @Test
    @DisplayName("ReliablePacketManager handles ACKs")
    public void testHandleAck() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        byte[] testData = "test".getBytes();
        short seq1 = reliableManager.sendReliable(testData, (byte) 1);
        short seq2 = reliableManager.sendReliable(testData, (byte) 1);

        assertEquals(2, reliableManager.getPendingCount());

        reliableManager.handleAck(List.of(seq1));

        assertEquals(1, reliableManager.getPendingCount(),
            "Should have one pending packet after ACK");

        reliableManager.handleAck(List.of(seq2));

        assertEquals(0, reliableManager.getPendingCount(),
            "Should have no pending packets after all ACKs");

        socket.close();
    }

    @Test
    @DisplayName("ReliablePacketManager retransmits unacknowledged packets")
    public void testRetransmission() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        reliableManager.setTimeout(500);
        reliableManager.setMaxRetries(3);

        byte[] testData = "test".getBytes();
        reliableManager.sendReliable(testData, (byte) 1);

        assertEquals(1, reliableManager.getPendingCount());

        Thread.sleep(600);

        reliableManager.processRetransmissions();

        assertEquals(1, reliableManager.getPendingCount(),
            "Packet should still be pending after first retry");

        socket.close();
    }

    @Test
    @DisplayName("ReliablePacketManager gives up after max retries")
    public void testMaxRetries() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        reliableManager.setTimeout(100);
        reliableManager.setMaxRetries(2);

        byte[] testData = "test".getBytes();
        reliableManager.sendReliable(testData, (byte) 1);

        for (int i = 0; i < 3; i++) {
            Thread.sleep(150);
            reliableManager.processRetransmissions();
        }

        assertEquals(0, reliableManager.getPendingCount(),
            "Should give up after max retries");

        socket.close();
    }

    @Test
    @DisplayName("ReliablePacketManager detects duplicates")
    public void testDuplicateDetection() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        byte fromClient = (byte) 3;
        short sequence = 100;

        boolean isDuplicate1 = reliableManager.handleReceivedReliable(fromClient, sequence);
        assertFalse(isDuplicate1, "First packet should not be duplicate");

        boolean isDuplicate2 = reliableManager.handleReceivedReliable(fromClient, sequence);
        assertTrue(isDuplicate2, "Same sequence should be detected as duplicate");

        boolean isDuplicate3 = reliableManager.handleReceivedReliable(fromClient, (short) 99);
        assertTrue(isDuplicate3, "Lower sequence should be detected as duplicate");

        boolean isDuplicate4 = reliableManager.handleReceivedReliable(fromClient, (short) 101);
        assertFalse(isDuplicate4, "Higher sequence should not be duplicate");

        socket.close();
    }

    @Test
    @DisplayName("ReliablePacketManager configurable timeout")
    public void testConfigurableTimeout() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        reliableManager.setTimeout(200);
        reliableManager.setMaxRetries(1);

        byte[] testData = "test".getBytes();
        reliableManager.sendReliable(testData, (byte) 1);

        Thread.sleep(150);
        reliableManager.processRetransmissions();
        assertEquals(1, reliableManager.getPendingCount(),
            "Should not retry before timeout");

        Thread.sleep(100);
        reliableManager.processRetransmissions();
        assertEquals(1, reliableManager.getPendingCount(),
            "Should retry after timeout");

        socket.close();
    }

    @Test
    @DisplayName("ReliablePacketManager multiple sequences")
    public void testMultipleSequences() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        byte[] testData = "test".getBytes();
        List<Short> sequences = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            sequences.add(reliableManager.sendReliable(testData, (byte) 1));
        }

        assertEquals(5, reliableManager.getPendingCount());

        reliableManager.handleAck(sequences.subList(0, 3));

        assertEquals(2, reliableManager.getPendingCount(),
            "Should have 2 pending after ACKing 3");

        socket.close();
    }

    @Test
    @DisplayName("ReliablePacketManager per-client sequence tracking")
    public void testPerClientSequenceTracking() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        byte client1 = (byte) 3;
        byte client2 = (byte) 4;

        assertFalse(reliableManager.handleReceivedReliable(client1, (short) 100));
        assertFalse(reliableManager.handleReceivedReliable(client2, (short) 100));

        assertTrue(reliableManager.handleReceivedReliable(client1, (short) 100),
            "Client 1 duplicate should be detected");
        assertTrue(reliableManager.handleReceivedReliable(client2, (short) 100),
            "Client 2 duplicate should be detected");

        assertFalse(reliableManager.handleReceivedReliable(client1, (short) 101),
            "Client 1 new sequence should not be duplicate");

        socket.close();
    }

    @Test
    @DisplayName("Integration: Reliable delivery end-to-end")
    public void testReliableDeliveryEndToEnd() throws Exception {
        NeonSocket socket = new NeonSocket();
        socket.setBlocking(true);
        InetSocketAddress relayAddr = new InetSocketAddress("localhost", 17780);
        ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr, (byte) 2);

        byte[] testData = "test".getBytes();
        short seq1 = reliableManager.sendReliable(testData, (byte) 1);
        short seq2 = reliableManager.sendReliable(testData, (byte) 1);

        assertEquals(2, reliableManager.getPendingCount(),
            "Should have 2 pending packets");

        reliableManager.handleAck(List.of(seq1));

        assertEquals(1, reliableManager.getPendingCount(),
            "Should have 1 pending packet after first ACK");

        reliableManager.handleAck(List.of(seq2));

        assertEquals(0, reliableManager.getPendingCount(),
            "Should receive ACK for reliable packet");

        socket.close();
    }
}
