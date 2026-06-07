import { describe, it, expect } from "vitest";
import {
  MAGIC,
  VERSION,
  HEADER_SIZE,
  PacketType,
  packetTypeFromByte,
  signed16,
  buildHeader,
  serializeHeader,
  parseHeader,
  serializePacket,
  parsePacket,
  makePacket,
  isConnectRequest,
  isConnectAccept,
  isConnectDeny,
  isSessionConfig,
  isPacketTypeRegistry,
  isHostRegister,
  isPing,
  isPong,
  isDisconnectNotice,
  isAck,
  isReconnectRequest,
  isGamePacket,
  ConnectRequestPayload,
  ConnectAcceptPayload,
  ConnectDenyPayload,
  SessionConfigPayload,
  HostRegisterPayload,
  PacketTypeRegistryPayload,
  PingPayload,
  PongPayload,
  AckPayload,
  ReconnectRequestPayload,
  DisconnectNoticePayload,
  GamePacketPayload,
} from "../src/_protocol";

describe("constants", () => {
  it("MAGIC is 0x4E45", () => expect(MAGIC).toBe(0x4e45));
  it("VERSION is 0x01", () => expect(VERSION).toBe(0x01));
  it("HEADER_SIZE is 8", () => expect(HEADER_SIZE).toBe(8));
});

describe("packetTypeFromByte", () => {
  it("maps known types", () => {
    expect(packetTypeFromByte(0x01)).toBe(PacketType.CONNECT_REQUEST);
    expect(packetTypeFromByte(0x06)).toBe(PacketType.HOST_REGISTER);
    expect(packetTypeFromByte(0x0f)).toBe(PacketType.RECONNECT_REQUEST);
  });
  it("maps anything >= 0x10 to GAME_PACKET", () => {
    expect(packetTypeFromByte(0x10)).toBe(PacketType.GAME_PACKET);
    expect(packetTypeFromByte(0xff)).toBe(PacketType.GAME_PACKET);
    expect(packetTypeFromByte(0x42)).toBe(PacketType.GAME_PACKET);
  });
  it("throws on unknown reserved type", () => {
    expect(() => packetTypeFromByte(0x09)).toThrow();
  });
});

describe("signed16", () => {
  it("handles positive values", () => expect(signed16(5)).toBe(5));
  it("handles zero", () => expect(signed16(0)).toBe(0));
  it("handles wrap-around boundary", () => {
    expect(signed16(0x7fff)).toBe(32767);
    expect(signed16(0x8000)).toBe(-32768);
    expect(signed16(0xffff)).toBe(-1);
  });
  it("duplicate detection: newer - older > 0", () => {
    expect(signed16(10 - 5)).toBeGreaterThan(0);
  });
  it("duplicate detection: 1 - 65535 wraps correctly (1 is newer)", () => {
    expect(signed16(1 - 65535)).toBeGreaterThan(0);
  });
  it("duplicate detection: same seq is not newer", () => {
    expect(signed16(5 - 5)).toBeLessThanOrEqual(0);
  });
});

describe("PacketHeader serialization", () => {
  it("round-trips", () => {
    const h = buildHeader(PacketType.CONNECT_REQUEST, 42, 2, 1);
    const buf = serializeHeader(h);
    expect(buf.length).toBe(HEADER_SIZE);
    const parsed = parseHeader(buf);
    expect(parsed.magic).toBe(MAGIC);
    expect(parsed.version).toBe(VERSION);
    expect(parsed.packetType).toBe(PacketType.CONNECT_REQUEST);
    expect(parsed.sequence).toBe(42);
    expect(parsed.clientId).toBe(2);
    expect(parsed.destinationId).toBe(1);
  });

  it("masks sequence to 16 bits", () => {
    const h = buildHeader(0x01, 0x10042, 1, 1);
    const buf = serializeHeader(h);
    expect(parseHeader(buf).sequence).toBe(0x0042);
  });

  it("rejects invalid magic", () => {
    const buf = Buffer.alloc(8);
    buf.writeUInt16LE(0x0000, 0);
    expect(() => parseHeader(buf)).toThrow(/magic/i);
  });

  it("rejects short buffers", () => {
    expect(() => parseHeader(Buffer.alloc(4))).toThrow();
  });

  it("is little-endian", () => {
    const h = buildHeader(0x01, 0x0201, 0, 0);
    const buf = serializeHeader(h);
    // magic: 0x4E at offset 0, 0x45 at offset 1 (LE: low byte first)
    expect(buf[0]).toBe(0x45);
    expect(buf[1]).toBe(0x4e);
    // sequence 0x0201: 0x01 at offset 4, 0x02 at offset 5
    expect(buf[4]).toBe(0x01);
    expect(buf[5]).toBe(0x02);
  });
});

describe("ConnectRequest", () => {
  it("round-trips", () => {
    const payload: ConnectRequestPayload = { clientVersion: 1, name: "alice", sessionId: 42, gameId: 7 };
    const pkt = makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, payload);
    const buf = serializePacket(pkt);
    const parsed = parsePacket(buf);
    expect(isConnectRequest(parsed.payload)).toBe(true);
    const p = parsed.payload as ConnectRequestPayload;
    expect(p.clientVersion).toBe(1);
    expect(p.name).toBe("alice");
    expect(p.sessionId).toBe(42);
    expect(p.gameId).toBe(7);
  });

  it("handles UTF-8 names", () => {
    const payload: ConnectRequestPayload = { clientVersion: 1, name: "プレイヤー", sessionId: 1, gameId: 0 };
    const pkt = makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect((parsed.payload as ConnectRequestPayload).name).toBe("プレイヤー");
  });
});

describe("ConnectAccept", () => {
  it("round-trips with large token", () => {
    const token = 0xdeadbeefcafebaben;
    const payload: ConnectAcceptPayload = { clientId: 5, sessionId: 100, token };
    const pkt = makePacket(PacketType.CONNECT_ACCEPT, 1, 1, 5, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isConnectAccept(parsed.payload)).toBe(true);
    const p = parsed.payload as ConnectAcceptPayload;
    expect(p.clientId).toBe(5);
    expect(p.sessionId).toBe(100);
    expect(p.token).toBe(token);
  });
});

describe("ConnectDeny", () => {
  it("round-trips", () => {
    const payload: ConnectDenyPayload = { reason: "Name taken" };
    const pkt = makePacket(PacketType.CONNECT_DENY, 0, 0, 0, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isConnectDeny(parsed.payload)).toBe(true);
    expect((parsed.payload as ConnectDenyPayload).reason).toBe("Name taken");
  });
});

describe("SessionConfig", () => {
  it("round-trips", () => {
    const payload: SessionConfigPayload = { version: 1, tickRate: 60, maxPacketSize: 1200 };
    const pkt = makePacket(PacketType.SESSION_CONFIG, 0, 1, 2, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isSessionConfig(parsed.payload)).toBe(true);
    const p = parsed.payload as SessionConfigPayload;
    expect(p.tickRate).toBe(60);
    expect(p.maxPacketSize).toBe(1200);
  });
});

describe("PacketTypeRegistry", () => {
  it("round-trips with entries", () => {
    const payload: PacketTypeRegistryPayload = {
      entries: [
        { packetId: 0x10, name: "POSITION", description: "Player position" },
        { packetId: 0x11, name: "CHAT", description: "" },
      ],
    };
    const pkt = makePacket(PacketType.PACKET_TYPE_REGISTRY, 0, 1, 2, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isPacketTypeRegistry(parsed.payload)).toBe(true);
    const p = parsed.payload as PacketTypeRegistryPayload;
    expect(p.entries).toHaveLength(2);
    expect(p.entries[0].name).toBe("POSITION");
    expect(p.entries[1].description).toBe("");
  });

  it("round-trips empty registry", () => {
    const payload: PacketTypeRegistryPayload = { entries: [] };
    const pkt = makePacket(PacketType.PACKET_TYPE_REGISTRY, 0, 1, 2, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect((parsed.payload as PacketTypeRegistryPayload).entries).toHaveLength(0);
  });
});

describe("HostRegister", () => {
  it("round-trips", () => {
    const payload: HostRegisterPayload = { sessionId: 99, hostToken: 0x1122334455667788n };
    const pkt = makePacket(PacketType.HOST_REGISTER, 0, 1, 0, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isHostRegister(parsed.payload)).toBe(true);
    const p = parsed.payload as HostRegisterPayload;
    expect(p.sessionId).toBe(99);
    expect(p.hostToken).toBe(0x1122334455667788n);
  });
});

describe("Ping / Pong", () => {
  it("Ping round-trips", () => {
    const payload: PingPayload = { timestamp: BigInt(Date.now()) };
    const pkt = makePacket(PacketType.PING, 0, 2, 1, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isPing(parsed.payload)).toBe(true);
    expect((parsed.payload as PingPayload).timestamp).toBe(payload.timestamp);
  });

  it("Pong round-trips", () => {
    const payload: PongPayload = { originalTimestamp: 999999n };
    const pkt = makePacket(PacketType.PONG, 0, 1, 2, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isPong(parsed.payload)).toBe(true);
    expect((parsed.payload as PongPayload).originalTimestamp).toBe(999999n);
  });
});

describe("DisconnectNotice", () => {
  it("round-trips (empty payload)", () => {
    const payload: DisconnectNoticePayload = { _tag: "disconnect" };
    const pkt = makePacket(PacketType.DISCONNECT_NOTICE, 0, 2, 0, payload);
    const buf = serializePacket(pkt);
    expect(buf.length).toBe(HEADER_SIZE); // no payload bytes
    const parsed = parsePacket(buf);
    expect(isDisconnectNotice(parsed.payload)).toBe(true);
  });
});

describe("Ack", () => {
  it("round-trips single sequence", () => {
    const payload: AckPayload = { sequences: [7] };
    const pkt = makePacket(PacketType.ACK, 0, 2, 0, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isAck(parsed.payload)).toBe(true);
    expect((parsed.payload as AckPayload).sequences).toEqual([7]);
  });

  it("round-trips multiple sequences", () => {
    const payload: AckPayload = { sequences: [1, 2, 65535] };
    const pkt = makePacket(PacketType.ACK, 0, 2, 0, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect((parsed.payload as AckPayload).sequences).toEqual([1, 2, 65535]);
  });
});

describe("ReconnectRequest", () => {
  it("round-trips", () => {
    const payload: ReconnectRequestPayload = { token: 0xaabbccddff001122n, sessionId: 42, previousClientId: 3 };
    const pkt = makePacket(PacketType.RECONNECT_REQUEST, 0, 3, 1, payload);
    const parsed = parsePacket(serializePacket(pkt));
    expect(isReconnectRequest(parsed.payload)).toBe(true);
    const p = parsed.payload as ReconnectRequestPayload;
    expect(p.token).toBe(0xaabbccddff001122n);
    expect(p.sessionId).toBe(42);
    expect(p.previousClientId).toBe(3);
  });
});

describe("GamePacket", () => {
  it("round-trips (type 0x10)", () => {
    const payload: GamePacketPayload = { rawByte: 0x10, payload: Buffer.from([0x01, 0x02, 0x03]) };
    const pkt = makePacket(PacketType.GAME_PACKET, 0, 2, 0, payload);
    // set real type byte for serialization
    const realPkt = { header: { ...pkt.header, packetType: 0x10 }, payload };
    const buf = serializePacket(realPkt);
    const parsed = parsePacket(buf);
    expect(isGamePacket(parsed.payload)).toBe(true);
    const p = parsed.payload as GamePacketPayload;
    expect(p.rawByte).toBe(0x10);
    expect([...p.payload]).toEqual([0x01, 0x02, 0x03]);
  });
});

describe("full packet interop checks", () => {
  it("Java/Python interop: packet wire bytes match expected", () => {
    // A CONNECT_REQUEST for session 42, gameId 0, name "bob", clientVersion 1
    // Expected bytes (little-endian):
    //   Header: 45 4E 01 01 00 00 00 01
    //   Payload: 01 03 00 62 6F 62 2A 00 00 00 00 00 00 00
    const payload: ConnectRequestPayload = { clientVersion: 1, name: "bob", sessionId: 42, gameId: 0 };
    const pkt = makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, payload);
    const buf = serializePacket(pkt);
    // magic LE: 0x45, 0x4E
    expect(buf[0]).toBe(0x45);
    expect(buf[1]).toBe(0x4e);
    // version
    expect(buf[2]).toBe(0x01);
    // type
    expect(buf[3]).toBe(0x01);
    // sequence LE: 0x00, 0x00
    expect(buf[4]).toBe(0x00);
    expect(buf[5]).toBe(0x00);
    // clientId = 0
    expect(buf[6]).toBe(0x00);
    // destId = 1
    expect(buf[7]).toBe(0x01);
    // payload: clientVersion=1, nameLen=3 LE, "bob"=62 6F 62, sessionId=42 LE, gameId=0 LE
    expect(buf[8]).toBe(0x01);
    expect(buf[9]).toBe(0x03);
    expect(buf[10]).toBe(0x00);
    expect(buf[11]).toBe(0x62); // 'b'
    expect(buf[12]).toBe(0x6f); // 'o'
    expect(buf[13]).toBe(0x62); // 'b'
    expect(buf[14]).toBe(0x2a); // 42 LE low
    expect(buf[15]).toBe(0x00);
    expect(buf[16]).toBe(0x00);
    expect(buf[17]).toBe(0x00);
  });
});
