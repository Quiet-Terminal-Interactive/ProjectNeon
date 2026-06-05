package com.quietterminal.neon.host;

import com.quietterminal.neon.core.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NeonHostTest {

        private static final NeonConfig CFG = NeonConfig.defaults();

        private NeonSocket mockRelay;
        private NeonHost host;
        private Thread hostThread;
        private InetSocketAddress hostAddr;

        @BeforeEach
        void setUp() throws Exception {
                mockRelay = new NeonSocket(0, CFG);
                mockRelay.setBlocking(true);
                mockRelay.setTimeout(3000);
                int relayPort = mockRelay.getLocalAddress().getPort();

                host = new NeonHost(42, "127.0.0.1:" + relayPort, CFG);
                hostThread = Thread.ofVirtual().start(() -> {
                        try {
                                host.startAndRun();
                        } catch (Exception ignored) {
                        }
                });

                NeonSocket.ReceivedNeonPacket reg = mockRelay.receivePacket();
                assertNotNull(reg, "HOST_REGISTER not received");
                assertEquals(PacketType.HOST_REGISTER, PacketType.fromByte(reg.packet().header().packetType()));
                hostAddr = (InetSocketAddress) reg.source();
                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 0, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectAccept((byte) 1, 42, 99L)), hostAddr);

                Thread.sleep(100);
                assertTrue(host.isRunning(), "Host did not reach RUNNING state");
        }

        @AfterEach
        void tearDown() throws Exception {
                if (host.isRunning())
                        host.stop();
                hostThread.join(1000);
                mockRelay.close();
        }

        @Test
        void hostRegisters_isRunning() {
                assertTrue(host.isRunning());
        }

        @Test
        void acceptsClient_sendsAcceptConfigAndRegistry() throws Exception {
                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "alice", 42, 0)), hostAddr);

                NeonSocket.ReceivedNeonPacket acceptPkt = mockRelay.receivePacket();
                assertNotNull(acceptPkt);
                assertEquals(PacketType.CONNECT_ACCEPT, PacketType.fromByte(acceptPkt.packet().header().packetType()));
                PacketPayload.ConnectAccept ca = (PacketPayload.ConnectAccept) acceptPkt.packet().payload();
                assertEquals(42, ca.sessionId());
                assertTrue((ca.clientId() & 0xFF) >= 2, "Assigned clientId should be >= 2");

                NeonSocket.ReceivedNeonPacket cfgPkt = mockRelay.receivePacket();
                assertNotNull(cfgPkt);
                assertEquals(PacketType.SESSION_CONFIG, PacketType.fromByte(cfgPkt.packet().header().packetType()));
                PacketPayload.SessionConfig sc = (PacketPayload.SessionConfig) cfgPkt.packet().payload();
                assertEquals(CFG.getHostSessionTickRate(), sc.tickRate());
                assertEquals(CFG.getHostSessionMaxPacketSize(), sc.maxPacketSize());

                NeonSocket.ReceivedNeonPacket regPkt = mockRelay.receivePacket();
                assertNotNull(regPkt);
                assertEquals(PacketType.PACKET_TYPE_REGISTRY,
                                PacketType.fromByte(regPkt.packet().header().packetType()));
        }

        @Test
        void rejectsDuplicateName() throws Exception {
                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "alice", 42, 0)), hostAddr);
                mockRelay.receivePacket(); // CONNECT_ACCEPT
                mockRelay.receivePacket(); // SESSION_CONFIG
                mockRelay.receivePacket(); // PACKET_TYPE_REGISTRY

                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 2, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "alice", 42, 0)), hostAddr);

                NeonSocket.ReceivedNeonPacket deny = mockRelay.receivePacket();
                assertNotNull(deny);
                assertEquals(PacketType.CONNECT_DENY, PacketType.fromByte(deny.packet().header().packetType()));
                String reason = ((PacketPayload.ConnectDeny) deny.packet().payload()).reason();
                assertTrue(reason.contains("Name taken"), "Expected 'Name taken' but got: " + reason);
        }

        @Test
        void firesClientConnectCallback() throws Exception {
                List<String> connected = new ArrayList<>();
                host.setClientConnectCallback((id, name, sid) -> connected.add(name));

                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "bob", 42, 0)), hostAddr);
                mockRelay.receivePacket(); // CONNECT_ACCEPT
                mockRelay.receivePacket(); // SESSION_CONFIG
                mockRelay.receivePacket(); // PACKET_TYPE_REGISTRY

                Thread.sleep(50);
                assertEquals(List.of("bob"), connected);
        }

        @Test
        void respondsToPing_withPong() throws Exception {
                mockRelay.sendPacket(NeonPacket.create(PacketType.PING, (short) 1, (byte) 2, (byte) 1,
                                new PacketPayload.Ping(99999L)), hostAddr);

                NeonSocket.ReceivedNeonPacket pong = mockRelay.receivePacket();
                assertNotNull(pong);
                assertEquals(PacketType.PONG, PacketType.fromByte(pong.packet().header().packetType()));
                assertEquals(99999L, ((PacketPayload.Pong) pong.packet().payload()).originalTimestamp());
        }

        @Test
        void disconnectNotice_firesCallback_storesForReconnect() throws Exception {
                List<Byte> disconnected = new ArrayList<>();
                host.setClientDisconnectCallback(disconnected::add);

                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "carol", 42, 0)), hostAddr);
                NeonSocket.ReceivedNeonPacket acceptPkt = mockRelay.receivePacket();
                byte clientId = ((PacketPayload.ConnectAccept) acceptPkt.packet().payload()).clientId();
                mockRelay.receivePacket(); // SESSION_CONFIG
                mockRelay.receivePacket(); // PACKET_TYPE_REGISTRY

                mockRelay.sendPacket(NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) 5, clientId, (byte) 1,
                                new PacketPayload.DisconnectNotice()), hostAddr);

                Thread.sleep(100);
                assertEquals(1, disconnected.size());
                assertEquals(clientId, (byte) disconnected.get(0));
        }

        @Test
        void reconnect_withValidToken_succeeds() throws Exception {
                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "dave", 42, 0)), hostAddr);
                NeonSocket.ReceivedNeonPacket acceptPkt = mockRelay.receivePacket();
                byte clientId = ((PacketPayload.ConnectAccept) acceptPkt.packet().payload()).clientId();
                long token = ((PacketPayload.ConnectAccept) acceptPkt.packet().payload()).token();
                mockRelay.receivePacket(); // SESSION_CONFIG
                mockRelay.receivePacket(); // PACKET_TYPE_REGISTRY

                mockRelay.sendPacket(NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) 5, clientId, (byte) 1,
                                new PacketPayload.DisconnectNotice()), hostAddr);
                Thread.sleep(100);

                mockRelay.sendPacket(NeonPacket.create(PacketType.RECONNECT_REQUEST, (short) 6, clientId, (byte) 1,
                                new PacketPayload.ReconnectRequest(token, 42, clientId)), hostAddr);

                NeonSocket.ReceivedNeonPacket reAccept = mockRelay.receivePacket();
                assertNotNull(reAccept);
                assertEquals(PacketType.CONNECT_ACCEPT, PacketType.fromByte(reAccept.packet().header().packetType()));
                PacketPayload.ConnectAccept ca = (PacketPayload.ConnectAccept) reAccept.packet().payload();
                assertEquals(clientId, ca.clientId());
                assertEquals(42, ca.sessionId());
        }

        @Test
        void reconnect_withWrongToken_sendsDeny() throws Exception {
                mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1,
                                new PacketPayload.ConnectRequest((byte) 1, "eve", 42, 0)), hostAddr);
                NeonSocket.ReceivedNeonPacket acceptPkt = mockRelay.receivePacket();
                byte clientId = ((PacketPayload.ConnectAccept) acceptPkt.packet().payload()).clientId();
                mockRelay.receivePacket(); // SESSION_CONFIG
                mockRelay.receivePacket(); // PACKET_TYPE_REGISTRY

                mockRelay.sendPacket(NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) 5, clientId, (byte) 1,
                                new PacketPayload.DisconnectNotice()), hostAddr);
                Thread.sleep(100);

                mockRelay.sendPacket(NeonPacket.create(PacketType.RECONNECT_REQUEST, (short) 6, clientId, (byte) 1,
                                new PacketPayload.ReconnectRequest(0xDEADL, 42, clientId)), hostAddr);

                NeonSocket.ReceivedNeonPacket deny = mockRelay.receivePacket();
                assertNotNull(deny);
                assertEquals(PacketType.CONNECT_DENY, PacketType.fromByte(deny.packet().header().packetType()));
        }
}
