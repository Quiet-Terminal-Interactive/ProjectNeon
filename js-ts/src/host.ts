import { randomBytes } from "crypto";
import { EventEmitter } from "events";
import { NeonConfig } from "./_config";
import { NeonSocket, Address, parseAddress } from "./_socket";
import { AckStateMachine } from "./_ack";
import { GamePacketRegistry } from "./_registry";
import {
  NeonPacket,
  PacketType,
  makePacket,
  VERSION,
  isConnectRequest,
  isReconnectRequest,
  isAck,
  isPing,
  isDisconnectNotice,
  ConnectAcceptPayload,
  ConnectDenyPayload,
  SessionConfigPayload,
  PacketTypeRegistryPayload,
  AckPayload,
  PongPayload,
  DisconnectNoticePayload,
  GamePacketPayload,
  isGamePacket,
} from "./_protocol";

interface DisconnectedClient {
  name: string;
  token: bigint;
  disconnectedAt: number;
}

enum State {
  CREATED = "CREATED",
  STARTING = "STARTING",
  RUNNING = "RUNNING",
  STOPPING = "STOPPING",
  STOPPED = "STOPPED",
  FAILED = "FAILED",
}

export class NeonHost extends EventEmitter {
  private config: NeonConfig;
  private sessionId: number;
  private relayAddr: Address;
  private socket: NeonSocket;
  private ackMachine: AckStateMachine;

  private nextSequence = 0;
  private state = State.CREATED;

  private connectedClients = new Map<number, string>();
  private connectedNames = new Map<string, number>();
  private clientTokens = new Map<number, bigint>();
  private disconnectedClients = new Map<number, DisconnectedClient>();
  private seqToClient = new Map<number, number>();

  private ackInterval: NodeJS.Timeout | null = null;

  private onClientConnect: ((clientId: number, name: string, sessionId: number) => void) | null = null;
  private onClientDeny: ((name: string, reason: string) => void) | null = null;
  private onClientDisconnect: ((clientId: number) => void) | null = null;
  private onPingReceived: ((clientId: number) => void) | null = null;
  private onUnhandledPacket: ((packetType: number, senderClientId: number, payload: Buffer) => void) | null = null;
  private gamePacketRegistry: GamePacketRegistry | null = null;

  constructor(sessionId: number, relayAddress: string, config?: NeonConfig) {
    super();
    this.config = config ?? new NeonConfig();
    this.sessionId = sessionId;
    this.relayAddr = parseAddress(relayAddress);
    this.socket = new NeonSocket(undefined, this.config);
    this.ackMachine = new AckStateMachine(this.config.hostAckTimeoutMs, this.config.hostMaxAckRetries);
  }

  setClientConnectCallback(cb: ((clientId: number, name: string, sessionId: number) => void) | null): void {
    this.onClientConnect = cb;
  }

  setClientDenyCallback(cb: ((name: string, reason: string) => void) | null): void {
    this.onClientDeny = cb;
  }

  setClientDisconnectCallback(cb: ((clientId: number) => void) | null): void {
    this.onClientDisconnect = cb;
  }

  setPingReceivedCallback(cb: ((clientId: number) => void) | null): void {
    this.onPingReceived = cb;
  }

  setUnhandledPacketCallback(cb: ((packetType: number, senderClientId: number, payload: Buffer) => void) | null): void {
    this.onUnhandledPacket = cb;
  }

  setGamePacketRegistry(registry: GamePacketRegistry | null): void {
    this.gamePacketRegistry = registry;
  }

  async start(): Promise<void> {
    if (this.state !== State.CREATED) throw new Error(`Cannot start from state: ${this.state}`);
    this.state = State.STARTING;
    try {
      await this.socket.waitBound();
      await this._doStart();
      this.state = State.RUNNING;
      this._setupRunLoop();
    } catch (e) {
      this.state = State.FAILED;
      throw e;
    }
  }

  async stop(): Promise<void> {
    if (this.state !== State.RUNNING) throw new Error(`Cannot stop from state: ${this.state}`);
    this.state = State.STOPPING;
    try {
      await this._doStop();
    } finally {
      this.state = State.STOPPED;
    }
  }

  async startAndRun(): Promise<void> {
    await this.start();
  }

  get isRunning(): boolean {
    return this.state === State.RUNNING;
  }

  localAddress(): Address {
    return this.socket.localAddress();
  }

  private async _doStart(): Promise<void> {
    if (this.config.isDtlsEnabled) {
      await this.socket.performClientHandshake(
        this.config.dtlsConfig!,
        this.relayAddr,
        this.config.clientConnectionTimeoutMs
      );
    }

    const hostToken = randomBytes(8).readBigUInt64LE(0);
    this.clientTokens.set(1, hostToken);

    this.socket.sendPacket(
      makePacket(PacketType.HOST_REGISTER, this._nextSeq(), 1, 0, {
        sessionId: this.sessionId,
        hostToken,
      }),
      this.relayAddr
    );

    await this._waitForPacket(
      (pkt) => {
        const p = pkt.payload;
        return "clientId" in p && "token" in p && (p as ConnectAcceptPayload).clientId === 1;
      },
      this.config.clientConnectionTimeoutMs,
      "Relay registration timed out"
    );
  }

  private async _doStop(): Promise<void> {
    if (this.ackInterval) {
      clearInterval(this.ackInterval);
      this.ackInterval = null;
    }

    const notice: DisconnectNoticePayload = { _tag: "disconnect" };
    try {
      this.socket.sendPacket(makePacket(PacketType.DISCONNECT_NOTICE, this._nextSeq(), 1, 0, notice), this.relayAddr);
    } catch {}

    const deadline = Date.now() + this.config.hostGracefulShutdownTimeoutMs;
    while (this.ackMachine.hasPending && Date.now() < deadline) {
      this._checkPendingAcks();
      await _sleep(10);
    }

    this.socket.close();
  }

  private _setupRunLoop(): void {
    this.socket.on("packet", (pkt: NeonPacket) => this._handlePacket(pkt));
    this.ackInterval = setInterval(() => this._checkPendingAcks(), this.config.hostProcessingLoopSleepMs);
    if (this.ackInterval.unref) this.ackInterval.unref();
  }

  private _handlePacket(pkt: NeonPacket): void {
    if (this.state !== State.RUNNING) return;
    const { header, payload } = pkt;
    if (isConnectRequest(payload)) {
      this._handleConnectRequest(payload, header);
    } else if (isReconnectRequest(payload)) {
      this._handleReconnectRequest(payload);
    } else if (isPing(payload)) {
      this._handlePing(payload.timestamp, header.clientId);
    } else if (isAck(payload)) {
      this._handleAck(payload);
    } else if (isDisconnectNotice(payload)) {
      this._handleDisconnect(header.clientId);
    } else {
      const raw = isGamePacket(payload) ? (payload as GamePacketPayload).payload : Buffer.alloc(0);
      this.onUnhandledPacket?.(header.packetType, header.clientId, raw);
    }
  }

  private _handleConnectRequest(req: { name: string; sessionId: number }, header: NeonPacket["header"]): void {
    if (this.connectedClients.size >= this.config.maxClientsPerSession) {
      this._sendDeny(req.name, "Session full");
      return;
    }
    if (this.connectedNames.has(req.name)) {
      this._sendDeny(req.name, "Name taken");
      return;
    }

    const cid = this._nextClientId();
    if (cid === null) {
      this._sendDeny(req.name, "Server full");
      return;
    }

    const token = randomBytes(8).readBigUInt64LE(0);
    this.connectedClients.set(cid, req.name);
    this.connectedNames.set(req.name, cid);
    this.clientTokens.set(cid, token);

    const accept: ConnectAcceptPayload = { clientId: cid, sessionId: this.sessionId, token };
    this.socket.sendPacket(makePacket(PacketType.CONNECT_ACCEPT, this._nextSeq(), 1, 0, accept), this.relayAddr);

    this.onClientConnect?.(cid, req.name, this.sessionId);

    const cfgSeq = this._nextSeq();
    const cfgPayload: SessionConfigPayload = {
      version: VERSION,
      tickRate: this.config.hostSessionTickRate,
      maxPacketSize: this.config.hostSessionMaxPacketSize,
    };
    const cfgPkt = makePacket(PacketType.SESSION_CONFIG, cfgSeq, 1, cid, cfgPayload);
    this.socket.sendPacket(cfgPkt, this.relayAddr);
    this.ackMachine.track(cfgSeq, cfgPkt);
    this.seqToClient.set(cfgSeq, cid);

    const registry: PacketTypeRegistryPayload =
      this.gamePacketRegistry && !this.gamePacketRegistry.isEmpty
        ? this.gamePacketRegistry.buildRegistry()
        : { entries: [] };
    this.socket.sendPacket(
      makePacket(PacketType.PACKET_TYPE_REGISTRY, this._nextSeq(), 1, cid, registry),
      this.relayAddr
    );
  }

  private _handleReconnectRequest(req: { token: bigint; previousClientId: number }): void {
    const prevId = req.previousClientId;
    const dc = this.disconnectedClients.get(prevId);
    if (!dc) {
      this._sendDeny("", "Reconnect denied");
      return;
    }
    if (dc.token !== req.token) {
      this._sendDeny(dc.name, "Reconnect denied");
      return;
    }
    if (Date.now() - dc.disconnectedAt > this.config.hostSessionTokenTimeoutMs) {
      this.disconnectedClients.delete(prevId);
      this._sendDeny(dc.name, "Token expired");
      return;
    }

    this.disconnectedClients.delete(prevId);
    const newToken = randomBytes(8).readBigUInt64LE(0);
    this.connectedClients.set(prevId, dc.name);
    this.connectedNames.set(dc.name, prevId);
    this.clientTokens.set(prevId, newToken);

    const accept: ConnectAcceptPayload = { clientId: prevId, sessionId: this.sessionId, token: newToken };
    this.socket.sendPacket(makePacket(PacketType.CONNECT_ACCEPT, this._nextSeq(), 1, 0, accept), this.relayAddr);
    this.onClientConnect?.(prevId, dc.name, this.sessionId);
  }

  private _handlePing(timestamp: bigint, senderId: number): void {
    const pong: PongPayload = { originalTimestamp: timestamp };
    this.socket.sendPacket(makePacket(PacketType.PONG, this._nextSeq(), 1, senderId, pong), this.relayAddr);
    this.onPingReceived?.(senderId);
  }

  private _handleAck(ack: AckPayload): void {
    for (const seq of ack.sequences) {
      this.ackMachine.acknowledge(seq);
      this.seqToClient.delete(seq);
    }
  }

  private _handleDisconnect(clientId: number): void {
    const name = this.connectedClients.get(clientId);
    if (!name) return;
    this.connectedClients.delete(clientId);
    this.connectedNames.delete(name);
    const token = this.clientTokens.get(clientId) ?? 0n;
    this.disconnectedClients.set(clientId, { name, token, disconnectedAt: Date.now() });
    for (const [seq, cid] of [...this.seqToClient]) {
      if (cid === clientId) this.seqToClient.delete(seq);
    }
    this.onClientDisconnect?.(clientId);
  }

  private _sendDeny(name: string, reason: string): void {
    if (name) this.onClientDeny?.(name, reason);
    const deny: ConnectDenyPayload = { reason };
    this.socket.sendPacket(makePacket(PacketType.CONNECT_DENY, this._nextSeq(), 1, 0, deny), this.relayAddr);
  }

  private _checkPendingAcks(): void {
    const { needsRetry, failed } = this.ackMachine.process();
    for (const seq of needsRetry) {
      const pkt = this.ackMachine.markResent(seq);
      if (pkt) this.socket.sendPacket(pkt, this.relayAddr);
    }
    for (const seq of failed) {
      const cid = this.seqToClient.get(seq);
      this.ackMachine.acknowledge(seq);
      this.seqToClient.delete(seq);
      if (cid !== undefined) {
        this.emit("error", new Error(`SESSION_CONFIG delivery permanently failed for client ${cid}`));
      }
    }
  }

  private _nextClientId(): number | null {
    for (let id = 2; id <= 254; id++) {
      if (!this.connectedClients.has(id) && !this.disconnectedClients.has(id)) return id;
    }
    return null;
  }

  sendPacket(payload: Buffer, packetType: number, destId: number): void {
    const pkt = makePacket(packetType, this._nextSeq(), 1, destId, { rawByte: packetType, payload });
    this.socket.sendPacket(pkt, this.relayAddr);
  }

  connectedClientSnapshot(): Map<number, string> {
    return new Map(this.connectedClients);
  }

  private _nextSeq(): number {
    const seq = this.nextSequence & 0xffff;
    this.nextSequence++;
    return seq;
  }

  private _waitForPacket(
    predicate: (pkt: NeonPacket) => boolean,
    timeoutMs: number,
    timeoutMessage: string
  ): Promise<NeonPacket> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.socket.off("packet", handler);
        reject(new Error(timeoutMessage));
      }, timeoutMs);

      const handler = (pkt: NeonPacket) => {
        if (predicate(pkt)) {
          clearTimeout(timer);
          this.socket.off("packet", handler);
          resolve(pkt);
        }
      };
      this.socket.on("packet", handler);
    });
  }
}

function _sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
