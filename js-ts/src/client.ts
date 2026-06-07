import { EventEmitter } from "events";
import { NeonConfig } from "./_config";
import { NeonSocket, Address, parseAddress } from "./_socket";
import {
  NeonPacket,
  PacketType,
  VERSION,
  makePacket,
  isConnectAccept,
  isConnectDeny,
  isSessionConfig,
  isPacketTypeRegistry,
  isPing,
  isPong,
  isDisconnectNotice,
  ConnectRequestPayload,
  ReconnectRequestPayload,
  AckPayload,
  PingPayload,
  PongPayload,
  DisconnectNoticePayload,
  SessionConfigPayload,
  PacketTypeRegistryPayload,
  ConnectAcceptPayload,
  ConnectDenyPayload,
} from "./_protocol";

enum State {
  CREATED = "CREATED",
  STARTING = "STARTING",
  RUNNING = "RUNNING",
  STOPPING = "STOPPING",
  STOPPED = "STOPPED",
  FAILED = "FAILED",
}

export class NeonClient extends EventEmitter {
  private config: NeonConfig;
  private name: string;
  private socket: NeonSocket;

  private relayAddr: Address | null = null;
  private sessionId: number | null = null;
  private clientId: number | null = null;
  private sessionToken: bigint | null = null;

  private nextSequence = 0;
  private state = State.CREATED;
  private lastPing = Date.now();
  private pingInterval: NodeJS.Timeout | null = null;

  private onPong: ((pong: PongPayload) => void) | null = null;
  private onSessionConfig: ((sc: SessionConfigPayload) => void) | null = null;
  private onPacketTypeRegistry: ((reg: PacketTypeRegistryPayload) => void) | null = null;
  private onUnhandledPacket: ((packetType: number, senderClientId: number) => void) | null = null;
  private onDisconnect: ((clientId: number) => void) | null = null;

  constructor(name: string, config?: NeonConfig) {
    super();
    this.name = name;
    this.config = config ?? new NeonConfig();
    this.socket = new NeonSocket(undefined, this.config);
  }

  setPongCallback(cb: ((pong: PongPayload) => void) | null): void {
    this.onPong = cb;
  }

  setSessionConfigCallback(cb: ((sc: SessionConfigPayload) => void) | null): void {
    this.onSessionConfig = cb;
  }

  setPacketTypeRegistryCallback(cb: ((reg: PacketTypeRegistryPayload) => void) | null): void {
    this.onPacketTypeRegistry = cb;
  }

  setUnhandledPacketCallback(cb: ((packetType: number, senderClientId: number) => void) | null): void {
    this.onUnhandledPacket = cb;
  }

  setDisconnectCallback(cb: ((clientId: number) => void) | null): void {
    this.onDisconnect = cb;
  }

  async connect(sessionId: number, relayAddress: string): Promise<boolean> {
    this.sessionId = sessionId;
    this.relayAddr = parseAddress(relayAddress);
    try {
      await this._doConnect();
      return true;
    } catch (e) {
      return false;
    }
  }

  private async _doConnect(): Promise<void> {
    if (this.state !== State.CREATED) throw new Error(`Cannot connect from state: ${this.state}`);
    this.state = State.STARTING;
    try {
      await this.socket.waitBound();

      if (this.config.isDtlsEnabled) {
        await this.socket.performClientHandshake(
          this.config.dtlsConfig!,
          this.relayAddr!,
          this.config.clientConnectionTimeoutMs
        );
      }

      const req: ConnectRequestPayload = {
        clientVersion: VERSION,
        name: this.name,
        sessionId: this.sessionId!,
        gameId: 0,
      };
      this.socket.sendPacket(makePacket(PacketType.CONNECT_REQUEST, this._nextSeq(), 0, 1, req), this.relayAddr!);

      const pkt = await this._waitForConnectResponse(this.config.clientConnectionTimeoutMs);
      if (!pkt) throw new Error("Connection timed out");

      if (isConnectAccept(pkt.payload)) {
        this.clientId = (pkt.payload as ConnectAcceptPayload).clientId;
        this.sessionToken = (pkt.payload as ConnectAcceptPayload).token;
        this.state = State.RUNNING;
        this._setupRunLoop();
      } else if (isConnectDeny(pkt.payload)) {
        this.state = State.FAILED;
        throw new Error(`Connection denied: ${(pkt.payload as ConnectDenyPayload).reason}`);
      } else {
        this.state = State.FAILED;
        throw new Error("Unexpected response");
      }
    } catch (e) {
      if (this.state === State.STARTING) this.state = State.FAILED;
      throw e;
    }
  }

  stop(): void {
    if (this.state !== State.RUNNING) throw new Error(`Cannot stop from state: ${this.state}`);
    this.state = State.STOPPING;
    this._doStop();
    this.state = State.STOPPED;
  }

  private _doStop(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    const cid = this.clientId ?? 0;
    const notice: DisconnectNoticePayload = { _tag: "disconnect" };
    try {
      this.socket.sendPacket(makePacket(PacketType.DISCONNECT_NOTICE, this._nextSeq(), cid, 0, notice), this.relayAddr!);
    } catch {}
    setImmediate(() => this.socket.close());
  }

  run(): void {}

  get isRunning(): boolean {
    return this.state === State.RUNNING;
  }

  get currentClientId(): number | null {
    return this.clientId;
  }

  localAddress(): Address {
    return this.socket.localAddress();
  }

  async reconnect(maxAttempts?: number): Promise<boolean> {
    if (!this.sessionToken || !this.sessionId || !this.clientId || !this.relayAddr) return false;

    const attempts = maxAttempts ?? this.config.clientMaxReconnectAttempts;

    if (this.socket.isClosed) {
      this.socket = new NeonSocket(undefined, this.config);
      await this.socket.waitBound();
      if (this.config.isDtlsEnabled) {
        try {
          await this.socket.performClientHandshake(
            this.config.dtlsConfig!,
            this.relayAddr,
            this.config.clientConnectionTimeoutMs
          );
        } catch {
          return false;
        }
      }
      this.socket.on("packet", (pkt: NeonPacket, addr: Address) => this._handlePacket(pkt));
    }

    let delayMs = this.config.clientInitialReconnectDelayMs;
    for (let i = 0; i < attempts; i++) {
      if (i > 0) {
        await _sleep(delayMs);
        delayMs = Math.min(delayMs * 2, this.config.clientMaxReconnectDelayMs);
      }
      if (await this._attemptReconnect()) {
        this.state = State.RUNNING;
        return true;
      }
    }
    return false;
  }

  private async _attemptReconnect(): Promise<boolean> {
    const req: ReconnectRequestPayload = {
      token: this.sessionToken!,
      sessionId: this.sessionId!,
      previousClientId: this.clientId!,
    };
    try {
      this.socket.sendPacket(
        makePacket(PacketType.RECONNECT_REQUEST, this._nextSeq(), this.clientId!, 1, req),
        this.relayAddr!
      );
      const pkt = await this._waitForConnectResponse(this.config.clientConnectionTimeoutMs);
      if (!pkt) return false;
      if (isConnectAccept(pkt.payload)) {
        this.sessionToken = (pkt.payload as ConnectAcceptPayload).token;
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }

  private _setupRunLoop(): void {
    this.socket.on("packet", (pkt: NeonPacket) => this._handlePacket(pkt));
    this.pingInterval = setInterval(() => this._checkAutoPing(), this.config.clientProcessingLoopSleepMs);
    if (this.pingInterval.unref) this.pingInterval.unref();
  }

  private _handlePacket(pkt: NeonPacket): void {
    if (this.state !== State.RUNNING) return;
    const { header, payload } = pkt;
    const cid = this.clientId;
    if (cid !== null && header.destinationId !== 0 && header.destinationId !== cid) return;

    if (isPong(payload)) {
      this.onPong?.(payload as PongPayload);
    } else if (isSessionConfig(payload)) {
      this._handleSessionConfig(payload as SessionConfigPayload, header.sequence);
    } else if (isPacketTypeRegistry(payload)) {
      this.onPacketTypeRegistry?.(payload as PacketTypeRegistryPayload);
    } else if (isPing(payload)) {
      this._sendPong((payload as PingPayload).timestamp);
    } else if (isDisconnectNotice(payload)) {
      this.onDisconnect?.(header.clientId);
    } else {
      this.onUnhandledPacket?.(header.packetType, header.clientId);
    }
  }

  private _handleSessionConfig(sc: SessionConfigPayload, sequence: number): void {
    this.onSessionConfig?.(sc);
    const cid = this.clientId ?? 0;
    const ack: AckPayload = { sequences: [sequence] };
    this.socket.sendPacket(makePacket(PacketType.ACK, this._nextSeq(), cid, 0, ack), this.relayAddr!);
  }

  private _sendPong(timestamp: bigint): void {
    const cid = this.clientId ?? 0;
    const pong: PongPayload = { originalTimestamp: timestamp };
    this.socket.sendPacket(makePacket(PacketType.PONG, this._nextSeq(), cid, 1, pong), this.relayAddr!);
  }

  private _checkAutoPing(): void {
    if (this.state !== State.RUNNING || !this.relayAddr) return;
    const now = Date.now();
    if (now - this.lastPing >= this.config.clientPingIntervalMs) {
      this.lastPing = now;
      const cid = this.clientId ?? 0;
      const ping: PingPayload = { timestamp: BigInt(now) };
      this.socket.sendPacket(makePacket(PacketType.PING, this._nextSeq(), cid, 1, ping), this.relayAddr);
    }
  }

  sendPacket(payload: Buffer, packetType: number, destId: number): void {
    if (!this.relayAddr) throw new Error("Not connected");
    const cid = this.clientId ?? 0;
    this.socket.sendPacket(makePacket(packetType, this._nextSeq(), cid, destId, { rawByte: packetType, payload }), this.relayAddr);
  }

  private _nextSeq(): number {
    const seq = this.nextSequence & 0xffff;
    this.nextSequence++;
    return seq;
  }

  private _waitForConnectResponse(timeoutMs: number): Promise<NeonPacket | null> {
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.socket.off("packet", handler);
        resolve(null);
      }, timeoutMs);

      const handler = (pkt: NeonPacket) => {
        if (isConnectAccept(pkt.payload) || isConnectDeny(pkt.payload)) {
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
