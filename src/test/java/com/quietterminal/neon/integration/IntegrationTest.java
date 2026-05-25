package com.quietterminal.neon.integration;

import com.quietterminal.neon.client.NeonClient;
import com.quietterminal.neon.core.*;
import com.quietterminal.neon.host.NeonHost;
import com.quietterminal.neon.relay.NeonRelay;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {

    private static final NeonConfig CFG = NeonConfig.defaults();
    private static final int SESSION_ID = 42;

    private NeonRelay relay;
    private NeonHost host;
    private Thread relayThread;
    private Thread hostThread;
    private String relayAddr;
    private final List<NeonClient> clients = new ArrayList<>();

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

        int port = relay.getLocalAddress().getPort();
        relayAddr = "127.0.0.1:" + port;

        host = new NeonHost(SESSION_ID, relayAddr, CFG);
        hostThread = Thread.ofVirtual().start(() -> {
            try {
                host.startAndRun();
            } catch (Exception ignored) {
            }
        });
        Thread.sleep(100);
        assertTrue(host.isRunning(), "Host must be running before tests");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (NeonClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        if (host.isRunning())
            host.stop();
        hostThread.join(1000);
        if (relay.isRunning())
            relay.stop();
        relayThread.join(500);
    }

    private NeonClient connect(String name) throws Exception {
        NeonClient client = new NeonClient(name, CFG);
        clients.add(client);
        boolean ok = client.connect(SESSION_ID, relayAddr);
        assertTrue(ok, name + " failed to connect");
        return client;
    }

    @Test
    void fullConnect_clientIsAssignedId() throws Exception {
        NeonClient client = connect("alice");

        assertTrue(client.isRunning());
        assertNotNull(client.getClientId());
        assertTrue((client.getClientId() & 0xFF) >= 2, "Client ID must be >= 2");
    }

    @Test
    void fullConnect_clientReceivesSessionConfig() throws Exception {
        NeonClient client = connect("bob");

        List<PacketPayload.SessionConfig> received = new ArrayList<>();
        client.setSessionConfigCallback(received::add);

        Thread.sleep(50);
        client.processPackets();

        assertFalse(received.isEmpty(), "CLIENT must receive SESSION_CONFIG forwarded through relay");
        assertEquals(CFG.getHostSessionTickRate(), received.get(0).tickRate());
    }

    @Test
    void gamePacket_fromClient_reachesHost() throws Exception {
        NeonClient client = connect("carol");

        List<Byte> received = new ArrayList<>();
        host.setUnhandledPacketCallback((type, id) -> received.add(id));

        client.sendPacket(new byte[] { 0x01, 0x02 }, (byte) 0x10, (byte) 1);

        Thread.sleep(100);
        assertFalse(received.isEmpty(), "Game packet from client must reach host through relay");
        assertEquals(client.getClientId(), received.get(0));
    }

    @Test
    void reconnect_afterDisconnect_succeeds() throws Exception {
        NeonClient client = connect("dave");

        client.stop();
        Thread.sleep(200);

        boolean[] result = { false };
        Thread t = Thread.ofVirtual().start(() -> result[0] = client.reconnect(1));
        t.join(3000);

        assertTrue(result[0], "Reconnect must succeed after clean disconnect");
    }
}
