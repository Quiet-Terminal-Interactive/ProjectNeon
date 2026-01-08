package com.quietterminal.projectneon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for NeonSocket.
 * Note: Network tests may be flaky due to UDP's unreliable nature and timing issues.
 */
class NeonSocketTest {

    private NeonSocket socket;
    private final List<NeonSocket> socketsToCleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        if (socket != null) {
            socket.close();
        }
        for (NeonSocket s : socketsToCleanup) {
            try {
                s.close();
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
        socketsToCleanup.clear();
    }

    private NeonSocket createSocket() throws IOException {
        NeonSocket s = new NeonSocket();
        socketsToCleanup.add(s);
        return s;
    }

    private NeonSocket createSocket(int port) throws IOException {
        NeonSocket s = new NeonSocket(port);
        socketsToCleanup.add(s);
        return s;
    }

    @Test
    @DisplayName("Should create socket with any available port")
    void testDefaultConstructor() throws IOException {
        socket = createSocket();
        assertNotNull(socket);
        assertNotNull(socket.getLocalAddress());
        assertTrue(socket.getLocalAddress().getPort() > 0);
    }

    @Test
    @DisplayName("Should create socket bound to specific port")
    void testConstructorWithPort() throws IOException {
        int port = 0;
        socket = createSocket(port);
        assertNotNull(socket);
        assertNotNull(socket.getLocalAddress());
    }

    @Test
    @DisplayName("Should default to non-blocking mode")
    void testDefaultNonBlockingMode() throws IOException {
        socket = createSocket();
        assertFalse(socket.isBlocking());
    }

    @Test
    @DisplayName("Should switch to blocking mode")
    void testSetBlockingTrue() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);
        assertTrue(socket.isBlocking());
    }

    @Test
    @DisplayName("Should switch to non-blocking mode")
    void testSetBlockingFalse() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);
        socket.setBlocking(false);
        assertFalse(socket.isBlocking());
    }

    @Test
    @DisplayName("Should get local address")
    void testGetLocalAddress() throws IOException {
        socket = createSocket();
        InetSocketAddress address = socket.getLocalAddress();
        assertNotNull(address);
        assertNotNull(address.getAddress());
        assertTrue(address.getPort() > 0);
    }

    @Test
    @DisplayName("Should set socket timeout")
    void testSetSoTimeout() throws IOException {
        socket = createSocket();
        assertDoesNotThrow(() -> socket.setSoTimeout(5000));
    }

    @Test
    @DisplayName("Should timeout in blocking mode with timeout set")
    void testReceiveTimeout() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);
        socket.setSoTimeout(100);

        assertThrows(SocketTimeoutException.class, () -> socket.receive());
    }

    @Test
    @DisplayName("Should close socket successfully")
    void testClose() throws IOException {
        socket = createSocket();
        InetSocketAddress addressBeforeClose = socket.getLocalAddress();
        assertNotNull(addressBeforeClose);

        assertDoesNotThrow(() -> socket.close());
    }

    @Test
    @DisplayName("Should handle connection lifecycle")
    void testConnectionLifecycle() throws IOException {
        NeonSocket socket1 = createSocket();
        assertTrue(socket1.getLocalAddress().getPort() > 0);

        NeonSocket socket2 = createSocket();
        assertTrue(socket2.getLocalAddress().getPort() > 0);

        assertNotEquals(socket1.getLocalAddress().getPort(), socket2.getLocalAddress().getPort());
    }

    @Test
    @DisplayName("Should handle invalid address gracefully")
    void testInvalidAddressHandling() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);
        byte[] data = "test".getBytes();

        SocketAddress unreachableAddress = new InetSocketAddress("127.0.0.1", 65000);
        assertDoesNotThrow(() -> socket.sendTo(data, unreachableAddress));
    }

    @Test
    @DisplayName("Should serialize and send NeonPacket")
    void testPacketSerialization() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);

        PacketPayload.Ping payload = new PacketPayload.Ping(System.currentTimeMillis());
        NeonPacket packet = NeonPacket.create(
            PacketType.PING,
            (short) 1,
            (byte) 0,
            (byte) 0,
            payload
        );

        SocketAddress destination = new InetSocketAddress("127.0.0.1", 50000);
        assertDoesNotThrow(() -> socket.sendPacket(packet, destination));
    }

    @Test
    @DisplayName("Should handle concurrent socket creation")
    void testConcurrentSocketCreation() throws IOException {
        List<NeonSocket> sockets = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            NeonSocket s = createSocket();
            sockets.add(s);
            assertNotNull(s.getLocalAddress());
            assertTrue(s.getLocalAddress().getPort() > 0);
        }

        long uniquePorts = sockets.stream()
            .map(s -> s.getLocalAddress().getPort())
            .distinct()
            .count();
        assertEquals(10, uniquePorts);
    }

    @Test
    @DisplayName("Should handle ReceivedPacket record creation")
    void testReceivedPacketRecord() {
        byte[] data = "test".getBytes();
        SocketAddress source = new InetSocketAddress("127.0.0.1", 12345);

        NeonSocket.ReceivedPacket packet = new NeonSocket.ReceivedPacket(data, source);

        assertNotNull(packet);
        assertArrayEquals(data, packet.data());
        assertEquals(source, packet.source());
    }

    @Test
    @DisplayName("Should handle ReceivedNeonPacket record creation")
    void testReceivedNeonPacketRecord() {
        PacketPayload.Ping payload = new PacketPayload.Ping(123L);
        NeonPacket neonPacket = NeonPacket.create(
            PacketType.PING,
            (short) 1,
            (byte) 0,
            (byte) 0,
            payload
        );
        SocketAddress source = new InetSocketAddress("127.0.0.1", 12345);

        NeonSocket.ReceivedNeonPacket received = new NeonSocket.ReceivedNeonPacket(neonPacket, source);

        assertNotNull(received);
        assertEquals(neonPacket, received.packet());
        assertEquals(source, received.source());
    }

    @Test
    @DisplayName("Should create packet with all supported packet types")
    void testAllPacketTypes() {
        PacketType[] types = {
            PacketType.PING,
            PacketType.PONG,
            PacketType.DISCONNECT_NOTICE,
            PacketType.GAME_PACKET
        };

        for (PacketType type : types) {
            PacketPayload payload = switch (type) {
                case PING -> new PacketPayload.Ping(123L);
                case PONG -> new PacketPayload.Pong(456L);
                case DISCONNECT_NOTICE -> new PacketPayload.DisconnectNotice();
                case GAME_PACKET -> new PacketPayload.GamePacket(new byte[]{1, 2, 3});
                default -> throw new IllegalArgumentException("Unsupported type in test");
            };

            NeonPacket packet = NeonPacket.create(
                type,
                (short) 1,
                (byte) 0,
                (byte) 0,
                payload
            );

            assertNotNull(packet);
            assertNotNull(packet.toBytes());
            assertTrue(packet.toBytes().length > 0);
        }
    }

    @Test
    @DisplayName("Should handle large packet payloads")
    void testLargePacketPayload() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);

        byte[] largePayload = new byte[1400];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }

        NeonPacket packet = NeonPacket.create(
            PacketType.GAME_PACKET,
            (short) 1,
            (byte) 0,
            (byte) 0,
            new PacketPayload.GamePacket(largePayload)
        );

        byte[] serialized = packet.toBytes();
        assertTrue(serialized.length > largePayload.length);

        SocketAddress destination = new InetSocketAddress("127.0.0.1", 50000);
        assertDoesNotThrow(() -> socket.sendPacket(packet, destination));
    }

    @Test
    @DisplayName("Should handle zero-length payloads")
    void testZeroLengthPayload() throws IOException {
        socket = createSocket();
        socket.setBlocking(true);

        NeonPacket packet = NeonPacket.create(
            PacketType.GAME_PACKET,
            (short) 1,
            (byte) 0,
            (byte) 0,
            new PacketPayload.GamePacket(new byte[0])
        );

        byte[] serialized = packet.toBytes();
        assertEquals(PacketHeader.HEADER_SIZE, serialized.length);

        SocketAddress destination = new InetSocketAddress("127.0.0.1", 50000);
        assertDoesNotThrow(() -> socket.sendPacket(packet, destination));
    }
}
