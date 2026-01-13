package com.quietterminal.projectneon.host;

import com.quietterminal.projectneon.util.LoggerConfig;

import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI entry point for the Neon host.
 */
public class HostMain {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(HostMain.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    public static void main(String[] args) {
        try {
            System.out.println("=== Project Neon Host ===");
            System.out.println();

            int sessionId;
            if (args.length > 0) {
                try {
                    sessionId = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    logger.log(Level.SEVERE, "Invalid session ID argument: {0}", args[0]);
                    System.err.println("Invalid session ID. Usage: java HostMain [sessionId]");
                    return;
                }
            } else {
                sessionId = new Random().nextInt(1000000);
                System.out.println("No session ID provided, using random: " + sessionId);
            }

            String relayAddr = "127.0.0.1:7777";

            try (NeonHost host = new NeonHost(sessionId, relayAddr)) {
                host.setClientConnectCallback((clientId, name, session) ->
                    System.out.println("Client connected: " + name +
                        " (ID: " + (clientId & 0xFF) + ", Session: " + session + ")")
                );

                host.setClientDenyCallback((name, reason) ->
                    System.out.println("Client denied: " + name + " - " + reason)
                );

                host.setPingReceivedCallback(clientId ->
                    System.out.println("Ping received from client " + (clientId & 0xFF))
                );

                host.setUnhandledPacketCallback((packetType, fromClientId) ->
                    System.out.println("Unhandled packet type 0x" +
                        String.format("%02X", packetType & 0xFF) +
                        " from client " + (fromClientId & 0xFF))
                );

                System.out.println("Starting host on session " + sessionId);
                System.out.println("Relay address: " + relayAddr);
                System.out.println("\nHost running. Waiting for clients...");
                System.out.println("Press Ctrl+C to exit.\n");

                host.start();

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Host error [SessionID=" + sessionId + "]", e);
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fatal error during host initialization", e);
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
