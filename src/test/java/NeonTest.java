import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.host.NeonHost;
import com.quietterminal.projectneon.relay.NeonRelay;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration test for Project Neon.
 * Tests the full flow: Relay -> Host -> Client connections.
 */
public class NeonTest {
    private static final String RELAY_ADDR = "127.0.0.1:7777";
    private static final int SESSION_ID = 12345;
    private static final int TEST_DURATION_SECONDS = 10;

    public static void main(String[] args) {
        System.out.println("=== Project Neon Integration Test ===\n");

        CountDownLatch relayReady = new CountDownLatch(1);
        CountDownLatch hostReady = new CountDownLatch(1);

        Thread relayThread = new Thread(() -> {
            try (NeonRelay relay = new NeonRelay(RELAY_ADDR)) {
                System.out.println("[RELAY] Started on " + RELAY_ADDR);
                relayReady.countDown();
                relay.start();
            } catch (Exception e) {
                System.err.println("[RELAY] Error: " + e.getMessage());
                e.printStackTrace();
            }
        });
        relayThread.setDaemon(true);
        relayThread.start();

        try {
            relayReady.await(2, TimeUnit.SECONDS);
            Thread.sleep(500);

            Thread hostThread = new Thread(() -> {
                try (NeonHost host = new NeonHost(SESSION_ID, RELAY_ADDR)) {
                    host.setClientConnectCallback((clientId, name, session) ->
                        System.out.println("[HOST] Client connected: " + name +
                            " (ID: " + (clientId & 0xFF) + ")")
                    );

                    host.setPingReceivedCallback(clientId ->
                        System.out.println("[HOST] Ping from client " + (clientId & 0xFF))
                    );

                    System.out.println("[HOST] Started on session " + SESSION_ID);
                    hostReady.countDown();
                    host.start();
                } catch (Exception e) {
                    System.err.println("[HOST] Error: " + e.getMessage());
                }
            });
            hostThread.setDaemon(true);
            hostThread.start();

            hostReady.await(2, TimeUnit.SECONDS);
            Thread.sleep(500);

            Thread client1Thread = new Thread(() -> runClient("Alice"));
            Thread client2Thread = new Thread(() -> runClient("Bob"));

            client1Thread.setDaemon(true);
            client2Thread.setDaemon(true);

            client1Thread.start();
            Thread.sleep(1000);
            client2Thread.start();

            System.out.println("\n[TEST] Running for " + TEST_DURATION_SECONDS + " seconds...\n");
            Thread.sleep(TEST_DURATION_SECONDS * 1000);

            System.out.println("\n[TEST] Test complete!");
            System.out.println("[TEST] Check the output above for:");
            System.out.println("  - Relay forwarding packets");
            System.out.println("  - Host accepting clients");
            System.out.println("  - Clients receiving session config");
            System.out.println("  - Ping/pong exchanges");

        } catch (InterruptedException e) {
            System.err.println("[TEST] Interrupted: " + e.getMessage());
        }

        System.exit(0);
    }

    private static void runClient(String name) {
        try (NeonClient client = new NeonClient(name)) {
            client.setPongCallback((responseTime, timestamp) ->
                System.out.println("[" + name + "] Pong! Response time: " + responseTime + "ms")
            );

            client.setSessionConfigCallback((version, tickRate, maxPacketSize) ->
                System.out.println("[" + name + "] Session config received: tickRate=" + tickRate)
            );

            System.out.println("[" + name + "] Connecting...");
            boolean connected = client.connect(SESSION_ID, RELAY_ADDR);

            if (connected) {
                System.out.println("[" + name + "] Connected! Client ID: " +
                    client.getClientId().orElse((byte) 0));

                while (true) {
                    client.processPackets();
                    Thread.sleep(10);
                }
            } else {
                System.err.println("[" + name + "] Failed to connect");
            }
        } catch (Exception e) {
            if (!e.getMessage().contains("Socket closed")) {
                System.err.println("[" + name + "] Error: " + e.getMessage());
            }
        }
    }
}
