package com.quietterminal.projectneon.core;

/**
 * Core packet types for the Neon protocol.
 * Range 0x01-0x0F reserved for core protocol.
 * Range 0x10-0xFF available for game-defined packets.
 */
public enum PacketType {
    CONNECT_REQUEST((byte) 0x01),
    CONNECT_ACCEPT((byte) 0x02),
    CONNECT_DENY((byte) 0x03),
    SESSION_CONFIG((byte) 0x04),
    PACKET_TYPE_REGISTRY((byte) 0x05),
    PING((byte) 0x0B),
    PONG((byte) 0x0C),
    DISCONNECT_NOTICE((byte) 0x0D),
    ACK((byte) 0x0E),
    GAME_PACKET((byte) 0x10);

    private final byte value;

    PacketType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    public static PacketType fromByte(byte value) {
        return switch (value) {
            case 0x01 -> CONNECT_REQUEST;
            case 0x02 -> CONNECT_ACCEPT;
            case 0x03 -> CONNECT_DENY;
            case 0x04 -> SESSION_CONFIG;
            case 0x05 -> PACKET_TYPE_REGISTRY;
            case 0x0B -> PING;
            case 0x0C -> PONG;
            case 0x0D -> DISCONNECT_NOTICE;
            case 0x0E -> ACK;
            default -> {
                if ((value & 0xFF) >= 0x10) {
                    yield GAME_PACKET;
                }
                throw new IllegalArgumentException("Unknown packet type: 0x" + Integer.toHexString(value & 0xFF));
            }
        };
    }

    public boolean isCorePacket() {
        return (value & 0xFF) < 0x10;
    }
}
