package com.quietterminal.projectneon.relay;

import com.quietterminal.projectneon.util.LoggerConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI entry point for the Neon relay server.
 * This class is internal and not part of the public API.
 */
class RelayMain {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(RelayMain.class.getName());
        LoggerConfig.configureLogger(logger);
    }
    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0:7777";

    public static void main(String[] args) {
        try {
            System.out.println("=== Project Neon Relay Server ===");
            System.out.println();

            String bindAddress = DEFAULT_BIND_ADDRESS;
            if (args.length > 0 && args[0].equals("--bind") && args.length > 1) {
                bindAddress = args[1];
            }

            System.out.println("Starting relay server...");
            System.out.println("Bind address: " + bindAddress);
            System.out.println("\nRelay running. Waiting for connections...");
            System.out.println("Press Ctrl+C to exit.\n");

            try (NeonRelay relay = new NeonRelay(bindAddress)) {
                relay.start();
            }

        } catch (Exception e) {
            String bindAddr = args.length > 1 ? args[1] : DEFAULT_BIND_ADDRESS;
            logger.log(Level.SEVERE, "Relay error [BindAddress=" + bindAddr + "]", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
