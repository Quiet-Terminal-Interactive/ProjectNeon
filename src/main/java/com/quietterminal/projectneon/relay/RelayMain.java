package com.quietterminal.projectneon.relay;

/**
 * CLI entry point for the Neon relay server.
 */
public class RelayMain {
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
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
