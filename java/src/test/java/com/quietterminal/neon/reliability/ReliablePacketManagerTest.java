package com.quietterminal.neon.reliability;

import com.quietterminal.neon.core.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReliablePacketManagerTest {

    private static final NeonConfig CFG = NeonConfig.defaults();

    private NeonSocket senderSocket;
    private NeonSocket receiverSocket;
    private ReliablePacketManager manager;

    @BeforeEach
    void setUp() throws Exception {
        receiverSocket = new NeonSocket(0, CFG);
        receiverSocket.setBlocking(true);
        receiverSocket.setTimeout(1000);

        senderSocket = new NeonSocket(0, CFG);
        InetSocketAddress receiverAddr = new InetSocketAddress("127.0.0.1", receiverSocket.getLocalAddress().getPort());
        manager = new ReliablePacketManager(senderSocket, receiverAddr, (byte) 2, CFG);
    }

    @AfterEach
    void tearDown() throws Exception {
        senderSocket.close();
        receiverSocket.close();
    }

    @Test
    void sendReliable_ackedImmediately_noPendingRemains() throws Exception {
        NeonPacket packet = NeonPacket.create(PacketType.GAME_PACKET, (short) 1, (byte) 2, (byte) 0,
                new PacketPayload.GamePacket(new byte[] { 0x42 }));

        manager.sendReliable(packet);
        assertTrue(manager.hasPending());

        manager.acknowledge((short) 1);
        assertFalse(manager.hasPending());
    }

    @Test
    void retransmit_onTimeout_packetResent() throws Exception {
        NeonConfig shortTimeout = NeonConfig.builder().reliablePacketTimeoutMs(50).build();
        InetSocketAddress recv = new InetSocketAddress("127.0.0.1", receiverSocket.getLocalAddress().getPort());
        manager = new ReliablePacketManager(senderSocket, recv, (byte) 2, shortTimeout);

        NeonPacket packet = NeonPacket.create(PacketType.GAME_PACKET, (short) 7, (byte) 2, (byte) 0,
                new PacketPayload.GamePacket(new byte[] { 0x01 }));

        manager.sendReliable(packet);
        NeonSocket.ReceivedNeonPacket first = receiverSocket.receivePacket();
        assertNotNull(first, "Initial send not received");
        assertEquals((short) 7, first.packet().header().sequence());

        Thread.sleep(100);
        manager.processRetransmissions();

        NeonSocket.ReceivedNeonPacket retransmit = receiverSocket.receivePacket();
        assertNotNull(retransmit, "Retransmitted packet not received");
        assertEquals((short) 7, retransmit.packet().header().sequence());
        assertTrue(manager.hasPending(), "Packet should still be pending after one retry");
    }

    @Test
    void fail_afterMaxRetries_callbackFired() throws Exception {
        NeonConfig cfg = NeonConfig.builder().reliablePacketTimeoutMs(50).reliablePacketMaxRetries(1).build();
        List<Short> failed = new ArrayList<>();
        InetSocketAddress recv = new InetSocketAddress("127.0.0.1", receiverSocket.getLocalAddress().getPort());
        manager = new ReliablePacketManager(senderSocket, recv, (byte) 2, cfg);
        manager.setOnDeliveryFailed(failed::add);

        NeonPacket packet = NeonPacket.create(PacketType.GAME_PACKET, (short) 3, (byte) 2, (byte) 0,
                new PacketPayload.GamePacket(new byte[] { 0x02 }));

        manager.sendReliable(packet);
        receiverSocket.receivePacket();

        Thread.sleep(100);
        manager.processRetransmissions();
        assertTrue(failed.isEmpty(), "No failure yet after first retry");
        receiverSocket.receivePacket();

        Thread.sleep(100);
        manager.processRetransmissions();
        assertEquals(List.of((short) 3), failed);
        assertFalse(manager.hasPending(), "Packet must be removed after permanent failure");
    }

    @Test
    void duplicateDetection_wrapAround() throws Exception {
        byte sender = (byte) 5;

        assertFalse(manager.isDuplicate(sender, (short) 65534), "65534 should not be dup (first seen)");
        assertTrue(manager.isDuplicate(sender, (short) 65534), "65534 again should be dup");
        assertFalse(manager.isDuplicate(sender, (short) 65535), "65535 should not be dup");
        assertFalse(manager.isDuplicate(sender, (short) 0), "0 after 65535 should not be dup (wrap)");
        assertFalse(manager.isDuplicate(sender, (short) 1), "1 after 0 should not be dup");
        assertTrue(manager.isDuplicate(sender, (short) 0), "0 again should be dup (less than last=1)");
    }
}
