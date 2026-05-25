package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NeonSocketTest {

    private static final NeonConfig CFG = NeonConfig.defaults();

    @Test
    void nonBlockingReturnsNullWhenEmpty() throws Exception {
        try (NeonSocket socket = new NeonSocket(CFG)) {
            assertNull(socket.receive());
        }
    }

    @Test
    void sendAndReceiveNeonPacket() throws Exception {
        try (NeonSocket sender = new NeonSocket(CFG);
                NeonSocket receiver = new NeonSocket(CFG)) {

            receiver.setBlocking(true);
            receiver.setTimeout(2000);

            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", receiver.getLocalAddress().getPort());

            var payload = new PacketPayload.Ping(99L);
            var packet = NeonPacket.create(PacketType.PING, (short) 1, (byte) 1, (byte) 0, payload);
            sender.sendPacket(packet, dest);

            NeonSocket.ReceivedNeonPacket received = receiver.receivePacket();

            assertNotNull(received);
            assertEquals(new PacketPayload.Ping(99L), received.packet().payload());
            assertEquals(PacketType.PING, PacketType.fromByte(received.packet().header().packetType()));
        }
    }

    @Test
    void sendAndReceiveRawBytes() throws Exception {
        try (NeonSocket sender = new NeonSocket(CFG);
                NeonSocket receiver = new NeonSocket(CFG)) {

            receiver.setBlocking(true);
            receiver.setTimeout(2000);

            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", receiver.getLocalAddress().getPort());

            var payload = new PacketPayload.Ack(List.of((short) 10, (short) 20));
            var packet = NeonPacket.create(PacketType.ACK, (short) 5, (byte) 2, (byte) 1, payload);
            byte[] sent = packet.toBytes();
            sender.send(sent, dest);

            Transport.ReceivedData data = receiver.receive();
            assertNotNull(data);
            assertArrayEquals(sent, data.data());
        }
    }

    @Test
    void blockingTimesOut() throws Exception {
        try (NeonSocket socket = new NeonSocket(CFG)) {
            socket.setBlocking(true);
            socket.setTimeout(50);
            assertThrows(SocketTimeoutException.class, socket::receive);
        }
    }

    @Test
    void isClosed_afterClose() throws Exception {
        NeonSocket socket = new NeonSocket(CFG);
        assertFalse(socket.isClosed());
        socket.close();
        assertTrue(socket.isClosed());
    }

    @Test
    void getLocalAddressReturnsValidPort() throws Exception {
        try (NeonSocket socket = new NeonSocket(CFG)) {
            InetSocketAddress addr = socket.getLocalAddress();
            assertNotNull(addr);
            assertTrue(addr.getPort() > 0);
        }
    }

    @Test
    void malformedPacketIsDiscarded() throws Exception {
        try (NeonSocket sender = new NeonSocket(CFG);
                NeonSocket receiver = new NeonSocket(CFG)) {

            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", receiver.getLocalAddress().getPort());
            sender.send(new byte[8], dest); // 8 bytes, wrong magic

            receiver.setBlocking(true);
            receiver.setTimeout(500);
            Transport.ReceivedData raw = receiver.receive();

            if (raw != null) {
                final byte[] rawBytes = raw.data();
                assertThrows(IllegalArgumentException.class, () -> NeonPacket.fromBytes(rawBytes));
            }
        }
    }

    @Test
    void specificPortBinding() throws Exception {
        try (NeonSocket socket = new NeonSocket(0, CFG)) {
            assertTrue(socket.getLocalAddress().getPort() > 0);
        }
    }
}
