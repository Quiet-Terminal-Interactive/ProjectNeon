package com.quietterminal.neon.core;

import java.util.Arrays;

/**
 * A fully parsed Neon protocol packet consisting of a fixed 8-byte {@link PacketHeader}
 * followed by a variable-length {@link PacketPayload}.
 */
public record NeonPacket(PacketHeader header, PacketPayload payload) {

    public byte[] toBytes() {
        byte[] headerBytes = header.toBytes();
        byte[] payloadBytes = payload.toBytes();
        byte[] result = new byte[headerBytes.length + payloadBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(payloadBytes, 0, result, headerBytes.length, payloadBytes.length);
        return result;
    }

    public static NeonPacket fromBytes(byte[] data) {
        if (data.length < PacketHeader.HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too short: " + data.length);
        }
        PacketHeader header = PacketHeader.fromBytes(data);
        byte[] payloadBytes = Arrays.copyOfRange(data, PacketHeader.HEADER_SIZE, data.length);

        PayloadDeserializer custom = PayloadRegistry.getDeserializer(header.packetType());
        if (custom != null) {
            return new NeonPacket(header, custom.fromBytes(payloadBytes));
        }

        PacketPayload payload = switch (PacketType.fromByte(header.packetType())) {
            case CONNECT_REQUEST -> PacketPayload.ConnectRequest.fromBytes(payloadBytes);
            case CONNECT_ACCEPT -> PacketPayload.ConnectAccept.fromBytes(payloadBytes);
            case CONNECT_DENY -> PacketPayload.ConnectDeny.fromBytes(payloadBytes);
            case SESSION_CONFIG -> PacketPayload.SessionConfig.fromBytes(payloadBytes);
            case PACKET_TYPE_REGISTRY -> PacketPayload.PacketTypeRegistry.fromBytes(payloadBytes);
            case HOST_REGISTER -> PacketPayload.HostRegister.fromBytes(payloadBytes);
            case PING -> PacketPayload.Ping.fromBytes(payloadBytes);
            case PONG -> PacketPayload.Pong.fromBytes(payloadBytes);
            case DISCONNECT_NOTICE -> PacketPayload.DisconnectNotice.fromBytes(payloadBytes);
            case ACK -> PacketPayload.Ack.fromBytes(payloadBytes);
            case RECONNECT_REQUEST -> PacketPayload.ReconnectRequest.fromBytes(payloadBytes);
            case GAME_PACKET -> new PacketPayload.GamePacket(payloadBytes);
        };

        return new NeonPacket(header, payload);
    }

    public static NeonPacket create(PacketType type, short sequence, byte clientId, byte destId,
            PacketPayload payload) {
        return new NeonPacket(PacketHeader.create(type.getValue(), sequence, clientId, destId), payload);
    }
}
