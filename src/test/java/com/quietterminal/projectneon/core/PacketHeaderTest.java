package com.quietterminal.projectneon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PacketHeader.
 */
class PacketHeaderTest {

    @Test
    @DisplayName("Should serialize and deserialize header correctly (round-trip)")
    void testSerializationRoundTrip() {
        PacketHeader original = PacketHeader.create(
            PacketType.CONNECT_REQUEST.getValue(),
            (short) 42,
            (byte) 1,
            (byte) 0
        );

        byte[] bytes = original.toBytes();
        PacketHeader deserialized = PacketHeader.fromBytes(bytes);

        assertEquals(original, deserialized);
        assertEquals(PacketHeader.MAGIC, deserialized.magic());
        assertEquals(PacketHeader.VERSION, deserialized.version());
        assertEquals(PacketType.CONNECT_REQUEST.getValue(), deserialized.packetType());
        assertEquals((short) 42, deserialized.sequence());
        assertEquals((byte) 1, deserialized.clientId());
        assertEquals((byte) 0, deserialized.destinationId());
    }

    @Test
    @DisplayName("Should validate magic number on construction")
    void testMagicNumberValidation() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new PacketHeader(
                (short) 0xDEAD, // Wrong magic number
                PacketHeader.VERSION,
                PacketType.PING.getValue(),
                (short) 0,
                (byte) 0,
                (byte) 0
            )
        );

        assertTrue(exception.getMessage().contains("Invalid magic number"));
        assertTrue(exception.getMessage().contains("0xDEAD"));
        assertTrue(exception.getMessage().contains("0x4E45"));
    }

    @Test
    @DisplayName("Should accept correct magic number")
    void testCorrectMagicNumber() {
        assertDoesNotThrow(() -> new PacketHeader(
            PacketHeader.MAGIC,
            PacketHeader.VERSION,
            PacketType.PING.getValue(),
            (short) 0,
            (byte) 0,
            (byte) 0
        ));
    }

    @Test
    @DisplayName("Should handle different protocol versions")
    void testVersionCompatibility() {
        // Current version should work
        PacketHeader v1 = PacketHeader.create(
            PacketType.PING.getValue(),
            (short) 0,
            (byte) 0,
            (byte) 0
        );
        assertEquals(PacketHeader.VERSION, v1.version());

        // Future version should be deserializable (for forward compatibility)
        PacketHeader futureVersion = new PacketHeader(
            PacketHeader.MAGIC,
            (byte) 99, // Future version
            PacketType.PING.getValue(),
            (short) 0,
            (byte) 0,
            (byte) 0
        );
        byte[] bytes = futureVersion.toBytes();
        PacketHeader deserialized = PacketHeader.fromBytes(bytes);
        assertEquals((byte) 99, deserialized.version());
    }

    @Test
    @DisplayName("Should throw exception for insufficient bytes")
    void testInvalidHeaderHandling() {
        byte[] tooShort = new byte[4]; // Header needs 8 bytes

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PacketHeader.fromBytes(tooShort)
        );

        assertTrue(exception.getMessage().contains("Insufficient bytes"));
    }

    @Test
    @DisplayName("Should handle empty byte array")
    void testEmptyByteArray() {
        byte[] empty = new byte[0];

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PacketHeader.fromBytes(empty)
        );

        assertTrue(exception.getMessage().contains("Insufficient bytes"));
    }

    @Test
    @DisplayName("Should serialize to exactly 8 bytes")
    void testHeaderSize() {
        PacketHeader header = PacketHeader.create(
            PacketType.PING.getValue(),
            (short) 0,
            (byte) 0,
            (byte) 0
        );

        byte[] bytes = header.toBytes();
        assertEquals(PacketHeader.HEADER_SIZE, bytes.length);
        assertEquals(8, bytes.length);
    }

    @Test
    @DisplayName("Should handle all packet types")
    void testAllPacketTypes() {
        for (PacketType type : PacketType.values()) {
            PacketHeader header = PacketHeader.create(
                type.getValue(),
                (short) 1,
                (byte) 2,
                (byte) 3
            );

            byte[] bytes = header.toBytes();
            PacketHeader deserialized = PacketHeader.fromBytes(bytes);

            assertEquals(type.getValue(), deserialized.packetType());
        }
    }

    @Test
    @DisplayName("Should handle maximum unsigned values correctly")
    void testMaximumValues() {
        PacketHeader header = PacketHeader.create(
            (byte) 0xFF, // Max byte value
            (short) 0xFFFF, // Max short value
            (byte) 0xFF, // Max client ID
            (byte) 0xFF  // Max destination ID
        );

        byte[] bytes = header.toBytes();
        PacketHeader deserialized = PacketHeader.fromBytes(bytes);

        assertEquals((byte) 0xFF, deserialized.packetType());
        assertEquals((short) 0xFFFF, deserialized.sequence());
        assertEquals((byte) 0xFF, deserialized.clientId());
        assertEquals((byte) 0xFF, deserialized.destinationId());
    }

    @Test
    @DisplayName("Should handle zero values correctly")
    void testZeroValues() {
        PacketHeader header = PacketHeader.create(
            (byte) 0,
            (short) 0,
            (byte) 0,
            (byte) 0
        );

        byte[] bytes = header.toBytes();
        PacketHeader deserialized = PacketHeader.fromBytes(bytes);

        assertEquals((byte) 0, deserialized.packetType());
        assertEquals((short) 0, deserialized.sequence());
        assertEquals((byte) 0, deserialized.clientId());
        assertEquals((byte) 0, deserialized.destinationId());
    }

    @Test
    @DisplayName("Should maintain little-endian byte order")
    void testLittleEndianByteOrder() {
        PacketHeader header = PacketHeader.create(
            PacketType.PING.getValue(),
            (short) 0x1234,
            (byte) 0x56,
            (byte) 0x78
        );

        byte[] bytes = header.toBytes();

        // Magic number (0x4E45) in little-endian: [0x45, 0x4E]
        assertEquals((byte) 0x45, bytes[0]);
        assertEquals((byte) 0x4E, bytes[1]);

        // Sequence (0x1234) in little-endian: [0x34, 0x12]
        assertEquals((byte) 0x34, bytes[4]);
        assertEquals((byte) 0x12, bytes[5]);
    }

    @Test
    @DisplayName("Should produce meaningful toString output")
    void testToString() {
        PacketHeader header = PacketHeader.create(
            PacketType.PING.getValue(),
            (short) 42,
            (byte) 1,
            (byte) 2
        );

        String str = header.toString();

        assertTrue(str.contains("PacketHeader"));
        assertTrue(str.contains("0x4E45")); // Magic
        assertTrue(str.contains("42")); // Sequence
    }

    @Test
    @DisplayName("Should handle record equality correctly")
    void testRecordEquality() {
        PacketHeader header1 = PacketHeader.create(
            PacketType.PING.getValue(),
            (short) 1,
            (byte) 2,
            (byte) 3
        );

        PacketHeader header2 = PacketHeader.create(
            PacketType.PING.getValue(),
            (short) 1,
            (byte) 2,
            (byte) 3
        );

        PacketHeader header3 = PacketHeader.create(
            PacketType.PONG.getValue(),
            (short) 1,
            (byte) 2,
            (byte) 3
        );

        assertEquals(header1, header2);
        assertNotEquals(header1, header3);
        assertEquals(header1.hashCode(), header2.hashCode());
    }
}
