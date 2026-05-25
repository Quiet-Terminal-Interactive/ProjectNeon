package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NeonPacketTest {

    private static NeonPacket roundTrip(NeonPacket packet) {
        return NeonPacket.fromBytes(packet.toBytes());
    }

    @Test
    void connectRequestRoundTrip() {
        var payload = new PacketPayload.ConnectRequest((byte) 1, "Alice", 10, 0);
        var packet = NeonPacket.create(PacketType.CONNECT_REQUEST, (short) 1, (byte) 0, (byte) 1, payload);
        var restored = roundTrip(packet);
        assertEquals(packet.header(), restored.header());
        assertEquals(payload, restored.payload());
    }

    @Test
    void connectAcceptRoundTrip() {
        var payload = new PacketPayload.ConnectAccept((byte) 2, 10, 0xABCDEFL);
        var packet = NeonPacket.create(PacketType.CONNECT_ACCEPT, (short) 2, (byte) 1, (byte) 2, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void connectDenyRoundTrip() {
        var payload = new PacketPayload.ConnectDeny("Name taken");
        var packet = NeonPacket.create(PacketType.CONNECT_DENY, (short) 0, (byte) 1, (byte) 0, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void sessionConfigRoundTrip() {
        var payload = new PacketPayload.SessionConfig((byte) 1, (short) 60, (short) 1200);
        var packet = NeonPacket.create(PacketType.SESSION_CONFIG, (short) 3, (byte) 1, (byte) 2, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void packetTypeRegistryRoundTrip() {
        var payload = new PacketPayload.PacketTypeRegistry(List.of(
                new PacketPayload.PacketTypeEntry((byte) 0x10, "Move", "Movement packet")));
        var packet = NeonPacket.create(PacketType.PACKET_TYPE_REGISTRY, (short) 4, (byte) 1, (byte) 2, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void hostRegisterRoundTrip() {
        var payload = new PacketPayload.HostRegister(42, 0xDEADC0DEL);
        var packet = NeonPacket.create(PacketType.HOST_REGISTER, (short) 0, (byte) 1, (byte) 0, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void pingRoundTrip() {
        var payload = new PacketPayload.Ping(System.currentTimeMillis());
        var packet = NeonPacket.create(PacketType.PING, (short) 5, (byte) 2, (byte) 1, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void pongRoundTrip() {
        var payload = new PacketPayload.Pong(System.currentTimeMillis() - 50);
        var packet = NeonPacket.create(PacketType.PONG, (short) 6, (byte) 1, (byte) 2, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void disconnectNoticeRoundTrip() {
        var payload = new PacketPayload.DisconnectNotice();
        var packet = NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) 7, (byte) 2, (byte) 0, payload);
        var restored = roundTrip(packet);
        assertEquals(payload, restored.payload());
        assertInstanceOf(PacketPayload.DisconnectNotice.class, restored.payload());
    }

    @Test
    void ackRoundTrip() {
        var payload = new PacketPayload.Ack(List.of((short) 10, (short) 11, (short) 12));
        var packet = NeonPacket.create(PacketType.ACK, (short) 8, (byte) 2, (byte) 1, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void reconnectRequestRoundTrip() {
        var payload = new PacketPayload.ReconnectRequest(0xBEEFL, 99, (byte) 2);
        var packet = NeonPacket.create(PacketType.RECONNECT_REQUEST, (short) 9, (byte) 0, (byte) 1, payload);
        assertEquals(payload, roundTrip(packet).payload());
    }

    @Test
    void gamePacketRoundTrip() {
        byte[] data = { 0x10, 0x20, 0x30 };
        var payload = new PacketPayload.GamePacket(data);
        var packet = NeonPacket.create(PacketType.GAME_PACKET, (short) 10, (byte) 2, (byte) 0, payload);
        var restored = roundTrip(packet);
        assertInstanceOf(PacketPayload.GamePacket.class, restored.payload());
        assertEquals(payload, restored.payload());
    }

    @Test
    void tooShortThrows() {
        assertThrows(IllegalArgumentException.class, () -> NeonPacket.fromBytes(new byte[7]));
    }

    @Test
    void headerAndPayloadConcatenatedCorrectly() {
        var payload = new PacketPayload.Ping(0L);
        var packet = NeonPacket.create(PacketType.PING, (short) 0, (byte) 1, (byte) 0, payload);
        byte[] bytes = packet.toBytes();
        assertEquals(PacketHeader.HEADER_SIZE + 8, bytes.length);
    }

    @Test
    void sequenceWrapsAroundCorrectly() {
        var payload = new PacketPayload.DisconnectNotice();
        var packet = NeonPacket.create(PacketType.DISCONNECT_NOTICE, (short) -1, (byte) 1, (byte) 0, payload);
        assertEquals((short) -1, roundTrip(packet).header().sequence());
    }
}
