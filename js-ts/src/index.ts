export { NeonConfig } from "./_config";
export type { NeonConfigOptions } from "./_config";
export {
  MAGIC,
  VERSION,
  HEADER_SIZE,
  PacketType,
  packetTypeFromByte,
  signed16,
  isDtlsRecord,
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
} from "./_protocol";

export type {
  PacketHeader,
  ConnectRequestPayload,
  ConnectAcceptPayload,
  ConnectDenyPayload,
  SessionConfigPayload,
  PacketTypeEntryPayload,
  PacketTypeRegistryPayload,
  HostRegisterPayload,
  PingPayload,
  PongPayload,
  DisconnectNoticePayload,
  AckPayload,
  ReconnectRequestPayload,
  GamePacketPayload,
  AnyPayload,
  NeonPacket,
} from "./_protocol";

export { DtlsConfig, DtlsContext, DtlsSession } from "./dtls";
export { GamePacketRegistry } from "./_registry";
export type { GamePacketDescriptor } from "./_registry";
export { ReliablePacketManager } from "./_reliable";
export { NeonRelay } from "./relay";
export { NeonHost } from "./host";
export { NeonClient } from "./client";
export { parseAddress, addressKey } from "./_socket";
export type { Address } from "./_socket";
