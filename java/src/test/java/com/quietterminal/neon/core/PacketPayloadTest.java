package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketPayloadTest {

    @Test
    void connectRequestRoundTrip() {
        var original = new PacketPayload.ConnectRequest((byte) 1, "Player1", 42, 99);
        var restored = PacketPayload.ConnectRequest.fromBytes(original.toBytes());
        assertEquals(original, restored);
    }

    @Test
    void connectRequestRejectsEmptyName() {
        ByteBuffer buf = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1); // version
        buf.putShort((short) 0); // nameLen = 0
        buf.putInt(1); // sessionId
        buf.putInt(0); // gameId
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(buf.array()));
    }

    @Test
    void connectRequestRejectsNameTooLong() {
        int nameLen = PacketPayload.MAX_NAME_LENGTH + 1;
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + nameLen + 8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 1);
        buf.putShort((short) nameLen);
        buf.put(new byte[nameLen]);
        buf.putInt(1);
        buf.putInt(0);
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(buf.array()));
    }

    @Test
    void connectRequestRejectsTruncatedBuffer() {
        byte[] full = new PacketPayload.ConnectRequest((byte) 1, "test", 1, 0).toBytes();
        byte[] truncated = Arrays.copyOf(full, full.length / 2);
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.ConnectRequest.fromBytes(truncated));
    }

    @Test
    void connectRequestNameLengthIsShortOnWire() {
        var req = new PacketPayload.ConnectRequest((byte) 1, "AB", 1, 0);
        byte[] bytes = req.toBytes();
        // bytes[0]=version, bytes[1..2]=nameLen(LE short), bytes[3..4]="AB"
        assertEquals((byte) 1, bytes[0]);
        assertEquals((byte) 2, bytes[1]); // nameLen low byte = 2
        assertEquals((byte) 0, bytes[2]); // nameLen high byte = 0
    }

    @Test
    void connectAcceptRoundTrip() {
        var original = new PacketPayload.ConnectAccept((byte) 3, 100, 0xDEADBEEFCAFEL);
        assertEquals(original, PacketPayload.ConnectAccept.fromBytes(original.toBytes()));
    }

    @Test
    void connectAcceptRejectsTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.ConnectAccept.fromBytes(new byte[12]));
    }

    @Test
    void connectDenyRoundTrip() {
        var original = new PacketPayload.ConnectDeny("Session full");
        assertEquals(original, PacketPayload.ConnectDeny.fromBytes(original.toBytes()));
    }

    @Test
    void connectDenyRejectsEmptyReason() {
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.ConnectDeny.fromBytes(buf.array()));
    }

    @Test
    void connectDenyReasonLengthIsShortOnWire() {
        var deny = new PacketPayload.ConnectDeny("Hi");
        byte[] bytes = deny.toBytes();
        assertEquals((byte) 2, bytes[0]); // reasonLen low byte
        assertEquals((byte) 0, bytes[1]); // reasonLen high byte
    }

    @Test
    void sessionConfigRoundTrip() {
        var original = new PacketPayload.SessionConfig((byte) 1, (short) 60, (short) 1200);
        assertEquals(original, PacketPayload.SessionConfig.fromBytes(original.toBytes()));
    }

    @Test
    void sessionConfigRejectsTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.SessionConfig.fromBytes(new byte[4]));
    }

    @Test
    void packetTypeRegistryRoundTrip() {
        var entries = List.of(
                new PacketPayload.PacketTypeEntry((byte) 0x10, "Move", "Player movement"),
                new PacketPayload.PacketTypeEntry((byte) 0x11, "Fire", "Shoot action"));
        var original = new PacketPayload.PacketTypeRegistry(entries);
        assertEquals(original, PacketPayload.PacketTypeRegistry.fromBytes(original.toBytes()));
    }

    @Test
    void packetTypeRegistryEmptyEntriesRoundTrip() {
        var original = new PacketPayload.PacketTypeRegistry(List.of());
        assertEquals(original, PacketPayload.PacketTypeRegistry.fromBytes(original.toBytes()));
    }

    @Test
    void packetTypeRegistryEntryCountIsShortOnWire() {
        var reg = new PacketPayload.PacketTypeRegistry(List.of(
                new PacketPayload.PacketTypeEntry((byte) 0x10, "X", "Y")));
        byte[] bytes = reg.toBytes();
        assertEquals((byte) 1, bytes[0]); // count low byte
        assertEquals((byte) 0, bytes[1]); // count high byte
    }

    @Test
    void packetTypeRegistryRejectsTooManyEntries() {
        int count = PacketPayload.MAX_PACKET_COUNT + 1;
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) count);
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.PacketTypeRegistry.fromBytes(buf.array()));
    }

    @Test
    void hostRegisterRoundTrip() {
        var original = new PacketPayload.HostRegister(77, Long.MIN_VALUE);
        assertEquals(original, PacketPayload.HostRegister.fromBytes(original.toBytes()));
    }

    @Test
    void hostRegisterRejectsTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.HostRegister.fromBytes(new byte[11]));
    }

    @Test
    void pingRoundTrip() {
        var original = new PacketPayload.Ping(System.currentTimeMillis());
        assertEquals(original, PacketPayload.Ping.fromBytes(original.toBytes()));
    }

    @Test
    void pongRoundTrip() {
        var original = new PacketPayload.Pong(System.currentTimeMillis() - 100);
        assertEquals(original, PacketPayload.Pong.fromBytes(original.toBytes()));
    }

    @Test
    void pingRejectsTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.Ping.fromBytes(new byte[7]));
    }

    @Test
    void disconnectNoticeRoundTrip() {
        var original = new PacketPayload.DisconnectNotice();
        var restored = PacketPayload.DisconnectNotice.fromBytes(original.toBytes());
        assertEquals(original, restored);
        assertEquals(0, original.toBytes().length);
    }

    @Test
    void ackRoundTrip() {
        var original = new PacketPayload.Ack(List.of((short) 1, (short) 2, (short) 300));
        assertEquals(original, PacketPayload.Ack.fromBytes(original.toBytes()));
    }

    @Test
    void ackEmptySequencesRoundTrip() {
        var original = new PacketPayload.Ack(List.of());
        assertEquals(original, PacketPayload.Ack.fromBytes(original.toBytes()));
    }

    @Test
    void ackCountIsShortOnWire() {
        var ack = new PacketPayload.Ack(List.of((short) 7));
        byte[] bytes = ack.toBytes();
        assertEquals((byte) 1, bytes[0]); // count low byte
        assertEquals((byte) 0, bytes[1]); // count high byte
    }

    @Test
    void ackRejectsTooManySequences() {
        int count = PacketPayload.MAX_PACKET_COUNT + 1;
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) count);
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.Ack.fromBytes(buf.array()));
    }

    @Test
    void ackRejectsTruncatedSequences() {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 5); // claims 5 sequences
        buf.putShort((short) 1); // only 1 sequence present
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.Ack.fromBytes(buf.array()));
    }

    @Test
    void reconnectRequestRoundTrip() {
        var original = new PacketPayload.ReconnectRequest(0xCAFEBABEL, 55, (byte) 3);
        assertEquals(original, PacketPayload.ReconnectRequest.fromBytes(original.toBytes()));
    }

    @Test
    void reconnectRequestRejectsTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketPayload.ReconnectRequest.fromBytes(new byte[12]));
    }

    @Test
    void gamePacketRoundTrip() {
        byte[] data = { 0x01, 0x02, 0x03, (byte) 0xFF };
        var original = new PacketPayload.GamePacket(data);
        var restored = PacketPayload.GamePacket.fromBytes(original.toBytes());
        assertEquals(original, restored);
    }

    @Test
    void gamePacketEmptyPayload() {
        var original = new PacketPayload.GamePacket(new byte[0]);
        assertEquals(original, PacketPayload.GamePacket.fromBytes(original.toBytes()));
    }

    @Test
    void sanitizeStringStripsControlChars() {
        // DO NOT TOUCH UNLESS NECESSARY, FORMATTER CAN STRIP CONTROL CHARACTERS!
        assertEquals("helloworld", PacketPayload.sanitizeString("helloworld"));
    }

    @Test
    void sanitizeStringTrimsWhitespace() {
        assertEquals("hi", PacketPayload.sanitizeString("  hi  "));
    }

    @Test
    void sanitizeStringHandlesNull() {
        assertEquals("", PacketPayload.sanitizeString(null));
    }
}
