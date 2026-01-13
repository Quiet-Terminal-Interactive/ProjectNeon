package com.quietterminal.projectneon.jni;

import com.quietterminal.projectneon.util.LoggerConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JNI wrapper for NeonClient to enable C/C++ integration.
 *
 * This class provides native methods that can be called from C/C++ code
 * for game engine integration (Unreal, Unity, custom engines).
 */
public class NeonClientJNI {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(NeonClientJNI.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    static {
        try {
            System.loadLibrary("neon_jni");
        } catch (UnsatisfiedLinkError e) {
            logger.log(Level.SEVERE, "Failed to load neon_jni library", e);
            System.err.println("Failed to load neon_jni library: " + e.getMessage());
        }
    }

    /**
     * Creates a new NeonClient instance.
     * @param name Player name
     * @return Pointer to NeonClient (as long) or 0 on error
     */
    public static native long neonClientNew(String name);

    /**
     * Connects to a relay server.
     * @param clientPtr Pointer to NeonClient
     * @param sessionId Session ID to join
     * @param relayAddr Relay address (host:port)
     * @return true if connected, false otherwise
     */
    public static native boolean neonClientConnect(long clientPtr, int sessionId, String relayAddr);

    /**
     * Processes incoming packets.
     * @param clientPtr Pointer to NeonClient
     * @return Number of packets processed, or -1 on error
     */
    public static native int neonClientProcessPackets(long clientPtr);

    /**
     * Gets the assigned client ID.
     * @param clientPtr Pointer to NeonClient
     * @return Client ID, or -1 if not connected
     */
    public static native int neonClientGetId(long clientPtr);

    /**
     * Gets the current session ID.
     * @param clientPtr Pointer to NeonClient
     * @return Session ID, or -1 if not connected
     */
    public static native int neonClientGetSessionId(long clientPtr);

    /**
     * Checks if the client is connected.
     * @param clientPtr Pointer to NeonClient
     * @return true if connected, false otherwise
     */
    public static native boolean neonClientIsConnected(long clientPtr);

    /**
     * Sends a ping to the host.
     * @param clientPtr Pointer to NeonClient
     * @return true if sent successfully, false otherwise
     */
    public static native boolean neonClientSendPing(long clientPtr);

    /**
     * Enables or disables auto-ping.
     * @param clientPtr Pointer to NeonClient
     * @param enabled true to enable, false to disable
     */
    public static native void neonClientSetAutoPing(long clientPtr, boolean enabled);

    /**
     * Sets the pong callback.
     * @param clientPtr Pointer to NeonClient
     * @param callback C function pointer (address as long)
     */
    public static native void neonClientSetPongCallback(long clientPtr, long callback);

    /**
     * Sets the session config callback.
     * @param clientPtr Pointer to NeonClient
     * @param callback C function pointer (address as long)
     */
    public static native void neonClientSetSessionConfigCallback(long clientPtr, long callback);

    /**
     * Sets the packet type registry callback.
     * @param clientPtr Pointer to NeonClient
     * @param callback C function pointer (address as long)
     */
    public static native void neonClientSetPacketTypeRegistryCallback(long clientPtr, long callback);

    /**
     * Sets the unhandled packet callback.
     * @param clientPtr Pointer to NeonClient
     * @param callback C function pointer (address as long)
     */
    public static native void neonClientSetUnhandledPacketCallback(long clientPtr, long callback);

    /**
     * Sets the wrong destination callback.
     * @param clientPtr Pointer to NeonClient
     * @param callback C function pointer (address as long)
     */
    public static native void neonClientSetWrongDestinationCallback(long clientPtr, long callback);

    /**
     * Frees the NeonClient instance.
     * @param clientPtr Pointer to NeonClient
     */
    public static native void neonClientFree(long clientPtr);

    /**
     * Gets the last error message.
     * @return Error message string, or null if no error
     */
    public static native String neonGetLastError();
}
