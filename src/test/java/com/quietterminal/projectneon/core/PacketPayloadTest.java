package com.quietterminal.projectneon.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for all PacketPayload implementations.
 */
class PacketPayloadTest {

    @Nested
    @DisplayName("ConnectRequest Tests")
    class ConnectRequestTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            PacketPayload.ConnectRequest original = new PacketPayload.ConnectRequest(
                (byte) 1,
                "TestPlayer",
                12345,
                9999
            );

            byte[] bytes = original.toBytes();
            PacketPayload.ConnectRequest deserialized = PacketPayload.ConnectRequest.fromBytes(bytes);

            assertEquals(original.clientVersion(), deserialized.clientVersion());
            assertEquals(original.desiredName(), deserialized.desiredName());
            assertEquals(original.targetSessionId(), deserialized.targetSessionId());
            assertEquals(original.gameIdentifier(), deserialized.gameIdentifier());
        }

        @Test
        @DisplayName("Should reject name exceeding max length")
        void testBufferOverflowProtection() {
            String longName = "A".repeat(PacketPayload.MAX_NAME_LENGTH + 1);
            PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
                (byte) 1,
                longName,
                1,
                1
            );

            byte[] bytes = request.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Name length"));
            assertTrue(exception.getMessage().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should sanitize client name with control characters")
        void testNameSanitization() {
            String nameWithControlChars = "Test\u0000Player\u0001\u001F";
            PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
                (byte) 1,
                nameWithControlChars,
                1,
                1
            );

            byte[] bytes = request.toBytes();
            PacketPayload.ConnectRequest deserialized = PacketPayload.ConnectRequest.fromBytes(bytes);

            assertFalse(deserialized.desiredName().contains("\u0000"));
            assertFalse(deserialized.desiredName().contains("\u0001"));
            assertEquals("TestPlayer", deserialized.desiredName());
        }

        @Test
        @DisplayName("Should reject negative session ID")
        void testNegativeSessionId() {
            PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
                (byte) 1,
                "Player",
                -1,
                1
            );

            byte[] bytes = request.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Session ID must be a positive integer"));
        }

        @Test
        @DisplayName("Should reject zero session ID")
        void testZeroSessionId() {
            PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
                (byte) 1,
                "Player",
                0,
                1
            );

            byte[] bytes = request.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Session ID must be a positive integer"));
        }

        @Test
        @DisplayName("Should reject empty name after sanitization")
        void testEmptyNameRejection() {
            String onlyControlChars = "\u0000\u0001\u001F";
            PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
                (byte) 1,
                onlyControlChars,
                1,
                1
            );

            byte[] bytes = request.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Client name cannot be empty"));
        }

        @Test
        @DisplayName("Should handle buffer underflow on insufficient bytes")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[2];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
        }

        @Test
        @DisplayName("Should handle UTF-8 encoded names")
        void testUtf8Encoding() {
            PacketPayload.ConnectRequest original = new PacketPayload.ConnectRequest(
                (byte) 1,
                "Player™你好",
                1,
                1
            );

            byte[] bytes = original.toBytes();
            PacketPayload.ConnectRequest deserialized = PacketPayload.ConnectRequest.fromBytes(bytes);

            assertEquals("Player™你好", deserialized.desiredName());
        }
    }

    @Nested
    @DisplayName("ConnectAccept Tests")
    class ConnectAcceptTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            PacketPayload.ConnectAccept original = new PacketPayload.ConnectAccept(
                (byte) 5,
                12345
            );

            byte[] bytes = original.toBytes();
            PacketPayload.ConnectAccept deserialized = PacketPayload.ConnectAccept.fromBytes(bytes);

            assertEquals(original.assignedClientId(), deserialized.assignedClientId());
            assertEquals(original.sessionId(), deserialized.sessionId());
        }

        @Test
        @DisplayName("Should reject negative session ID")
        void testNegativeSessionId() {
            PacketPayload.ConnectAccept accept = new PacketPayload.ConnectAccept(
                (byte) 1,
                -1
            );

            byte[] bytes = accept.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectAccept.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Session ID must be a positive integer"));
        }

        @Test
        @DisplayName("Should handle buffer underflow")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[3];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectAccept.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
            assertTrue(exception.getMessage().contains("expected 5 bytes"));
        }
    }

    @Nested
    @DisplayName("ConnectDeny Tests")
    class ConnectDenyTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            PacketPayload.ConnectDeny original = new PacketPayload.ConnectDeny("Server is full");

            byte[] bytes = original.toBytes();
            PacketPayload.ConnectDeny deserialized = PacketPayload.ConnectDeny.fromBytes(bytes);

            assertEquals(original.reason(), deserialized.reason());
        }

        @Test
        @DisplayName("Should reject reason exceeding max length")
        void testMaxLengthValidation() {
            String longReason = "X".repeat(PacketPayload.MAX_DESCRIPTION_LENGTH + 1);
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny(longReason);

            byte[] bytes = deny.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectDeny.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Reason length"));
            assertTrue(exception.getMessage().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should handle empty reason")
        void testEmptyReason() {
            PacketPayload.ConnectDeny original = new PacketPayload.ConnectDeny("");

            byte[] bytes = original.toBytes();
            PacketPayload.ConnectDeny deserialized = PacketPayload.ConnectDeny.fromBytes(bytes);

            assertEquals("", deserialized.reason());
        }

        @Test
        @DisplayName("Should handle buffer underflow")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[2];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.ConnectDeny.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
        }
    }

    @Nested
    @DisplayName("SessionConfig Tests")
    class SessionConfigTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            PacketPayload.SessionConfig original = new PacketPayload.SessionConfig(
                (byte) 1,
                (short) 60,
                (short) 1024
            );

            byte[] bytes = original.toBytes();
            PacketPayload.SessionConfig deserialized = PacketPayload.SessionConfig.fromBytes(bytes);

            assertEquals(original.version(), deserialized.version());
            assertEquals(original.tickRate(), deserialized.tickRate());
            assertEquals(original.maxPacketSize(), deserialized.maxPacketSize());
        }

        @Test
        @DisplayName("Should handle buffer underflow")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[3];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.SessionConfig.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
            assertTrue(exception.getMessage().contains("expected 5 bytes"));
        }
    }

    @Nested
    @DisplayName("PacketTypeRegistry Tests")
    class PacketTypeRegistryTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            List<PacketPayload.PacketTypeEntry> entries = Arrays.asList(
                new PacketPayload.PacketTypeEntry((byte) 0x10, "PlayerMove", "Player movement packet"),
                new PacketPayload.PacketTypeEntry((byte) 0x11, "PlayerAttack", "Player attack action")
            );

            PacketPayload.PacketTypeRegistry original = new PacketPayload.PacketTypeRegistry(entries);

            byte[] bytes = original.toBytes();
            PacketPayload.PacketTypeRegistry deserialized = PacketPayload.PacketTypeRegistry.fromBytes(bytes);

            assertEquals(entries.size(), deserialized.entries().size());
            for (int i = 0; i < entries.size(); i++) {
                assertEquals(entries.get(i).packetId(), deserialized.entries().get(i).packetId());
                assertEquals(entries.get(i).name(), deserialized.entries().get(i).name());
                assertEquals(entries.get(i).description(), deserialized.entries().get(i).description());
            }
        }

        @Test
        @DisplayName("Should reject count exceeding max")
        void testMaxCountValidation() {
            byte[] bytes = new byte[4];
            bytes[0] = (byte) (PacketPayload.MAX_PACKET_COUNT + 1);
            bytes[1] = 0;
            bytes[2] = 0;
            bytes[3] = 0;

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.PacketTypeRegistry.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Packet type count"));
            assertTrue(exception.getMessage().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should reject name exceeding max length")
        void testMaxNameLengthValidation() {
            List<PacketPayload.PacketTypeEntry> entries = Arrays.asList(
                new PacketPayload.PacketTypeEntry((byte) 0x10, "A".repeat(PacketPayload.MAX_NAME_LENGTH + 1), "Desc")
            );

            PacketPayload.PacketTypeRegistry registry = new PacketPayload.PacketTypeRegistry(entries);
            byte[] bytes = registry.toBytes();

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.PacketTypeRegistry.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("name length"));
            assertTrue(exception.getMessage().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should reject description exceeding max length")
        void testMaxDescriptionLengthValidation() {
            byte[] bytes = new byte[10];
            bytes[0] = 1; // count = 1
            bytes[1] = 0;
            bytes[2] = 0;
            bytes[3] = 0;
            bytes[4] = (byte) 0x10; // packet ID
            bytes[5] = 4; // name length = 4
            bytes[6] = 'N';
            bytes[7] = 'a';
            bytes[8] = 'm';
            bytes[9] = 'e';
            // Description length would overflow the buffer

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.PacketTypeRegistry.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
        }

        @Test
        @DisplayName("Should handle empty registry")
        void testEmptyRegistry() {
            PacketPayload.PacketTypeRegistry original = new PacketPayload.PacketTypeRegistry(List.of());

            byte[] bytes = original.toBytes();
            PacketPayload.PacketTypeRegistry deserialized = PacketPayload.PacketTypeRegistry.fromBytes(bytes);

            assertEquals(0, deserialized.entries().size());
        }

        @Test
        @DisplayName("Should handle buffer underflow")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[2];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.PacketTypeRegistry.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
        }
    }

    @Nested
    @DisplayName("Ping Tests")
    class PingTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            PacketPayload.Ping original = new PacketPayload.Ping(System.currentTimeMillis());

            byte[] bytes = original.toBytes();
            PacketPayload.Ping deserialized = PacketPayload.Ping.fromBytes(bytes);

            assertEquals(original.timestamp(), deserialized.timestamp());
        }

        @Test
        @DisplayName("Should handle buffer underflow")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[4];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.Ping.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
            assertTrue(exception.getMessage().contains("expected 8 bytes"));
        }

        @Test
        @DisplayName("Should handle zero timestamp")
        void testZeroTimestamp() {
            PacketPayload.Ping original = new PacketPayload.Ping(0L);

            byte[] bytes = original.toBytes();
            PacketPayload.Ping deserialized = PacketPayload.Ping.fromBytes(bytes);

            assertEquals(0L, deserialized.timestamp());
        }

        @Test
        @DisplayName("Should handle negative timestamp")
        void testNegativeTimestamp() {
            PacketPayload.Ping original = new PacketPayload.Ping(-12345L);

            byte[] bytes = original.toBytes();
            PacketPayload.Ping deserialized = PacketPayload.Ping.fromBytes(bytes);

            assertEquals(-12345L, deserialized.timestamp());
        }
    }

    @Nested
    @DisplayName("Pong Tests")
    class PongTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            PacketPayload.Pong original = new PacketPayload.Pong(System.currentTimeMillis());

            byte[] bytes = original.toBytes();
            PacketPayload.Pong deserialized = PacketPayload.Pong.fromBytes(bytes);

            assertEquals(original.originalTimestamp(), deserialized.originalTimestamp());
        }

        @Test
        @DisplayName("Should handle buffer underflow")
        void testBufferUnderflow() {
            byte[] insufficientBytes = new byte[4];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.Pong.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
            assertTrue(exception.getMessage().contains("expected 8 bytes"));
        }
    }

    @Nested
    @DisplayName("DisconnectNotice Tests")
    class DisconnectNoticeTests {

        @Test
        @DisplayName("Should serialize to empty byte array")
        void testSerialization() {
            PacketPayload.DisconnectNotice notice = new PacketPayload.DisconnectNotice();

            byte[] bytes = notice.toBytes();

            assertEquals(0, bytes.length);
        }

        @Test
        @DisplayName("Should deserialize from empty byte array")
        void testDeserialization() {
            byte[] bytes = new byte[0];

            PacketPayload.DisconnectNotice notice = PacketPayload.DisconnectNotice.fromBytes(bytes);

            assertNotNull(notice);
        }

        @Test
        @DisplayName("Should handle non-empty byte array")
        void testDeserializationWithExtraBytes() {
            byte[] bytes = new byte[10];

            PacketPayload.DisconnectNotice notice = PacketPayload.DisconnectNotice.fromBytes(bytes);

            assertNotNull(notice);
        }
    }

    @Nested
    @DisplayName("Ack Tests")
    class AckTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            List<Short> sequences = Arrays.asList((short) 1, (short) 5, (short) 42, (short) 100);
            PacketPayload.Ack original = new PacketPayload.Ack(sequences);

            byte[] bytes = original.toBytes();
            PacketPayload.Ack deserialized = PacketPayload.Ack.fromBytes(bytes);

            assertEquals(sequences.size(), deserialized.acknowledgedSequences().size());
            for (int i = 0; i < sequences.size(); i++) {
                assertEquals(sequences.get(i), deserialized.acknowledgedSequences().get(i));
            }
        }

        @Test
        @DisplayName("Should reject count exceeding max")
        void testMaxCountValidation() {
            byte[] bytes = new byte[4];
            bytes[0] = (byte) (PacketPayload.MAX_PACKET_COUNT + 1);
            bytes[1] = 0;
            bytes[2] = 0;
            bytes[3] = 0;

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.Ack.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("ACK packet count"));
            assertTrue(exception.getMessage().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should handle empty ACK list")
        void testEmptyAckList() {
            PacketPayload.Ack original = new PacketPayload.Ack(List.of());

            byte[] bytes = original.toBytes();
            PacketPayload.Ack deserialized = PacketPayload.Ack.fromBytes(bytes);

            assertEquals(0, deserialized.acknowledgedSequences().size());
        }

        @Test
        @DisplayName("Should handle buffer underflow on count")
        void testBufferUnderflowCount() {
            byte[] insufficientBytes = new byte[2];

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.Ack.fromBytes(insufficientBytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
            assertTrue(exception.getMessage().contains("ACK count"));
        }

        @Test
        @DisplayName("Should handle buffer underflow on sequences")
        void testBufferUnderflowSequences() {
            byte[] bytes = new byte[6];
            bytes[0] = 2; // Count = 2, but only 2 bytes remaining (need 4)

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PacketPayload.Ack.fromBytes(bytes)
            );

            assertTrue(exception.getMessage().contains("Buffer underflow"));
            assertTrue(exception.getMessage().contains("sequence numbers"));
        }
    }

    @Nested
    @DisplayName("GamePacket Tests")
    class GamePacketTests {

        @Test
        @DisplayName("Should serialize and deserialize correctly")
        void testSerializationRoundTrip() {
            byte[] payload = new byte[]{1, 2, 3, 4, 5, 0x7F, (byte) 0xFF};
            PacketPayload.GamePacket original = new PacketPayload.GamePacket(payload);

            byte[] bytes = original.toBytes();
            PacketPayload.GamePacket deserialized = PacketPayload.GamePacket.fromBytes(bytes);

            assertArrayEquals(payload, deserialized.payload());
        }

        @Test
        @DisplayName("Should handle empty payload")
        void testEmptyPayload() {
            PacketPayload.GamePacket original = new PacketPayload.GamePacket(new byte[0]);

            byte[] bytes = original.toBytes();
            PacketPayload.GamePacket deserialized = PacketPayload.GamePacket.fromBytes(bytes);

            assertEquals(0, deserialized.payload().length);
        }

        @Test
        @DisplayName("Should handle large payload")
        void testLargePayload() {
            byte[] largePayload = new byte[10000];
            for (int i = 0; i < largePayload.length; i++) {
                largePayload[i] = (byte) (i % 256);
            }

            PacketPayload.GamePacket original = new PacketPayload.GamePacket(largePayload);

            byte[] bytes = original.toBytes();
            PacketPayload.GamePacket deserialized = PacketPayload.GamePacket.fromBytes(bytes);

            assertArrayEquals(largePayload, deserialized.payload());
        }
    }

    @Nested
    @DisplayName("Utility Method Tests")
    class UtilityMethodTests {

        @Test
        @DisplayName("Should sanitize null string to empty")
        void testSanitizeNull() {
            String result = PacketPayload.sanitizeString(null);
            assertEquals("", result);
        }

        @Test
        @DisplayName("Should remove control characters")
        void testSanitizeControlCharacters() {
            String input = "Hello\u0000World\u0001Test\u001F";
            String result = PacketPayload.sanitizeString(input);
            assertEquals("HelloWorldTest", result);
        }

        @Test
        @DisplayName("Should preserve valid characters")
        void testSanitizePreservesValid() {
            String input = "Player123 (Pro)";
            String result = PacketPayload.sanitizeString(input);
            assertEquals("Player123 (Pro)", result);
        }

        @Test
        @DisplayName("Should trim whitespace")
        void testSanitizeTrimsWhitespace() {
            String input = "  Player  ";
            String result = PacketPayload.sanitizeString(input);
            assertEquals("Player", result);
        }

        @Test
        @DisplayName("Should preserve newlines and tabs")
        void testSanitizePreservesNewlinesAndTabs() {
            String input = "Line1\nLine2\tTabbed";
            String result = PacketPayload.sanitizeString(input);
            assertTrue(result.contains("\n"));
            assertTrue(result.contains("\t"));
        }
    }
}
