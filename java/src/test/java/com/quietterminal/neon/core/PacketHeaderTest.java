package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketHeaderTest {

    @Test
    void roundTrip() {
        PacketHeader original = PacketHeader.create((byte) 0x01, (short) 42, (byte) 2, (byte) 0);
        PacketHeader restored = PacketHeader.fromBytes(original.toBytes());
        assertEquals(original, restored);
    }

    @Test
    void toBytesIsExactlyHeaderSize() {
        byte[] bytes = PacketHeader.create((byte) 0x04, (short) 0, (byte) 1, (byte) 0).toBytes();
        assertEquals(PacketHeader.HEADER_SIZE, bytes.length);
    }

    @Test
    void littleEndianLayout() {
        PacketHeader h = PacketHeader.create((byte) 0x01, (short) 0x0102, (byte) 3, (byte) 4);
        byte[] bytes = h.toBytes();
        // magic 0x4E45
        assertEquals((byte) 0x45, bytes[0]);
        assertEquals((byte) 0x4E, bytes[1]);
        // version
        assertEquals((byte) 1, bytes[2]);
        // packetType
        assertEquals((byte) 0x01, bytes[3]);
        // sequence 0x0102
        assertEquals((byte) 0x02, bytes[4]);
        assertEquals((byte) 0x01, bytes[5]);
        // clientId
        assertEquals((byte) 3, bytes[6]);
        // destinationId
        assertEquals((byte) 4, bytes[7]);
    }

    @Test
    void fromBytesIgnoresExtraTrailingBytes() {
        byte[] base = PacketHeader.create((byte) 0x01, (short) 1, (byte) 2, (byte) 3).toBytes();
        byte[] padded = new byte[base.length + 10];
        System.arraycopy(base, 0, padded, 0, base.length);
        PacketHeader h = PacketHeader.fromBytes(padded);
        assertEquals((byte) 0x01, h.packetType());
    }

    @Test
    void invalidMagicThrows() {
        byte[] data = new byte[8]; // magic = 0x0000
        assertThrows(IllegalArgumentException.class, () -> PacketHeader.fromBytes(data));
    }

    @Test
    void bufferTooShortThrows() {
        byte[] data = new byte[7];
        assertThrows(IllegalArgumentException.class, () -> PacketHeader.fromBytes(data));
    }

    @Test
    void constructorRejectsWrongMagic() {
        assertThrows(IllegalArgumentException.class,
                () -> new PacketHeader((short) 0x0000, (byte) 1, (byte) 0x01, (short) 0, (byte) 0, (byte) 0));
    }
}
