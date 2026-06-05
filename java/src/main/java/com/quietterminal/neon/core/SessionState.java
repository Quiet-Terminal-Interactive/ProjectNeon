package com.quietterminal.neon.core;

/**
 * High-level session state from the client's perspective.
 * Intended for use by game code tracking connection status.
 */
public enum SessionState {
    /** No active or pending connection. */
    DISCONNECTED,
    /** Handshake sent; awaiting accept or deny from the relay. */
    CONNECTING,
    /** Handshake complete; session is active. */
    CONNECTED,
    /** Reconnect token submitted; awaiting host confirmation. */
    RECONNECTING,
    /** Disconnect notice sent; awaiting relay acknowledgement. */
    DISCONNECTING
}
