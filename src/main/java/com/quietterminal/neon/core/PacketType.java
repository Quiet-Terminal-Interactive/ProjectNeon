package com.quietterminal.neon.core;

/**
 * Protocol packet types used in the Neon wire format.
 * Values {@code 0x10} and above are treated as {@link #GAME_PACKET} and are application-defined.
 */
public enum PacketType {
    /** Client requesting to join a session ({@code 0x01}). */
    CONNECT_REQUEST((byte) 0x01),
    /** Relay or host accepting a connection request ({@code 0x02}). */
    CONNECT_ACCEPT((byte) 0x02),
    /** Relay or host denying a connection request ({@code 0x03}). */
    CONNECT_DENY((byte) 0x03),
    /** Host delivering tick rate and max packet size to a client ({@code 0x04}). Delivered reliably. */
    SESSION_CONFIG((byte) 0x04),
    /** Host advertising registered game packet types ({@code 0x05}). */
    PACKET_TYPE_REGISTRY((byte) 0x05),
    /** Host registering a new session with the relay ({@code 0x06}). */
    HOST_REGISTER((byte) 0x06),
    /** Keepalive request ({@code 0x0B}). */
    PING((byte) 0x0B),
    /** Response to a {@link #PING} ({@code 0x0C}). */
    PONG((byte) 0x0C),
    /** Notification that a peer has disconnected ({@code 0x0D}). */
    DISCONNECT_NOTICE((byte) 0x0D),
    /** Acknowledgement of reliably-delivered packets ({@code 0x0E}). */
    ACK((byte) 0x0E),
    /** Client requesting to rejoin a session with a previously issued token ({@code 0x0F}). */
    RECONNECT_REQUEST((byte) 0x0F),
    /** Application-defined game packet; any type byte {@code >= 0x10} maps here. */
    GAME_PACKET((byte) 0x10);

    private final byte value;

    PacketType(byte value) {
        this.value = value;
    }

    /** Returns the wire-format byte value for this packet type. */
    public byte getValue() {
        return value;
    }

    /**
     * Returns the {@code PacketType} for the given wire byte. Any value {@code >= 0x10} returns
     * {@link #GAME_PACKET}.
     *
     * @throws IllegalArgumentException for reserved values with no mapping
     */
    public static PacketType fromByte(byte b) {
        int u = b & 0xFF;
        if (u >= 0x10)
            return GAME_PACKET;
        return switch (u) {
            case 0x01 -> CONNECT_REQUEST;
            case 0x02 -> CONNECT_ACCEPT;
            case 0x03 -> CONNECT_DENY;
            case 0x04 -> SESSION_CONFIG;
            case 0x05 -> PACKET_TYPE_REGISTRY;
            case 0x06 -> HOST_REGISTER;
            case 0x0B -> PING;
            case 0x0C -> PONG;
            case 0x0D -> DISCONNECT_NOTICE;
            case 0x0E -> ACK;
            case 0x0F -> RECONNECT_REQUEST;
            default -> throw new IllegalArgumentException("Unknown packet type: 0x" + Integer.toHexString(u));
        };
    }

    /** Returns {@code true} for all protocol-defined types, {@code false} for {@link #GAME_PACKET}. */
    public boolean isCorePacket() {
        return this != GAME_PACKET;
    }
}
