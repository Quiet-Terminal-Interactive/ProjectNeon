package com.quietterminal.neon.client;

import com.quietterminal.neon.core.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NeonClientTest {

    private static final NeonConfig CFG = NeonConfig.defaults();

    private NeonSocket mockRelay;
    private NeonClient client;
    private InetSocketAddress clientAddr;

    private void connectClient(String name) throws Exception {
        mockRelay = new NeonSocket(0, CFG);
        mockRelay.setBlocking(true);
        mockRelay.setTimeout(2000);
        int relayPort = mockRelay.getLocalAddress().getPort();

        client = new NeonClient(name, CFG);
        Thread connectThread = Thread.ofVirtual().start(() -> client.connect(1, "127.0.0.1:" + relayPort));

        NeonSocket.ReceivedNeonPacket req = mockRelay.receivePacket();
        assertNotNull(req, "CONNECT_REQUEST not received");
        assertEquals(PacketType.CONNECT_REQUEST, PacketType.fromByte(req.packet().header().packetType()));
        clientAddr = (InetSocketAddress) req.source();

        mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 0, (byte) 0, (byte) 2,
                new PacketPayload.ConnectAccept((byte) 2, 1, 12345L)), clientAddr);

        connectThread.join(1000);
        assertTrue(client.isRunning(), "Client did not reach RUNNING state");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        if (mockRelay != null)
            mockRelay.close();
    }

    @Test
    void connect_sendsConnectRequest_receivesAccept() throws Exception {
        connectClient("alice");
        assertEquals((byte) 2, (byte) client.getClientId());
    }

    @Test
    void connect_denied_returnsFalse() throws Exception {
        mockRelay = new NeonSocket(0, CFG);
        mockRelay.setBlocking(true);
        mockRelay.setTimeout(2000);
        int relayPort = mockRelay.getLocalAddress().getPort();

        client = new NeonClient("denied", CFG);
        boolean[] result = { true };
        Thread t = Thread.ofVirtual().start(() -> result[0] = client.connect(1, "127.0.0.1:" + relayPort));

        NeonSocket.ReceivedNeonPacket req = mockRelay.receivePacket();
        assertNotNull(req);
        mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_DENY, (short) 0, (byte) 0, (byte) 0,
                new PacketPayload.ConnectDeny("Full")), (InetSocketAddress) req.source());

        t.join(2000);
        assertFalse(result[0]);
    }

    @Test
    void sessionConfig_acksBack() throws Exception {
        connectClient("bob");

        List<PacketPayload.SessionConfig> received = new ArrayList<>();
        client.setSessionConfigCallback(received::add);

        mockRelay.sendPacket(NeonPacket.create(PacketType.SESSION_CONFIG, (short) 5, (byte) 1, (byte) 2,
                new PacketPayload.SessionConfig(PacketHeader.VERSION, (short) 60, (short) 1200)), clientAddr);

        client.processPackets();

        assertEquals(1, received.size());
        assertEquals((short) 60, received.get(0).tickRate());

        NeonSocket.ReceivedNeonPacket ack = mockRelay.receivePacket();
        assertNotNull(ack);
        assertEquals(PacketType.ACK, PacketType.fromByte(ack.packet().header().packetType()));
        List<Short> seqs = ((PacketPayload.Ack) ack.packet().payload()).sequences();
        assertEquals(List.of((short) 5), seqs);
    }

    @Test
    void wrongDestinationPacket_isIgnored() throws Exception {
        connectClient("carol");

        List<Byte> unhandled = new ArrayList<>();
        client.setUnhandledPacketCallback((type, id) -> unhandled.add(id));

        mockRelay.sendPacket(NeonPacket.create(PacketType.GAME_PACKET, (short) 1, (byte) 1, (byte) 3,
                new PacketPayload.GamePacket(new byte[] { 9 })), clientAddr);

        client.processPackets();
        assertTrue(unhandled.isEmpty(), "Wrong-destination packet must be silently ignored");
    }

    @Test
    void disconnect_notice_firesCallback() throws Exception {
        connectClient("dave");

        List<Byte> disconnected = new ArrayList<>();
        client.setDisconnectCallback(disconnected::add);

        mockRelay.sendPacket(NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) 1, (byte) 3, (byte) 0,
                new PacketPayload.DisconnectNotice()), clientAddr);

        client.processPackets();
        assertEquals(1, disconnected.size());
        assertEquals((byte) 3, (byte) disconnected.get(0));
    }

    @Test
    void reconnect_withOpenSocket_succeeds() throws Exception {
        connectClient("reconnector");

        boolean[] result = { false };
        Thread t = Thread.ofVirtual().start(() -> result[0] = client.reconnect());

        NeonSocket.ReceivedNeonPacket req = mockRelay.receivePacket();
        assertNotNull(req);
        assertEquals(PacketType.RECONNECT_REQUEST, PacketType.fromByte(req.packet().header().packetType()));

        mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 0, (byte) 0, (byte) 2,
                new PacketPayload.ConnectAccept((byte) 2, 1, 99999L)), clientAddr);

        t.join(2000);
        assertTrue(result[0]);
    }

    @Test
    void reconnect_withClosedSocket_createsNewSocket() throws Exception {
        connectClient("reconnector2");

        client.getLocalAddress();
        client.stop();

        mockRelay.receivePacket();

        boolean[] result = { false };
        Thread t = Thread.ofVirtual().start(() -> result[0] = client.reconnect());

        NeonSocket.ReceivedNeonPacket req = mockRelay.receivePacket();
        assertNotNull(req, "Expected RECONNECT_REQUEST after socket recreation");
        assertEquals(PacketType.RECONNECT_REQUEST, PacketType.fromByte(req.packet().header().packetType()));

        InetSocketAddress newClientAddr = (InetSocketAddress) req.source();
        mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 0, (byte) 0, (byte) 2,
                new PacketPayload.ConnectAccept((byte) 2, 1, 77777L)), newClientAddr);

        t.join(2000);
        assertTrue(result[0]);
    }

    @Test
    void autoPing_firesAfterInterval() throws Exception {
        NeonConfig shortPingCfg = NeonConfig.builder().clientPingIntervalMs(50).build();

        mockRelay = new NeonSocket(0, shortPingCfg);
        mockRelay.setBlocking(true);
        mockRelay.setTimeout(2000);
        int relayPort = mockRelay.getLocalAddress().getPort();

        client = new NeonClient("pinger", shortPingCfg);
        Thread connectThread = Thread.ofVirtual().start(() -> client.connect(1, "127.0.0.1:" + relayPort));

        NeonSocket.ReceivedNeonPacket req = mockRelay.receivePacket();
        clientAddr = (InetSocketAddress) req.source();
        mockRelay.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 0, (byte) 0, (byte) 2,
                new PacketPayload.ConnectAccept((byte) 2, 1, 1L)), clientAddr);
        connectThread.join(1000);

        Thread runThread = Thread.ofVirtual().start(client::run);

        Thread.sleep(200);
        client.stop();
        runThread.join(500);

        mockRelay.setBlocking(false);
        boolean sawPing = false;
        NeonSocket.ReceivedNeonPacket p;
        while ((p = mockRelay.receivePacket()) != null) {
            if (PacketType.PING == PacketType.fromByte(p.packet().header().packetType())) {
                sawPing = true;
            }
        }
        assertTrue(sawPing, "Expected at least one auto-ping from client");
    }
}
