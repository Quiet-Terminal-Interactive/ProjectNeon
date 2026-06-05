package com.quietterminal.neon.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Sealed-like marker interface for Neon packet payloads.
 *
 * <p>Each implementing record corresponds to a specific {@link PacketType}. Use Java pattern
 * matching ({@code instanceof} or {@code switch}) to dispatch on the concrete type.
 * Game-specific payloads should implement this interface indirectly via {@link GamePacket}.
 */
public interface PacketPayload {

    int MAX_NAME_LENGTH = 64;
    int MAX_DESCRIPTION_LENGTH = 256;
    int MAX_PACKET_COUNT = 100;

    byte[] toBytes();

    static String sanitizeString(String s) {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127)
                sb.append(c);
        }
        return sb.toString().trim();
    }

    record ConnectRequest(byte clientVersion, String name, int sessionId, int gameId) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + nameBytes.length + 4 + 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buf.put(clientVersion);
            buf.putShort((short) nameBytes.length);
            buf.put(nameBytes);
            buf.putInt(sessionId);
            buf.putInt(gameId);
            return buf.array();
        }

        public static ConnectRequest fromBytes(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 3)
                throw new IllegalArgumentException("ConnectRequest too short");
            byte version = buf.get();
            int nameLen = Short.toUnsignedInt(buf.getShort());
            if (nameLen < 1 || nameLen > MAX_NAME_LENGTH)
                throw new IllegalArgumentException("Invalid name length: " + nameLen);
            if (buf.remaining() < nameLen)
                throw new IllegalArgumentException("ConnectRequest truncated at name");
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            if (buf.remaining() < 8)
                throw new IllegalArgumentException("ConnectRequest truncated at ids");
            int sessionId = buf.getInt();
            int gameId = buf.getInt();
            return new ConnectRequest(version, new String(nameBytes, StandardCharsets.UTF_8), sessionId, gameId);
        }
    }

    record ConnectAccept(byte clientId, int sessionId, long token) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(clientId);
            buf.putInt(sessionId);
            buf.putLong(token);
            return buf.array();
        }

        public static ConnectAccept fromBytes(byte[] data) {
            if (data.length < 13)
                throw new IllegalArgumentException("ConnectAccept too short: " + data.length);
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            byte clientId = buf.get();
            int sessionId = buf.getInt();
            long token = buf.getLong();
            return new ConnectAccept(clientId, sessionId, token);
        }
    }

    record ConnectDeny(String reason) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(2 + reasonBytes.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) reasonBytes.length);
            buf.put(reasonBytes);
            return buf.array();
        }

        public static ConnectDeny fromBytes(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 2)
                throw new IllegalArgumentException("ConnectDeny too short");
            int reasonLen = Short.toUnsignedInt(buf.getShort());
            if (reasonLen < 1 || reasonLen > MAX_DESCRIPTION_LENGTH)
                throw new IllegalArgumentException("Invalid reason length: " + reasonLen);
            if (buf.remaining() < reasonLen)
                throw new IllegalArgumentException("ConnectDeny truncated");
            byte[] reasonBytes = new byte[reasonLen];
            buf.get(reasonBytes);
            return new ConnectDeny(new String(reasonBytes, StandardCharsets.UTF_8));
        }
    }

    record SessionConfig(byte version, short tickRate, short maxPacketSize) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(version);
            buf.putShort(tickRate);
            buf.putShort(maxPacketSize);
            return buf.array();
        }

        public static SessionConfig fromBytes(byte[] data) {
            if (data.length < 5)
                throw new IllegalArgumentException("SessionConfig too short: " + data.length);
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            byte version = buf.get();
            short tickRate = buf.getShort();
            short maxPacketSize = buf.getShort();
            return new SessionConfig(version, tickRate, maxPacketSize);
        }
    }

    record PacketTypeEntry(byte packetId, String name, String description) {
        byte[] toBytes() {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] descBytes = description.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 1 + nameBytes.length + 1 + descBytes.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buf.put(packetId);
            buf.put((byte) nameBytes.length);
            buf.put(nameBytes);
            buf.put((byte) descBytes.length);
            buf.put(descBytes);
            return buf.array();
        }

        static PacketTypeEntry fromBuffer(ByteBuffer buf) {
            if (buf.remaining() < 3)
                throw new IllegalArgumentException("PacketTypeEntry too short");
            byte id = buf.get();
            int nameLen = buf.get() & 0xFF;
            if (nameLen > MAX_NAME_LENGTH)
                throw new IllegalArgumentException("PacketTypeEntry name too long: " + nameLen);
            if (buf.remaining() < nameLen)
                throw new IllegalArgumentException("PacketTypeEntry name truncated");
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            if (buf.remaining() < 1)
                throw new IllegalArgumentException("PacketTypeEntry truncated at descLen");
            int descLen = buf.get() & 0xFF;
            if (buf.remaining() < descLen)
                throw new IllegalArgumentException("PacketTypeEntry description truncated");
            byte[] descBytes = new byte[descLen];
            buf.get(descBytes);
            return new PacketTypeEntry(id, new String(nameBytes, StandardCharsets.UTF_8),
                    new String(descBytes, StandardCharsets.UTF_8));
        }

        public static PacketTypeEntry fromBytes(byte[] data) {
            return fromBuffer(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
        }
    }

    record PacketTypeRegistry(List<PacketTypeEntry> entries) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            List<byte[]> encoded = entries.stream().map(PacketTypeEntry::toBytes).toList();
            int totalSize = 2 + encoded.stream().mapToInt(b -> b.length).sum();
            ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) entries.size());
            for (byte[] entry : encoded)
                buf.put(entry);
            return buf.array();
        }

        public static PacketTypeRegistry fromBytes(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 2)
                throw new IllegalArgumentException("PacketTypeRegistry too short");
            int count = Short.toUnsignedInt(buf.getShort());
            if (count > MAX_PACKET_COUNT)
                throw new IllegalArgumentException("Too many packet type entries: " + count);
            List<PacketTypeEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                entries.add(PacketTypeEntry.fromBuffer(buf));
            return new PacketTypeRegistry(Collections.unmodifiableList(entries));
        }
    }

    record HostRegister(int sessionId, long hostToken) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(sessionId);
            buf.putLong(hostToken);
            return buf.array();
        }

        public static HostRegister fromBytes(byte[] data) {
            if (data.length < 12)
                throw new IllegalArgumentException("HostRegister too short: " + data.length);
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int sessionId = buf.getInt();
            long hostToken = buf.getLong();
            return new HostRegister(sessionId, hostToken);
        }
    }

    record Ping(long timestamp) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(timestamp);
            return buf.array();
        }

        public static Ping fromBytes(byte[] data) {
            if (data.length < 8)
                throw new IllegalArgumentException("Ping too short: " + data.length);
            return new Ping(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong());
        }
    }

    record Pong(long originalTimestamp) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(originalTimestamp);
            return buf.array();
        }

        public static Pong fromBytes(byte[] data) {
            if (data.length < 8)
                throw new IllegalArgumentException("Pong too short: " + data.length);
            return new Pong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong());
        }
    }

    record DisconnectNotice() implements PacketPayload {
        @Override
        public byte[] toBytes() {
            return new byte[0];
        }

        public static DisconnectNotice fromBytes(byte[] data) {
            return new DisconnectNotice();
        }
    }

    record Ack(List<Short> sequences) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(2 + 2 * sequences.size()).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) sequences.size());
            for (short seq : sequences)
                buf.putShort(seq);
            return buf.array();
        }

        public static Ack fromBytes(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 2)
                throw new IllegalArgumentException("Ack too short");
            int count = Short.toUnsignedInt(buf.getShort());
            if (count > MAX_PACKET_COUNT)
                throw new IllegalArgumentException("Too many ack sequences: " + count);
            if (buf.remaining() < count * 2)
                throw new IllegalArgumentException("Ack truncated");
            List<Short> sequences = new ArrayList<>(count);
            for (int i = 0; i < count; i++)
                sequences.add(buf.getShort());
            return new Ack(Collections.unmodifiableList(sequences));
        }
    }

    record ReconnectRequest(long token, int sessionId, byte previousClientId) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(token);
            buf.putInt(sessionId);
            buf.put(previousClientId);
            return buf.array();
        }

        public static ReconnectRequest fromBytes(byte[] data) {
            if (data.length < 13)
                throw new IllegalArgumentException("ReconnectRequest too short: " + data.length);
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            long token = buf.getLong();
            int sessionId = buf.getInt();
            byte previousClientId = buf.get();
            return new ReconnectRequest(token, sessionId, previousClientId);
        }
    }

    record GamePacket(byte[] payload) implements PacketPayload {
        @Override
        public byte[] toBytes() {
            return payload.clone();
        }

        public static GamePacket fromBytes(byte[] data) {
            return new GamePacket(data.clone());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof GamePacket other))
                return false;
            return Arrays.equals(payload, other.payload);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(payload);
        }
    }
}
