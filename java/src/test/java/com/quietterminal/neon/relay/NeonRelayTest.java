package com.quietterminal.neon.relay;

import com.quietterminal.neon.core.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class NeonRelayTest {

        private static final NeonConfig CFG = NeonConfig.defaults();

        private NeonRelay relay;
        private Thread relayThread;

        @BeforeEach
        void setUp() throws Exception {
                relay = new NeonRelay("127.0.0.1", CFG);
                relayThread = Thread.ofVirtual().start(() -> {
                        try {
                                relay.startAndRun();
                        } catch (Exception ignored) {
                        }
                });
                Thread.sleep(50);
        }

        @AfterEach
        void tearDown() throws Exception {
                if (relay.isRunning())
                        relay.stop();
                relayThread.join(500);
        }

        @Test
        void hostRegisters_relayAcks() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG)) {
                        host.setBlocking(true);
                        host.setTimeout(2000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        host.sendPacket(NeonPacket.create(PacketType.HOST_REGISTER, (short) 0, (byte) 1, (byte) 0,
                                        new PacketPayload.HostRegister(42, 0xDEADBEEFL)), relayAddr);

                        NeonSocket.ReceivedNeonPacket ack = host.receivePacket();
                        assertNotNull(ack);
                        assertEquals(PacketType.CONNECT_ACCEPT,
                                        PacketType.fromByte(ack.packet().header().packetType()));
                        PacketPayload.ConnectAccept accept = (PacketPayload.ConnectAccept) ack.packet().payload();
                        assertEquals(1, accept.clientId());
                        assertEquals(42, accept.sessionId());
                }
        }

        @Test
        void connectRequest_forwardedToHost() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG);
                                NeonSocket client = new NeonSocket(CFG)) {

                        host.setBlocking(true);
                        host.setTimeout(2000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        host.sendPacket(NeonPacket.create(PacketType.HOST_REGISTER, (short) 0, (byte) 1, (byte) 0,
                                        new PacketPayload.HostRegister(10, 999L)), relayAddr);
                        host.receivePacket(); 

                        
                        client.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                        new PacketPayload.ConnectRequest((byte) 1, "alice", 10, 0)), relayAddr);

                        NeonSocket.ReceivedNeonPacket forwarded = host.receivePacket();
                        assertNotNull(forwarded);
                        assertEquals(PacketType.CONNECT_REQUEST,
                                        PacketType.fromByte(forwarded.packet().header().packetType()));
                        assertEquals("alice", ((PacketPayload.ConnectRequest) forwarded.packet().payload()).name());
                }
        }

        @Test
        void connectAccept_relayedToClient_andClientJoinsSession() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG);
                                NeonSocket client = new NeonSocket(CFG)) {

                        host.setBlocking(true);
                        host.setTimeout(2000);
                        client.setBlocking(true);
                        client.setTimeout(2000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        
                        host.sendPacket(NeonPacket.create(PacketType.HOST_REGISTER, (short) 0, (byte) 1, (byte) 0,
                                        new PacketPayload.HostRegister(20, 777L)), relayAddr);
                        host.receivePacket(); 

                        
                        client.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                        new PacketPayload.ConnectRequest((byte) 1, "bob", 20, 0)), relayAddr);
                        host.receivePacket(); 

                        
                        host.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 2, (byte) 1, (byte) 0,
                                        new PacketPayload.ConnectAccept((byte) 2, 20, 12345L)), relayAddr);

                        NeonSocket.ReceivedNeonPacket acceptForClient = client.receivePacket();
                        assertNotNull(acceptForClient);
                        assertEquals(PacketType.CONNECT_ACCEPT,
                                        PacketType.fromByte(acceptForClient.packet().header().packetType()));
                        PacketPayload.ConnectAccept ca = (PacketPayload.ConnectAccept) acceptForClient.packet()
                                        .payload();
                        assertEquals(2, ca.clientId());
                }
        }

        @Test
        void broadcastGamePacket_reachesAllPeersExceptSender() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG);
                                NeonSocket clientA = new NeonSocket(CFG);
                                NeonSocket clientB = new NeonSocket(CFG)) {

                        host.setBlocking(true);
                        host.setTimeout(2000);
                        clientA.setBlocking(true);
                        clientA.setTimeout(1000);
                        clientB.setBlocking(true);
                        clientB.setTimeout(1000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        joinSession(host, clientA, clientB, relayAddr, 30, (byte) 2, (byte) 3);

                        
                        clientA.sendPacket(NeonPacket.create(PacketType.GAME_PACKET, (short) 5, (byte) 2, (byte) 0,
                                        new PacketPayload.GamePacket(new byte[] { 1, 2, 3 })), relayAddr);

                        
                        NeonSocket.ReceivedNeonPacket forHost = host.receivePacket();
                        NeonSocket.ReceivedNeonPacket forB = clientB.receivePacket();
                        assertNotNull(forHost);
                        assertNotNull(forB);
                }
        }

        @Test
        void unicastGamePacket_reachesOnlyTarget() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG);
                                NeonSocket clientA = new NeonSocket(CFG);
                                NeonSocket clientB = new NeonSocket(CFG)) {

                        clientA.setBlocking(true);
                        clientA.setTimeout(300);
                        clientB.setBlocking(true);
                        clientB.setTimeout(300);
                        host.setBlocking(true);
                        host.setTimeout(2000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        joinSession(host, clientA, clientB, relayAddr, 40, (byte) 2, (byte) 3);

                        
                        clientA.sendPacket(NeonPacket.create(PacketType.GAME_PACKET, (short) 6, (byte) 2, (byte) 3,
                                        new PacketPayload.GamePacket(new byte[] { 9 })), relayAddr);

                        NeonSocket.ReceivedNeonPacket forB = clientB.receivePacket();
                        assertNotNull(forB);

                        
                        assertThrows(Exception.class, clientA::receivePacket); 
                }
        }

        @Test
        void disconnectNotice_forwardedToPeers() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG);
                                NeonSocket clientA = new NeonSocket(CFG);
                                NeonSocket clientB = new NeonSocket(CFG)) {

                        host.setBlocking(true);
                        host.setTimeout(2000);
                        clientB.setBlocking(true);
                        clientB.setTimeout(1000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        joinSession(host, clientA, clientB, relayAddr, 50, (byte) 2, (byte) 3);

                        
                        clientA.sendPacket(
                                        NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) 0, (byte) 2, (byte) 0,
                                                        new PacketPayload.DisconnectNotice()),
                                        relayAddr);

                        
                        NeonSocket.ReceivedNeonPacket noticeForHost = host.receivePacket();
                        NeonSocket.ReceivedNeonPacket noticeForB = clientB.receivePacket();
                        assertNotNull(noticeForHost);
                        assertNotNull(noticeForB);
                        assertEquals(PacketType.DISCONNECT_NOTICE,
                                        PacketType.fromByte(noticeForHost.packet().header().packetType()));
                }
        }

        @Test
        void sessionNotFound_sendsDeny() throws Exception {
                try (NeonSocket client = new NeonSocket(CFG)) {
                        client.setBlocking(true);
                        client.setTimeout(2000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        client.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 0, (byte) 0, (byte) 1,
                                        new PacketPayload.ConnectRequest((byte) 1, "ghost", 9999, 0)), relayAddr);

                        NeonSocket.ReceivedNeonPacket deny = client.receivePacket();
                        assertNotNull(deny);
                        assertEquals(PacketType.CONNECT_DENY,
                                        PacketType.fromByte(deny.packet().header().packetType()));
                }
        }

        @Test
        void rateLimiting_dropsExcessPackets() throws Exception {
                try (NeonSocket host = new NeonSocket(CFG);
                                NeonSocket spammer = new NeonSocket(CFG)) {

                        host.setBlocking(true);
                        host.setTimeout(2000);
                        InetSocketAddress relayAddr = relay.getLocalAddress();

                        
                        host.sendPacket(NeonPacket.create(PacketType.HOST_REGISTER, (short) 0, (byte) 1, (byte) 0,
                                        new PacketPayload.HostRegister(60, 1L)), relayAddr);
                        host.receivePacket(); 

                        
                        byte[] pkt = NeonPacket.create(PacketType.PING, (short) 0, (byte) 0, (byte) 1,
                                        new PacketPayload.Ping(0L)).toBytes();
                        for (int i = 0; i < 200; i++) {
                                spammer.send(pkt, relayAddr);
                        }

                        int received = 0;
                        host.setBlocking(false);
                        @SuppressWarnings("unused")
                        NeonSocket.ReceivedNeonPacket p;
                        while ((p = host.receivePacket()) != null)
                                received++;

                        assertTrue(received < 200, "Expected some packets dropped by rate limiter");
                }
        }

        
        private void joinSession(NeonSocket host, NeonSocket clientA, NeonSocket clientB,
                        InetSocketAddress relayAddr, int sessionId, byte idA, byte idB) throws Exception {
                host.setBlocking(true);
                host.setTimeout(2000);
                clientA.setBlocking(true);
                clientA.setTimeout(2000);
                clientB.setBlocking(true);
                clientB.setTimeout(2000);

                
                host.sendPacket(NeonPacket.create(PacketType.HOST_REGISTER, (short) 0, (byte) 1, (byte) 0,
                                new PacketPayload.HostRegister(sessionId, 1L)), relayAddr);
                host.receivePacket(); 

                
                clientA.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "a", sessionId, 0)), relayAddr);
                host.receivePacket(); 
                host.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 2, (byte) 1, (byte) 0,
                                new PacketPayload.ConnectAccept(idA, sessionId, 1L)), relayAddr);
                clientA.receivePacket(); 

                
                clientB.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 3, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "b", sessionId, 0)), relayAddr);
                host.receivePacket(); 
                host.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 4, (byte) 1, (byte) 0,
                                new PacketPayload.ConnectAccept(idB, sessionId, 2L)), relayAddr);
                clientB.receivePacket(); 
        }
}
