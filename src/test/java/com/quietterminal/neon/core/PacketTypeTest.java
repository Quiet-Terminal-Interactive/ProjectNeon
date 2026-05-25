package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketTypeTest {

    @Test
    void knownTypesRoundTrip() {
        assertEquals(PacketType.CONNECT_REQUEST, PacketType.fromByte((byte) 0x01));
        assertEquals(PacketType.CONNECT_ACCEPT, PacketType.fromByte((byte) 0x02));
        assertEquals(PacketType.CONNECT_DENY, PacketType.fromByte((byte) 0x03));
        assertEquals(PacketType.SESSION_CONFIG, PacketType.fromByte((byte) 0x04));
        assertEquals(PacketType.PACKET_TYPE_REGISTRY, PacketType.fromByte((byte) 0x05));
        assertEquals(PacketType.HOST_REGISTER, PacketType.fromByte((byte) 0x06));
        assertEquals(PacketType.PING, PacketType.fromByte((byte) 0x0B));
        assertEquals(PacketType.PONG, PacketType.fromByte((byte) 0x0C));
        assertEquals(PacketType.DISCONNECT_NOTICE, PacketType.fromByte((byte) 0x0D));
        assertEquals(PacketType.ACK, PacketType.fromByte((byte) 0x0E));
        assertEquals(PacketType.RECONNECT_REQUEST, PacketType.fromByte((byte) 0x0F));
    }

    @Test
    void gamePacketSentinelCoversEntireRange() {
        assertEquals(PacketType.GAME_PACKET, PacketType.fromByte((byte) 0x10));
        assertEquals(PacketType.GAME_PACKET, PacketType.fromByte((byte) 0x42));
        assertEquals(PacketType.GAME_PACKET, PacketType.fromByte((byte) 0xFF));
    }

    @Test
    void reservedRangeThrows() {
        for (int i = 0x07; i <= 0x0A; i++) {
            byte b = (byte) i;
            assertThrows(IllegalArgumentException.class, () -> PacketType.fromByte(b),
                    "Expected exception for 0x" + Integer.toHexString(i));
        }
    }

    @Test
    void isCorePacketReturnsFalseOnlyForGamePacket() {
        for (PacketType t : PacketType.values()) {
            if (t == PacketType.GAME_PACKET) {
                assertFalse(t.isCorePacket());
            } else {
                assertTrue(t.isCorePacket());
            }
        }
    }

    @Test
    void getValueMatchesEnumByte() {
        assertEquals((byte) 0x01, PacketType.CONNECT_REQUEST.getValue());
        assertEquals((byte) 0x06, PacketType.HOST_REGISTER.getValue());
        assertEquals((byte) 0x10, PacketType.GAME_PACKET.getValue());
    }
}
