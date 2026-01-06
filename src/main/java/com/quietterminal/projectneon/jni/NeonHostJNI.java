package com.quietterminal.projectneon.jni;

/**
 * JNI wrapper for NeonHost to enable C/C++ integration.
 */
public class NeonHostJNI {

    static {
        try {
            System.loadLibrary("neon_jni");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load neon_jni library: " + e.getMessage());
        }
    }

    /**
     * Creates a new NeonHost instance.
     * @param sessionId Session ID for the host
     * @param relayAddr Relay address (host:port)
     * @return Pointer to NeonHost (as long) or 0 on error
     */
    public static native long neonHostNew(int sessionId, String relayAddr);

    /**
     * Starts the host (non-blocking in JNI - should be called from a thread).
     * @param hostPtr Pointer to NeonHost
     * @return true if started successfully, false otherwise
     */
    public static native boolean neonHostStart(long hostPtr);

    /**
     * Processes incoming packets (for manual processing instead of start()).
     * @param hostPtr Pointer to NeonHost
     * @return Number of packets processed, or -1 on error
     */
    public static native int neonHostProcessPackets(long hostPtr);

    /**
     * Gets the session ID.
     * @param hostPtr Pointer to NeonHost
     * @return Session ID
     */
    public static native int neonHostGetSessionId(long hostPtr);

    /**
     * Gets the number of connected clients.
     * @param hostPtr Pointer to NeonHost
     * @return Client count
     */
    public static native int neonHostGetClientCount(long hostPtr);

    /**
     * Sets the client connect callback.
     * @param hostPtr Pointer to NeonHost
     * @param callback C function pointer (address as long)
     */
    public static native void neonHostSetClientConnectCallback(long hostPtr, long callback);

    /**
     * Sets the client deny callback.
     * @param hostPtr Pointer to NeonHost
     * @param callback C function pointer (address as long)
     */
    public static native void neonHostSetClientDenyCallback(long hostPtr, long callback);

    /**
     * Sets the ping received callback.
     * @param hostPtr Pointer to NeonHost
     * @param callback C function pointer (address as long)
     */
    public static native void neonHostSetPingReceivedCallback(long hostPtr, long callback);

    /**
     * Sets the unhandled packet callback.
     * @param hostPtr Pointer to NeonHost
     * @param callback C function pointer (address as long)
     */
    public static native void neonHostSetUnhandledPacketCallback(long hostPtr, long callback);

    /**
     * Frees the NeonHost instance.
     * @param hostPtr Pointer to NeonHost
     */
    public static native void neonHostFree(long hostPtr);
}
