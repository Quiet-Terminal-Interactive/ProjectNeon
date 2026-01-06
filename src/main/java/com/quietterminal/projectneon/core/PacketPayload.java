package com.quietterminal.projectneon.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Base interface for all packet payloads.
 */
public sealed interface PacketPayload permits
    PacketPayload.ConnectRequest,
    PacketPayload.ConnectAccept,
    PacketPayload.ConnectDeny,
    PacketPayload.SessionConfig,
    PacketPayload.PacketTypeRegistry,
    PacketPayload.Ping,
    PacketPayload.Pong,
    PacketPayload.DisconnectNotice,
    PacketPayload.Ack,
    PacketPayload.GamePacket {

    int MAX_NAME_LENGTH = 64;
    int MAX_DESCRIPTION_LENGTH = 256;
    int MAX_PACKET_COUNT = 100;
    int MAX_PAYLOAD_SIZE = 65507;

    byte[] toBytes();

    /**
     * Sanitizes a string by removing control characters and trimming whitespace.
     * Control characters (0x00-0x1F and 0x7F-0x9F) can cause issues in logs and displays.
     */
    static String sanitizeString(String input) {
        if (input == null) {
            return "";
        }
        // Remove control characters (0x00-0x1F and 0x7F-0x9F)
        String sanitized = input.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        // Trim whitespace
        return sanitized.trim();
    }

    record ConnectRequest(byte clientVersion, String desiredName, int targetSessionId, int gameIdentifier)
        implements PacketPayload {

        @Override
        public byte[] toBytes() {
            byte[] nameBytes = desiredName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + nameBytes.length + 4 + 4);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(clientVersion);
            buffer.putInt(nameBytes.length);
            buffer.put(nameBytes);
            buffer.putInt(targetSessionId);
            buffer.putInt(gameIdentifier);
            return buffer.array();
        }

        public static ConnectRequest fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 1) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for version");
            }
            byte version = buffer.get();
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for name length");
            }
            int nameLen = buffer.getInt();
            if (nameLen < 0 || nameLen > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("Name length " + nameLen + " exceeds maximum of " + MAX_NAME_LENGTH);
            }
            if (buffer.remaining() < nameLen) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for name");
            }
            byte[] nameBytes = new byte[nameLen];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            name = sanitizeString(name);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Client name cannot be empty after sanitization");
            }
            if (buffer.remaining() < 8) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for session and game IDs");
            }
            int sessionId = buffer.getInt();
            if (sessionId <= 0) {
                throw new IllegalArgumentException("Session ID must be a positive integer, got: " + sessionId);
            }
            int gameId = buffer.getInt();
            return new ConnectRequest(version, name, sessionId, gameId);
        }
    }

    record ConnectAccept(byte assignedClientId, int sessionId) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(1 + 4);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(assignedClientId);
            buffer.putInt(sessionId);
            return buffer.array();
        }

        public static ConnectAccept fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 5) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for ConnectAccept (expected 5 bytes)");
            }
            byte clientId = buffer.get();
            int sessionId = buffer.getInt();
            if (sessionId <= 0) {
                throw new IllegalArgumentException("Session ID must be a positive integer, got: " + sessionId);
            }
            return new ConnectAccept(clientId, sessionId);
        }
    }

    record ConnectDeny(String reason) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(4 + reasonBytes.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(reasonBytes.length);
            buffer.put(reasonBytes);
            return buffer.array();
        }

        public static ConnectDeny fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for reason length");
            }
            int len = buffer.getInt();
            if (len < 0 || len > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("Reason length " + len + " exceeds maximum of " + MAX_DESCRIPTION_LENGTH);
            }
            if (buffer.remaining() < len) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for reason");
            }
            byte[] reasonBytes = new byte[len];
            buffer.get(reasonBytes);
            String reason = new String(reasonBytes, StandardCharsets.UTF_8);
            return new ConnectDeny(reason);
        }
    }

    record SessionConfig(byte version, short tickRate, short maxPacketSize) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + 2);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(version);
            buffer.putShort(tickRate);
            buffer.putShort(maxPacketSize);
            return buffer.array();
        }

        public static SessionConfig fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 5) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for SessionConfig (expected 5 bytes)");
            }
            byte version = buffer.get();
            short tickRate = buffer.getShort();
            short maxPacketSize = buffer.getShort();
            return new SessionConfig(version, tickRate, maxPacketSize);
        }
    }

    record PacketTypeEntry(byte packetId, String name, String description) {
        public byte[] toBytes() {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] descBytes = description.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + nameBytes.length + 1 + descBytes.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(packetId);
            buffer.put((byte) nameBytes.length);
            buffer.put(nameBytes);
            buffer.put((byte) descBytes.length);
            buffer.put(descBytes);
            return buffer.array();
        }
    }

    record PacketTypeRegistry(List<PacketTypeEntry> entries) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            List<byte[]> entryBytes = new ArrayList<>();
            int totalSize = 4; // for entry count
            for (PacketTypeEntry entry : entries) {
                byte[] eb = entry.toBytes();
                entryBytes.add(eb);
                totalSize += eb.length;
            }
            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(entries.size());
            for (byte[] eb : entryBytes) {
                buffer.put(eb);
            }
            return buffer.array();
        }

        public static PacketTypeRegistry fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for entry count");
            }
            int count = buffer.getInt();
            if (count < 0 || count > MAX_PACKET_COUNT) {
                throw new IllegalArgumentException("Packet type count " + count + " exceeds maximum of " + MAX_PACKET_COUNT);
            }
            List<PacketTypeEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 1) {
                    throw new IllegalArgumentException("Buffer underflow: not enough bytes for packet ID");
                }
                byte packetId = buffer.get();
                if (buffer.remaining() < 1) {
                    throw new IllegalArgumentException("Buffer underflow: not enough bytes for name length");
                }
                int nameLen = buffer.get() & 0xFF;
                if (nameLen > MAX_NAME_LENGTH) {
                    throw new IllegalArgumentException("Packet type name length " + nameLen + " exceeds maximum of " + MAX_NAME_LENGTH);
                }
                if (buffer.remaining() < nameLen) {
                    throw new IllegalArgumentException("Buffer underflow: not enough bytes for name");
                }
                byte[] nameBytes = new byte[nameLen];
                buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                if (buffer.remaining() < 1) {
                    throw new IllegalArgumentException("Buffer underflow: not enough bytes for description length");
                }
                int descLen = buffer.get() & 0xFF;
                if (descLen > MAX_DESCRIPTION_LENGTH) {
                    throw new IllegalArgumentException("Packet type description length " + descLen + " exceeds maximum of " + MAX_DESCRIPTION_LENGTH);
                }
                if (buffer.remaining() < descLen) {
                    throw new IllegalArgumentException("Buffer underflow: not enough bytes for description");
                }
                byte[] descBytes = new byte[descLen];
                buffer.get(descBytes);
                String desc = new String(descBytes, StandardCharsets.UTF_8);
                entries.add(new PacketTypeEntry(packetId, name, desc));
            }
            return new PacketTypeRegistry(entries);
        }
    }

    record Ping(long timestamp) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(timestamp);
            return buffer.array();
        }

        public static Ping fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 8) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for Ping (expected 8 bytes)");
            }
            long timestamp = buffer.getLong();
            return new Ping(timestamp);
        }
    }

    record Pong(long originalTimestamp) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(originalTimestamp);
            return buffer.array();
        }

        public static Pong fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 8) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for Pong (expected 8 bytes)");
            }
            long timestamp = buffer.getLong();
            return new Pong(timestamp);
        }
    }

    record DisconnectNotice() implements PacketPayload {
        @Override
        public byte[] toBytes() {
            return new byte[0];
        }

        public static DisconnectNotice fromBytes(byte[] bytes) {
            return new DisconnectNotice();
        }
    }

    record Ack(List<Short> acknowledgedSequences) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(4 + acknowledgedSequences.size() * 2);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(acknowledgedSequences.size());
            for (Short seq : acknowledgedSequences) {
                buffer.putShort(seq);
            }
            return buffer.array();
        }

        public static Ack fromBytes(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for ACK count");
            }
            int count = buffer.getInt();
            if (count < 0 || count > MAX_PACKET_COUNT) {
                throw new IllegalArgumentException("ACK packet count " + count + " exceeds maximum of " + MAX_PACKET_COUNT);
            }
            if (buffer.remaining() < count * 2) {
                throw new IllegalArgumentException("Buffer underflow: not enough bytes for " + count + " sequence numbers");
            }
            List<Short> sequences = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                sequences.add(buffer.getShort());
            }
            return new Ack(sequences);
        }
    }

    record GamePacket(byte[] payload) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            return payload;
        }

        public static GamePacket fromBytes(byte[] bytes) {
            return new GamePacket(bytes);
        }
    }
}
