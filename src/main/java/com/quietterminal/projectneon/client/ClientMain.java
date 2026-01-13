package com.quietterminal.projectneon.client;

import com.quietterminal.projectneon.core.PacketPayload;
import com.quietterminal.projectneon.util.LoggerConfig;

import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI entry point for the Neon client.
 * This class is internal and not part of the public API.
 */
class ClientMain {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(ClientMain.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("=== Project Neon Client ===");
            System.out.println();

            System.out.print("Enter your name: ");
            String name = scanner.nextLine().trim();
            if (name.isEmpty()) {
                logger.log(Level.SEVERE, "Name cannot be empty");
                System.err.println("Name cannot be empty");
                return;
            }

            System.out.print("Enter session ID: ");
            int sessionId;
            try {
                sessionId = Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                logger.log(Level.SEVERE, "Invalid session ID provided", e);
                System.err.println("Invalid session ID");
                return;
            }

            System.out.print("Enter relay address (host:port, default 127.0.0.1:7777): ");
            String relayAddr = scanner.nextLine().trim();
            if (relayAddr.isEmpty()) {
                relayAddr = "127.0.0.1:7777";
            }

            try (NeonClient client = new NeonClient(name)) {
                client.setPongCallback((responseTime, timestamp) ->
                    System.out.println("Pong received! Response time: " + responseTime + "ms")
                );

                client.setSessionConfigCallback((version, tickRate, maxPacketSize) ->
                    System.out.println("Session config: version=" + version +
                        ", tickRate=" + tickRate + ", maxPacketSize=" + maxPacketSize)
                );

                client.setPacketTypeRegistryCallback(registry -> {
                    System.out.println("Packet type registry received with " +
                        registry.entries().size() + " entries");
                    for (PacketPayload.PacketTypeEntry entry : registry.entries()) {
                        System.out.println("  [0x" + String.format("%02X", entry.packetId() & 0xFF) +
                            "] " + entry.name() + ": " + entry.description());
                    }
                });

                client.setUnhandledPacketCallback((packetType, fromClientId) ->
                    System.out.println("Unhandled packet type 0x" +
                        String.format("%02X", packetType & 0xFF) +
                        " from client " + (fromClientId & 0xFF))
                );

                client.setWrongDestinationCallback((myId, destId) -> {
                    logger.log(Level.WARNING, "Received packet for client {0} but I am client {1}",
                        new Object[]{destId & 0xFF, myId & 0xFF});
                    System.err.println("Received packet for client " + (destId & 0xFF) +
                        " but I am client " + (myId & 0xFF));
                });

                System.out.println("\nConnecting to relay at " + relayAddr + "...");
                boolean connected = client.connect(sessionId, relayAddr);

                if (!connected) {
                    logger.log(Level.SEVERE, "Failed to connect to relay at {0} [SessionID={1}, ClientName={2}]",
                        new Object[]{relayAddr, sessionId, name});
                    System.err.println("Failed to connect");
                    return;
                }

                System.out.println("Connected! Client ID: " + client.getClientId().orElse((byte) 0));
                System.out.println("Session ID: " + client.getSessionId().orElse(0));
                System.out.println("\nClient running. Auto-ping enabled.");
                System.out.println("Press Ctrl+C to exit.\n");

                client.run();

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Client error", e);
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
