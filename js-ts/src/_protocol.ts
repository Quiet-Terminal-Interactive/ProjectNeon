export const MAGIC = 0x4e45;
export const VERSION = 0x01;
export const HEADER_SIZE = 8;

const MAX_NAME_LENGTH = 64;
const MAX_DESCRIPTION_LENGTH = 256;
const MAX_PACKET_COUNT = 100;

export function signed16(n: number): number {
  n = n & 0xffff;
  return n >= 0x8000 ? n - 0x10000 : n;
}

export enum PacketType {
  CONNECT_REQUEST = 0x01,
  CONNECT_ACCEPT = 0x02,
  CONNECT_DENY = 0x03,
  SESSION_CONFIG = 0x04,
  PACKET_TYPE_REGISTRY = 0x05,
  HOST_REGISTER = 0x06,
  PING = 0x0b,
  PONG = 0x0c,
  DISCONNECT_NOTICE = 0x0d,
  ACK = 0x0e,
  RECONNECT_REQUEST = 0x0f,
  GAME_PACKET = 0x10,
}

const CORE_TYPES = new Set<number>(Object.values(PacketType).filter((v) => typeof v === "number") as number[]);

export function packetTypeFromByte(b: number): PacketType {
  if (b >= 0x10) return PacketType.GAME_PACKET;
  if (CORE_TYPES.has(b)) return b as PacketType;
  throw new Error(`Unknown packet type: 0x${b.toString(16).padStart(2, "0")}`);
}

export function isDtlsRecord(firstByte: number): boolean {
  return (firstByte >= 0x14 && firstByte <= 0x17) || (firstByte >= 0x20 && firstByte <= 0x3f);
}

export interface PacketHeader {
  readonly magic: number;
  readonly version: number;
  readonly packetType: number;
  readonly sequence: number;
  readonly clientId: number;
  readonly destinationId: number;
}

export function buildHeader(
  packetType: number,
  sequence: number,
  clientId: number,
  destinationId: number
): PacketHeader {
  return {
    magic: MAGIC,
    version: VERSION,
    packetType,
    sequence: sequence & 0xffff,
    clientId: clientId & 0xff,
    destinationId: destinationId & 0xff,
  };
}

export function serializeHeader(h: PacketHeader): Buffer {
  const buf = Buffer.allocUnsafe(HEADER_SIZE);
  buf.writeUInt16LE(h.magic, 0);
  buf.writeUInt8(h.version, 2);
  buf.writeUInt8(h.packetType, 3);
  buf.writeUInt16LE(h.sequence, 4);
  buf.writeUInt8(h.clientId, 6);
  buf.writeUInt8(h.destinationId, 7);
  return buf;
}

export function parseHeader(data: Buffer): PacketHeader {
  if (data.length < HEADER_SIZE) throw new Error(`Buffer too short for header: ${data.length}`);
  const magic = data.readUInt16LE(0);
  if (magic !== MAGIC) throw new Error(`Invalid magic: 0x${magic.toString(16).padStart(4, "0")}`);
  return {
    magic,
    version: data.readUInt8(2),
    packetType: data.readUInt8(3),
    sequence: data.readUInt16LE(4),
    clientId: data.readUInt8(6),
    destinationId: data.readUInt8(7),
  };
}

export interface ConnectRequestPayload {
  readonly clientVersion: number;
  readonly name: string;
  readonly sessionId: number;
  readonly gameId: number;
}

export interface ConnectAcceptPayload {
  readonly clientId: number;
  readonly sessionId: number;
  readonly token: bigint;
}

export interface ConnectDenyPayload {
  readonly reason: string;
}

export interface SessionConfigPayload {
  readonly version: number;
  readonly tickRate: number;
  readonly maxPacketSize: number;
}

export interface PacketTypeEntryPayload {
  readonly packetId: number;
  readonly name: string;
  readonly description: string;
}

export interface PacketTypeRegistryPayload {
  readonly entries: readonly PacketTypeEntryPayload[];
}

export interface HostRegisterPayload {
  readonly sessionId: number;
  readonly hostToken: bigint;
}

export interface PingPayload {
  readonly timestamp: bigint;
}

export interface PongPayload {
  readonly originalTimestamp: bigint;
}

export interface DisconnectNoticePayload {
  readonly _tag: "disconnect";
}

export interface AckPayload {
  readonly sequences: readonly number[];
}

export interface ReconnectRequestPayload {
  readonly token: bigint;
  readonly sessionId: number;
  readonly previousClientId: number;
}

export interface GamePacketPayload {
  readonly rawByte: number;
  readonly payload: Buffer;
}

export type AnyPayload =
  | ConnectRequestPayload
  | ConnectAcceptPayload
  | ConnectDenyPayload
  | SessionConfigPayload
  | PacketTypeRegistryPayload
  | HostRegisterPayload
  | PingPayload
  | PongPayload
  | DisconnectNoticePayload
  | AckPayload
  | ReconnectRequestPayload
  | GamePacketPayload;

export function serializeConnectRequest(p: ConnectRequestPayload): Buffer {
  const nameBytes = Buffer.from(p.name, "utf8");
  const buf = Buffer.allocUnsafe(1 + 2 + nameBytes.length + 4 + 4);
  let off = 0;
  buf.writeUInt8(p.clientVersion, off++);
  buf.writeUInt16LE(nameBytes.length, off);
  off += 2;
  nameBytes.copy(buf, off);
  off += nameBytes.length;
  buf.writeInt32LE(p.sessionId, off);
  off += 4;
  buf.writeInt32LE(p.gameId, off);
  return buf;
}

export function serializeConnectAccept(p: ConnectAcceptPayload): Buffer {
  const buf = Buffer.allocUnsafe(13);
  buf.writeUInt8(p.clientId, 0);
  buf.writeInt32LE(p.sessionId, 1);
  buf.writeBigUInt64LE(BigInt.asUintN(64, p.token), 5);
  return buf;
}

export function serializeConnectDeny(p: ConnectDenyPayload): Buffer {
  const reasonBytes = Buffer.from(p.reason, "utf8");
  const buf = Buffer.allocUnsafe(2 + reasonBytes.length);
  buf.writeUInt16LE(reasonBytes.length, 0);
  reasonBytes.copy(buf, 2);
  return buf;
}

export function serializeSessionConfig(p: SessionConfigPayload): Buffer {
  const buf = Buffer.allocUnsafe(5);
  buf.writeUInt8(p.version, 0);
  buf.writeInt16LE(p.tickRate, 1);
  buf.writeInt16LE(p.maxPacketSize, 3);
  return buf;
}

export function serializePacketTypeEntry(e: PacketTypeEntryPayload): Buffer {
  const nameBytes = Buffer.from(e.name, "utf8");
  const descBytes = Buffer.from(e.description, "utf8");
  const buf = Buffer.allocUnsafe(1 + 1 + nameBytes.length + 1 + descBytes.length);
  let off = 0;
  buf.writeUInt8(e.packetId, off++);
  buf.writeUInt8(nameBytes.length, off++);
  nameBytes.copy(buf, off);
  off += nameBytes.length;
  buf.writeUInt8(descBytes.length, off++);
  descBytes.copy(buf, off);
  return buf;
}

export function serializePacketTypeRegistry(p: PacketTypeRegistryPayload): Buffer {
  const entryBufs = p.entries.map(serializePacketTypeEntry);
  const header = Buffer.allocUnsafe(2);
  header.writeUInt16LE(p.entries.length, 0);
  return Buffer.concat([header, ...entryBufs]);
}

export function serializeHostRegister(p: HostRegisterPayload): Buffer {
  const buf = Buffer.allocUnsafe(12);
  buf.writeInt32LE(p.sessionId, 0);
  buf.writeBigUInt64LE(BigInt.asUintN(64, p.hostToken), 4);
  return buf;
}

export function serializePing(p: PingPayload): Buffer {
  const buf = Buffer.allocUnsafe(8);
  buf.writeBigUInt64LE(BigInt.asUintN(64, p.timestamp), 0);
  return buf;
}

export function serializePong(p: PongPayload): Buffer {
  const buf = Buffer.allocUnsafe(8);
  buf.writeBigUInt64LE(BigInt.asUintN(64, p.originalTimestamp), 0);
  return buf;
}

export function serializeAck(p: AckPayload): Buffer {
  const buf = Buffer.allocUnsafe(2 + p.sequences.length * 2);
  buf.writeUInt16LE(p.sequences.length, 0);
  for (let i = 0; i < p.sequences.length; i++) {
    buf.writeUInt16LE(p.sequences[i], 2 + i * 2);
  }
  return buf;
}

export function serializeReconnectRequest(p: ReconnectRequestPayload): Buffer {
  const buf = Buffer.allocUnsafe(13);
  buf.writeBigUInt64LE(BigInt.asUintN(64, p.token), 0);
  buf.writeInt32LE(p.sessionId, 8);
  buf.writeUInt8(p.previousClientId, 12);
  return buf;
}

export function parseConnectRequest(data: Buffer): ConnectRequestPayload {
  if (data.length < 3) throw new Error("ConnectRequest too short");
  const clientVersion = data.readUInt8(0);
  const nameLen = data.readUInt16LE(1);
  if (nameLen < 1 || nameLen > MAX_NAME_LENGTH) throw new Error(`Invalid name length: ${nameLen}`);
  if (data.length < 3 + nameLen + 8) throw new Error("ConnectRequest truncated");
  const name = data.subarray(3, 3 + nameLen).toString("utf8");
  const sessionId = data.readInt32LE(3 + nameLen);
  const gameId = data.readInt32LE(3 + nameLen + 4);
  return { clientVersion, name, sessionId, gameId };
}

export function parseConnectAccept(data: Buffer): ConnectAcceptPayload {
  if (data.length < 13) throw new Error(`ConnectAccept too short: ${data.length}`);
  return {
    clientId: data.readUInt8(0),
    sessionId: data.readInt32LE(1),
    token: data.readBigUInt64LE(5),
  };
}

export function parseConnectDeny(data: Buffer): ConnectDenyPayload {
  if (data.length < 2) throw new Error("ConnectDeny too short");
  const reasonLen = data.readUInt16LE(0);
  if (reasonLen < 1 || reasonLen > MAX_DESCRIPTION_LENGTH) throw new Error(`Invalid reason length: ${reasonLen}`);
  if (data.length < 2 + reasonLen) throw new Error("ConnectDeny truncated");
  return { reason: data.subarray(2, 2 + reasonLen).toString("utf8") };
}

export function parseSessionConfig(data: Buffer): SessionConfigPayload {
  if (data.length < 5) throw new Error(`SessionConfig too short: ${data.length}`);
  return {
    version: data.readUInt8(0),
    tickRate: data.readInt16LE(1),
    maxPacketSize: data.readInt16LE(3),
  };
}

function parsePacketTypeEntry(data: Buffer, offset: number): [PacketTypeEntryPayload, number] {
  if (data.length - offset < 3) throw new Error("PacketTypeEntry too short");
  const packetId = data.readUInt8(offset);
  const nameLen = data.readUInt8(offset + 1);
  if (nameLen > MAX_NAME_LENGTH) throw new Error(`PacketTypeEntry name too long: ${nameLen}`);
  offset += 2;
  if (data.length - offset < nameLen) throw new Error("PacketTypeEntry name truncated");
  const name = data.subarray(offset, offset + nameLen).toString("utf8");
  offset += nameLen;
  if (data.length - offset < 1) throw new Error("PacketTypeEntry truncated at descLen");
  const descLen = data.readUInt8(offset++);
  if (data.length - offset < descLen) throw new Error("PacketTypeEntry description truncated");
  const description = data.subarray(offset, offset + descLen).toString("utf8");
  return [{ packetId, name, description }, offset + descLen];
}

export function parsePacketTypeRegistry(data: Buffer): PacketTypeRegistryPayload {
  if (data.length < 2) throw new Error("PacketTypeRegistry too short");
  const count = data.readUInt16LE(0);
  if (count > MAX_PACKET_COUNT) throw new Error(`Too many packet type entries: ${count}`);
  const entries: PacketTypeEntryPayload[] = [];
  let offset = 2;
  for (let i = 0; i < count; i++) {
    const [entry, newOffset] = parsePacketTypeEntry(data, offset);
    entries.push(entry);
    offset = newOffset;
  }
  return { entries };
}

export function parseHostRegister(data: Buffer): HostRegisterPayload {
  if (data.length < 12) throw new Error(`HostRegister too short: ${data.length}`);
  return {
    sessionId: data.readInt32LE(0),
    hostToken: data.readBigUInt64LE(4),
  };
}

export function parsePing(data: Buffer): PingPayload {
  if (data.length < 8) throw new Error("Ping too short");
  return { timestamp: data.readBigUInt64LE(0) };
}

export function parsePong(data: Buffer): PongPayload {
  if (data.length < 8) throw new Error("Pong too short");
  return { originalTimestamp: data.readBigUInt64LE(0) };
}

export function parseAck(data: Buffer): AckPayload {
  if (data.length < 2) throw new Error("Ack too short");
  const count = data.readUInt16LE(0);
  if (count > MAX_PACKET_COUNT) throw new Error(`Too many ack sequences: ${count}`);
  if (data.length < 2 + count * 2) throw new Error("Ack truncated");
  const sequences: number[] = [];
  for (let i = 0; i < count; i++) {
    sequences.push(data.readUInt16LE(2 + i * 2));
  }
  return { sequences };
}

export function parseReconnectRequest(data: Buffer): ReconnectRequestPayload {
  if (data.length < 13) throw new Error(`ReconnectRequest too short: ${data.length}`);
  return {
    token: data.readBigUInt64LE(0),
    sessionId: data.readInt32LE(8),
    previousClientId: data.readUInt8(12),
  };
}

export interface NeonPacket {
  readonly header: PacketHeader;
  readonly payload: AnyPayload;
}

export function serializePayload(header: PacketHeader, payload: AnyPayload): Buffer {
  const ptype = packetTypeFromByte(header.packetType);
  switch (ptype) {
    case PacketType.CONNECT_REQUEST:
      return serializeConnectRequest(payload as ConnectRequestPayload);
    case PacketType.CONNECT_ACCEPT:
      return serializeConnectAccept(payload as ConnectAcceptPayload);
    case PacketType.CONNECT_DENY:
      return serializeConnectDeny(payload as ConnectDenyPayload);
    case PacketType.SESSION_CONFIG:
      return serializeSessionConfig(payload as SessionConfigPayload);
    case PacketType.PACKET_TYPE_REGISTRY:
      return serializePacketTypeRegistry(payload as PacketTypeRegistryPayload);
    case PacketType.HOST_REGISTER:
      return serializeHostRegister(payload as HostRegisterPayload);
    case PacketType.PING:
      return serializePing(payload as PingPayload);
    case PacketType.PONG:
      return serializePong(payload as PongPayload);
    case PacketType.DISCONNECT_NOTICE:
      return Buffer.alloc(0);
    case PacketType.ACK:
      return serializeAck(payload as AckPayload);
    case PacketType.RECONNECT_REQUEST:
      return serializeReconnectRequest(payload as ReconnectRequestPayload);
    case PacketType.GAME_PACKET:
      return (payload as GamePacketPayload).payload;
    default:
      throw new Error(`Cannot serialize unknown packet type`);
  }
}

export function serializePacket(pkt: NeonPacket): Buffer {
  const payloadBuf = serializePayload(pkt.header, pkt.payload);
  return Buffer.concat([serializeHeader(pkt.header), payloadBuf]);
}

export function parsePacket(data: Buffer): NeonPacket {
  if (data.length < HEADER_SIZE) throw new Error(`Packet too short: ${data.length}`);
  const header = parseHeader(data);
  const payloadData = data.subarray(HEADER_SIZE);
  const ptype = packetTypeFromByte(header.packetType);
  let payload: AnyPayload;
  switch (ptype) {
    case PacketType.CONNECT_REQUEST:
      payload = parseConnectRequest(payloadData);
      break;
    case PacketType.CONNECT_ACCEPT:
      payload = parseConnectAccept(payloadData);
      break;
    case PacketType.CONNECT_DENY:
      payload = parseConnectDeny(payloadData);
      break;
    case PacketType.SESSION_CONFIG:
      payload = parseSessionConfig(payloadData);
      break;
    case PacketType.PACKET_TYPE_REGISTRY:
      payload = parsePacketTypeRegistry(payloadData);
      break;
    case PacketType.HOST_REGISTER:
      payload = parseHostRegister(payloadData);
      break;
    case PacketType.PING:
      payload = parsePing(payloadData);
      break;
    case PacketType.PONG:
      payload = parsePong(payloadData);
      break;
    case PacketType.DISCONNECT_NOTICE:
      payload = { _tag: "disconnect" };
      break;
    case PacketType.ACK:
      payload = parseAck(payloadData);
      break;
    case PacketType.RECONNECT_REQUEST:
      payload = parseReconnectRequest(payloadData);
      break;
    case PacketType.GAME_PACKET:
      payload = { rawByte: header.packetType, payload: Buffer.from(payloadData) };
      break;
    default:
      throw new Error(`Unhandled packet type`);
  }
  return { header, payload };
}

export function makePacket(
  packetType: PacketType,
  sequence: number,
  clientId: number,
  destinationId: number,
  payload: AnyPayload
): NeonPacket {
  return {
    header: buildHeader(packetType, sequence, clientId, destinationId),
    payload,
  };
}

export function isConnectRequest(p: AnyPayload): p is ConnectRequestPayload {
  return "name" in p && "sessionId" in p && "gameId" in p && "clientVersion" in p;
}

export function isConnectAccept(p: AnyPayload): p is ConnectAcceptPayload {
  return "clientId" in p && "sessionId" in p && "token" in p;
}

export function isConnectDeny(p: AnyPayload): p is ConnectDenyPayload {
  return "reason" in p;
}

export function isSessionConfig(p: AnyPayload): p is SessionConfigPayload {
  return "tickRate" in p && "maxPacketSize" in p;
}

export function isPacketTypeRegistry(p: AnyPayload): p is PacketTypeRegistryPayload {
  return "entries" in p;
}

export function isHostRegister(p: AnyPayload): p is HostRegisterPayload {
  return "hostToken" in p;
}

export function isPing(p: AnyPayload): p is PingPayload {
  return "timestamp" in p && !("originalTimestamp" in p);
}

export function isPong(p: AnyPayload): p is PongPayload {
  return "originalTimestamp" in p;
}

export function isDisconnectNotice(p: AnyPayload): p is DisconnectNoticePayload {
  return "_tag" in p && (p as DisconnectNoticePayload)._tag === "disconnect";
}

export function isAck(p: AnyPayload): p is AckPayload {
  return "sequences" in p;
}

export function isReconnectRequest(p: AnyPayload): p is ReconnectRequestPayload {
  return "previousClientId" in p;
}

export function isGamePacket(p: AnyPayload): p is GamePacketPayload {
  return "rawByte" in p && "payload" in p;
}
