package com.quietterminal.neon.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immutable 8-byte little-endian packet header for the Neon wire format.
 *
 * <p>Layout: {@code magic(2) version(1) packetType(1) sequence(2) clientId(1) destinationId(1)}.
 * The {@code magic} field must equal {@link #MAGIC} ({@code 0x4E45} / "NE");
 * packets with any other value are rejected during deserialization.
 */
public record PacketHeader(
        short magic,
        byte version,
        byte packetType,
        short sequence,
        byte clientId,
        byte destinationId) {

    public static final short MAGIC = (short) 0x4E45;
    public static final byte VERSION = (byte) 1;
    public static final int HEADER_SIZE = 8;

    public PacketHeader {
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic: 0x" + Integer.toHexString(magic & 0xFFFF));
        }
    }

    public static PacketHeader create(byte packetType, short sequence, byte clientId, byte destinationId) {
        return new PacketHeader(MAGIC, VERSION, packetType, sequence, clientId, destinationId);
    }

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(magic);
        buf.put(version);
        buf.put(packetType);
        buf.putShort(sequence);
        buf.put(clientId);
        buf.put(destinationId);
        return buf.array();
    }

    public static PacketHeader fromBytes(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Buffer too short for header: " + data.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        short magic = buf.getShort();
        byte version = buf.get();
        byte packetType = buf.get();
        short sequence = buf.getShort();
        byte clientId = buf.get();
        byte destinationId = buf.get();
        return new PacketHeader(magic, version, packetType, sequence, clientId, destinationId);
    }
}
