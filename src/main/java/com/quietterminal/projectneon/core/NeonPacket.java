package com.quietterminal.projectneon.core;

import java.util.Arrays;

/**
 * Complete Neon packet with header and payload.
 */
public record NeonPacket(PacketHeader header, PacketPayload payload) {

    /**
     * Serializes the entire packet to bytes.
     */
    public byte[] toBytes() {
        byte[] headerBytes = header.toBytes();
        byte[] payloadBytes = payload.toBytes();
        byte[] packet = new byte[headerBytes.length + payloadBytes.length];
        System.arraycopy(headerBytes, 0, packet, 0, headerBytes.length);
        System.arraycopy(payloadBytes, 0, packet, headerBytes.length, payloadBytes.length);
        return packet;
    }

    /**
     * Deserializes a packet from bytes.
     */
    public static NeonPacket fromBytes(byte[] bytes) {
        if (bytes.length < PacketHeader.HEADER_SIZE) {
            throw new IllegalArgumentException("Packet too small");
        }

        PacketHeader header = PacketHeader.fromBytes(bytes);
        byte[] payloadBytes = Arrays.copyOfRange(bytes, PacketHeader.HEADER_SIZE, bytes.length);

        PacketType type = PacketType.fromByte(header.packetType());
        PacketPayload payload = switch (type) {
            case CONNECT_REQUEST -> PacketPayload.ConnectRequest.fromBytes(payloadBytes);
            case CONNECT_ACCEPT -> PacketPayload.ConnectAccept.fromBytes(payloadBytes);
            case CONNECT_DENY -> PacketPayload.ConnectDeny.fromBytes(payloadBytes);
            case SESSION_CONFIG -> PacketPayload.SessionConfig.fromBytes(payloadBytes);
            case PACKET_TYPE_REGISTRY -> PacketPayload.PacketTypeRegistry.fromBytes(payloadBytes);
            case PING -> PacketPayload.Ping.fromBytes(payloadBytes);
            case PONG -> PacketPayload.Pong.fromBytes(payloadBytes);
            case DISCONNECT_NOTICE -> PacketPayload.DisconnectNotice.fromBytes(payloadBytes);
            case ACK -> PacketPayload.Ack.fromBytes(payloadBytes);
            case GAME_PACKET -> PacketPayload.GamePacket.fromBytes(payloadBytes);
        };

        return new NeonPacket(header, payload);
    }

    /**
     * Creates a new packet with automatic header creation.
     */
    public static NeonPacket create(PacketType type, short sequence, byte clientId, byte destinationId, PacketPayload payload) {
        PacketHeader header = PacketHeader.create(type.getValue(), sequence, clientId, destinationId);
        return new NeonPacket(header, payload);
    }

    @Override
    public String toString() {
        return String.format("NeonPacket[%s, payload=%s]", header, payload.getClass().getSimpleName());
    }
}
