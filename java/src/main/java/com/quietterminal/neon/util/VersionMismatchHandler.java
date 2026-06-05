package com.quietterminal.neon.util;

import java.util.logging.Logger;

/** Logs and optionally rejects packets whose protocol version does not match the local version. */
public final class VersionMismatchHandler {

    private static final Logger logger = Logger.getLogger(VersionMismatchHandler.class.getName());

    private VersionMismatchHandler() {
    }

    public static void handle(byte expected, byte received, String source) {
        logger.warning(() -> "Protocol version mismatch from " + source
                + ": expected=" + (expected & 0xFF)
                + " received=" + (received & 0xFF)
                + " — packet forwarded but behaviour may be undefined");
    }

    public static boolean isCompatible(byte expected, byte received) {
        return (expected & 0xFF) == (received & 0xFF);
    }
}
