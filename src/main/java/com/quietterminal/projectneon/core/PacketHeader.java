package com.quietterminal.projectneon.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fixed 8-byte packet header for all Neon packets.
 *
 * Layout:
 * - magic: u16 (0x4E45 = "NE")
 * - version: u8 (Protocol version)
 * - packet_type: u8 (See PacketType enum)
 * - sequence: u16 (For ordering/reliability)
 * - client_id: u8 (Sender)
 * - destination_id: u8 (Target: 0=broadcast, 1=host, 2+=clients)
 */
public record PacketHeader(
    short magic,
    byte version,
    byte packetType,
    short sequence,
    byte clientId,
    byte destinationId
) {
    public static final short MAGIC = (short) 0x4E45; // "NE"
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 8;

    public PacketHeader {
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                String.format("Invalid magic number: 0x%04X (expected 0x%04X)",
                    magic & 0xFFFF, MAGIC & 0xFFFF)
            );
        }
    }

    /**
     * Creates a new packet header with default magic and version.
     */
    public static PacketHeader create(byte packetType, short sequence, byte clientId, byte destinationId) {
        return new PacketHeader(MAGIC, VERSION, packetType, sequence, clientId, destinationId);
    }

    /**
     * Serializes the header to bytes (little-endian).
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(magic);
        buffer.put(version);
        buffer.put(packetType);
        buffer.putShort(sequence);
        buffer.put(clientId);
        buffer.put(destinationId);
        return buffer.array();
    }

    /**
     * Deserializes a header from bytes (little-endian).
     */
    public static PacketHeader fromBytes(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Insufficient bytes for packet header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return new PacketHeader(
            buffer.getShort(),
            buffer.get(),
            buffer.get(),
            buffer.getShort(),
            buffer.get(),
            buffer.get()
        );
    }

    @Override
    public String toString() {
        return String.format(
            "PacketHeader[magic=0x%04X, version=%d, type=0x%02X, seq=%d, from=%d, to=%d]",
            magic & 0xFFFF, version & 0xFF, packetType & 0xFF,
            sequence & 0xFFFF, clientId & 0xFF, destinationId & 0xFF
        );
    }
}
