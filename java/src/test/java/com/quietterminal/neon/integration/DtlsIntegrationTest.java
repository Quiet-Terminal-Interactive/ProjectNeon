package com.quietterminal.neon.integration;

import com.quietterminal.neon.client.NeonClient;
import com.quietterminal.neon.core.DtlsConfig;
import com.quietterminal.neon.core.NeonConfig;
import com.quietterminal.neon.host.NeonHost;
import com.quietterminal.neon.relay.NeonRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DtlsIntegrationTest {

    private static final int SESSION_ID = 200;

    private static SSLContext relayCtx;
    private static SSLContext clientCtx;

    private NeonRelay relay;
    private NeonHost host;
    private NeonClient client;
    private int relayPort;

    @BeforeAll
    static void loadDtls() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var is = DtlsIntegrationTest.class.getResourceAsStream("/test-relay.p12")) {
            assertNotNull(is, "test-relay.p12 not found in test resources");
            ks.load(is, "neontest".toCharArray());
        }
        relayCtx = DtlsConfig.fromKeyStore(ks, "neontest".toCharArray());
        clientCtx = DtlsConfig.insecureTrustAll();
    }

    @BeforeEach
    void startRelayAndHost() throws Exception {
        NeonConfig relayCfg = NeonConfig.builder()
                .relayPort(0)
                .relayMainLoopSleepMs(1)
                .sslContext(relayCtx)
                .build();
        relay = new NeonRelay("127.0.0.1", relayCfg);
        Thread.ofVirtual().start(() -> {
            try {
                relay.startAndRun();
            } catch (Exception ignored) {
            }
        });

        int attempts = 0;
        while (!relay.isRunning() && attempts++ < 50)
            Thread.sleep(10);
        assertTrue(relay.isRunning(), "Relay did not start");

        relayPort = relay.getLocalAddress().getPort();

        NeonConfig hostCfg = NeonConfig.builder()
                .clientConnectionTimeoutMs(5000)
                .hostProcessingLoopSleepMs(1)
                .sslContext(clientCtx)
                .build();
        host = new NeonHost(SESSION_ID, "127.0.0.1:" + relayPort, hostCfg);
        Thread.ofVirtual().start(() -> {
            try {
                host.startAndRun();
            } catch (Exception ignored) {
            }
        });

        attempts = 0;
        while (!host.isRunning() && attempts++ < 600)
            Thread.sleep(10);
        assertTrue(host.isRunning(), "Host did not start");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null)
            client.close();
        if (host != null)
            host.close();
        if (relay != null)
            relay.close();
    }

    private NeonClient connect(String name) throws Exception {
        NeonConfig cfg = NeonConfig.builder()
                .clientConnectionTimeoutMs(5000)
                .clientProcessingLoopSleepMs(1)
                .clientPingIntervalMs(Integer.MAX_VALUE)
                .sslContext(clientCtx)
                .build();
        NeonClient c = new NeonClient(name, cfg);
        boolean ok = c.connect(SESSION_ID, "127.0.0.1:" + relayPort);
        assertTrue(ok, "connect() returned false for " + name);
        return c;
    }

    @Test
    void dtlsConnect_clientIsAssignedId() throws Exception {
        client = connect("dtls-player1");
        assertNotNull(client.getClientId());
        assertTrue((client.getClientId() & 0xFF) >= 2);
    }

    @Test
    void dtlsConnect_clientReceivesSessionConfig() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedTickRate = new AtomicInteger();

        NeonConfig cfg = NeonConfig.builder()
                .clientConnectionTimeoutMs(5000)
                .clientProcessingLoopSleepMs(1)
                .clientPingIntervalMs(Integer.MAX_VALUE)
                .sslContext(clientCtx)
                .build();
        client = new NeonClient("dtls-player2", cfg);
        client.setSessionConfigCallback(sc -> {
            receivedTickRate.set(sc.tickRate());
            latch.countDown();
        });

        assertTrue(client.connect(SESSION_ID, "127.0.0.1:" + relayPort));
        Thread.ofVirtual().start(client::run);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "SESSION_CONFIG not received over DTLS");
        assertEquals(60, receivedTickRate.get());
    }

    @Test
    void dtlsConnect_gamePacket_fromClient_reachesHost() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean hostGotPacket = new AtomicBoolean(false);

        host.setUnhandledPacketCallback((type, sender, payload) -> {
            hostGotPacket.set(true);
            latch.countDown();
        });

        client = connect("dtls-player3");
        Thread.ofVirtual().start(client::run);

        client.sendPacket(new byte[] { 1, 2, 3 }, (byte) 0x10, (byte) 1);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Host did not receive game packet over DTLS");
        assertTrue(hostGotPacket.get());
    }

    @Test
    void dtlsReconnect_afterDisconnect_succeeds() throws Exception {
        NeonConfig cfg = NeonConfig.builder()
                .clientConnectionTimeoutMs(5000)
                .clientProcessingLoopSleepMs(1)
                .clientPingIntervalMs(Integer.MAX_VALUE)
                .clientInitialReconnectDelayMs(100)
                .sslContext(clientCtx)
                .build();
        client = new NeonClient("dtls-reconnect", cfg);
        assertTrue(client.connect(SESSION_ID, "127.0.0.1:" + relayPort));

        client.stop();
        Thread.sleep(200);

        boolean[] result = { false };
        Thread t = Thread.ofVirtual().start(() -> result[0] = client.reconnect(1));
        t.join(6000);
        assertTrue(result[0], "Reconnect over DTLS failed");
    }
}
