package com.quietterminal.projectneon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PacketType enum.
 */
class PacketTypeTest {

    @Test
    @DisplayName("Should map CONNECT_REQUEST to 0x01")
    void testConnectRequestValue() {
        assertEquals((byte) 0x01, PacketType.CONNECT_REQUEST.getValue());
    }

    @Test
    @DisplayName("Should map CONNECT_ACCEPT to 0x02")
    void testConnectAcceptValue() {
        assertEquals((byte) 0x02, PacketType.CONNECT_ACCEPT.getValue());
    }

    @Test
    @DisplayName("Should map CONNECT_DENY to 0x03")
    void testConnectDenyValue() {
        assertEquals((byte) 0x03, PacketType.CONNECT_DENY.getValue());
    }

    @Test
    @DisplayName("Should map SESSION_CONFIG to 0x04")
    void testSessionConfigValue() {
        assertEquals((byte) 0x04, PacketType.SESSION_CONFIG.getValue());
    }

    @Test
    @DisplayName("Should map PACKET_TYPE_REGISTRY to 0x05")
    void testPacketTypeRegistryValue() {
        assertEquals((byte) 0x05, PacketType.PACKET_TYPE_REGISTRY.getValue());
    }

    @Test
    @DisplayName("Should map PING to 0x0B")
    void testPingValue() {
        assertEquals((byte) 0x0B, PacketType.PING.getValue());
    }

    @Test
    @DisplayName("Should map PONG to 0x0C")
    void testPongValue() {
        assertEquals((byte) 0x0C, PacketType.PONG.getValue());
    }

    @Test
    @DisplayName("Should map DISCONNECT_NOTICE to 0x0D")
    void testDisconnectNoticeValue() {
        assertEquals((byte) 0x0D, PacketType.DISCONNECT_NOTICE.getValue());
    }

    @Test
    @DisplayName("Should map ACK to 0x0E")
    void testAckValue() {
        assertEquals((byte) 0x0E, PacketType.ACK.getValue());
    }

    @Test
    @DisplayName("Should map GAME_PACKET to 0x10")
    void testGamePacketValue() {
        assertEquals((byte) 0x10, PacketType.GAME_PACKET.getValue());
    }

    @Test
    @DisplayName("Should convert byte 0x01 to CONNECT_REQUEST")
    void testFromByteConnectRequest() {
        assertEquals(PacketType.CONNECT_REQUEST, PacketType.fromByte((byte) 0x01));
    }

    @Test
    @DisplayName("Should convert byte 0x02 to CONNECT_ACCEPT")
    void testFromByteConnectAccept() {
        assertEquals(PacketType.CONNECT_ACCEPT, PacketType.fromByte((byte) 0x02));
    }

    @Test
    @DisplayName("Should convert byte 0x03 to CONNECT_DENY")
    void testFromByteConnectDeny() {
        assertEquals(PacketType.CONNECT_DENY, PacketType.fromByte((byte) 0x03));
    }

    @Test
    @DisplayName("Should convert byte 0x04 to SESSION_CONFIG")
    void testFromByteSessionConfig() {
        assertEquals(PacketType.SESSION_CONFIG, PacketType.fromByte((byte) 0x04));
    }

    @Test
    @DisplayName("Should convert byte 0x05 to PACKET_TYPE_REGISTRY")
    void testFromBytePacketTypeRegistry() {
        assertEquals(PacketType.PACKET_TYPE_REGISTRY, PacketType.fromByte((byte) 0x05));
    }

    @Test
    @DisplayName("Should convert byte 0x0B to PING")
    void testFromBytePing() {
        assertEquals(PacketType.PING, PacketType.fromByte((byte) 0x0B));
    }

    @Test
    @DisplayName("Should convert byte 0x0C to PONG")
    void testFromBytePong() {
        assertEquals(PacketType.PONG, PacketType.fromByte((byte) 0x0C));
    }

    @Test
    @DisplayName("Should convert byte 0x0D to DISCONNECT_NOTICE")
    void testFromByteDisconnectNotice() {
        assertEquals(PacketType.DISCONNECT_NOTICE, PacketType.fromByte((byte) 0x0D));
    }

    @Test
    @DisplayName("Should convert byte 0x0E to ACK")
    void testFromByteAck() {
        assertEquals(PacketType.ACK, PacketType.fromByte((byte) 0x0E));
    }

    @ParameterizedTest
    @ValueSource(bytes = {0x10, 0x11, 0x20, 0x50, (byte) 0xFF})
    @DisplayName("Should convert bytes >= 0x10 to GAME_PACKET")
    void testFromByteGamePacket(byte value) {
        assertEquals(PacketType.GAME_PACKET, PacketType.fromByte(value));
    }

    @ParameterizedTest
    @ValueSource(bytes = {0x00, 0x06, 0x07, 0x08, 0x09, 0x0A})
    @DisplayName("Should throw exception for invalid byte values in core range")
    void testFromByteInvalidCoreValues(byte value) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PacketType.fromByte(value)
        );

        assertTrue(exception.getMessage().contains("Unknown packet type"));
        assertTrue(exception.getMessage().contains(Integer.toHexString(value & 0xFF)));
    }

    @Test
    @DisplayName("Should identify CONNECT_REQUEST as core packet")
    void testConnectRequestIsCorePacket() {
        assertTrue(PacketType.CONNECT_REQUEST.isCorePacket());
    }

    @Test
    @DisplayName("Should identify CONNECT_ACCEPT as core packet")
    void testConnectAcceptIsCorePacket() {
        assertTrue(PacketType.CONNECT_ACCEPT.isCorePacket());
    }

    @Test
    @DisplayName("Should identify PING as core packet")
    void testPingIsCorePacket() {
        assertTrue(PacketType.PING.isCorePacket());
    }

    @Test
    @DisplayName("Should identify ACK as core packet")
    void testAckIsCorePacket() {
        assertTrue(PacketType.ACK.isCorePacket());
    }

    @Test
    @DisplayName("Should identify GAME_PACKET as non-core packet")
    void testGamePacketIsNotCorePacket() {
        assertFalse(PacketType.GAME_PACKET.isCorePacket());
    }

    @Test
    @DisplayName("Should verify all core packet types are < 0x10")
    void testAllCorePacketsAreInCoreRange() {
        PacketType[] coreTypes = {
            PacketType.CONNECT_REQUEST,
            PacketType.CONNECT_ACCEPT,
            PacketType.CONNECT_DENY,
            PacketType.SESSION_CONFIG,
            PacketType.PACKET_TYPE_REGISTRY,
            PacketType.PING,
            PacketType.PONG,
            PacketType.DISCONNECT_NOTICE,
            PacketType.ACK
        };

        for (PacketType type : coreTypes) {
            assertTrue((type.getValue() & 0xFF) < 0x10,
                type + " should be in core range (< 0x10)");
            assertTrue(type.isCorePacket(),
                type + " should be identified as core packet");
        }
    }

    @Test
    @DisplayName("Should verify GAME_PACKET is >= 0x10")
    void testGamePacketIsInGameRange() {
        assertTrue((PacketType.GAME_PACKET.getValue() & 0xFF) >= 0x10);
        assertFalse(PacketType.GAME_PACKET.isCorePacket());
    }

    @Test
    @DisplayName("Should handle round-trip conversion for all enum values")
    void testRoundTripConversion() {
        for (PacketType type : PacketType.values()) {
            byte value = type.getValue();
            PacketType converted = PacketType.fromByte(value);

            // For GAME_PACKET, all values >= 0x10 map to it
            if (type == PacketType.GAME_PACKET) {
                assertEquals(PacketType.GAME_PACKET, converted);
            } else {
                assertEquals(type, converted);
            }
        }
    }

    @Test
    @DisplayName("Should handle unsigned byte values correctly")
    void testUnsignedByteHandling() {
        // Test that bytes are treated as unsigned (0xFF = 255, not -1)
        byte unsignedByte = (byte) 0xFF;
        PacketType result = PacketType.fromByte(unsignedByte);
        assertEquals(PacketType.GAME_PACKET, result);
    }

    @Test
    @DisplayName("Should throw exception for byte value 0x00")
    void testZeroByteValue() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PacketType.fromByte((byte) 0x00)
        );

        assertTrue(exception.getMessage().contains("Unknown packet type: 0x0"));
    }

    @Test
    @DisplayName("Should include hex value in exception message")
    void testExceptionMessageFormat() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PacketType.fromByte((byte) 0x06)
        );

        assertTrue(exception.getMessage().contains("Unknown packet type"));
        assertTrue(exception.getMessage().contains("0x6") || exception.getMessage().contains("0x06"));
    }

    @Test
    @DisplayName("Should have unique values for all core packet types")
    void testUniqueValues() {
        PacketType[] allTypes = PacketType.values();

        for (int i = 0; i < allTypes.length - 1; i++) {
            for (int j = i + 1; j < allTypes.length; j++) {
                if (allTypes[i] != PacketType.GAME_PACKET && allTypes[j] != PacketType.GAME_PACKET) {
                    assertNotEquals(allTypes[i].getValue(), allTypes[j].getValue(),
                        allTypes[i] + " and " + allTypes[j] + " should have different values");
                }
            }
        }
    }
}
