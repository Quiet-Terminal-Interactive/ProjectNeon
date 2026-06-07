import * as dgram from "dgram";
import type { AddressInfo } from "net";
import { EventEmitter } from "events";
import { NeonConfig } from "./_config";
import { DtlsConfig, DtlsContext, DtlsSession } from "./dtls";
import { HEADER_SIZE, NeonPacket, isDtlsRecord, parsePacket, serializePacket } from "./_protocol";

export interface Address {
  readonly address: string;
  readonly port: number;
}

export function addressKey(a: Address): string {
  return `${a.address}:${a.port}`;
}

export function parseAddress(s: string): Address {
  const idx = s.lastIndexOf(":");
  if (idx < 0) throw new Error(`Invalid address: ${s}`);
  return { address: s.slice(0, idx), port: parseInt(s.slice(idx + 1), 10) };
}

export class NeonSocket extends EventEmitter {
  private socket: dgram.Socket;
  private config: NeonConfig;
  private closed = false;

  private dtlsCtx: DtlsContext | null = null;
  private dtlsSessions = new Map<string, DtlsSession>();
  private isDtlsServer = false;

  constructor(bindAddress?: Address, config?: NeonConfig) {
    super();
    this.config = config ?? new NeonConfig();

    this.socket = dgram.createSocket("udp4");
    this.socket.on("message", (msg, rinfo) => {
      this._onRaw(msg, { address: rinfo.address, port: rinfo.port });
    });
    this.socket.on("error", (err) => {
      if (!this.closed) this.emit("error", err);
    });

    if (bindAddress) {
      this.socket.bind(bindAddress.port, bindAddress.address);
    } else {
      this.socket.bind();
    }
  }

  waitBound(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.socket.address) {
        try {
          this.socket.address();
          resolve();
          return;
        } catch {
        }
      }
      this.socket.once("listening", resolve);
      this.socket.once("error", reject);
    });
  }

  enableServerDtls(dtlsCfg: DtlsConfig): void {
    this.dtlsCtx = dtlsCfg.buildContext();
    this.isDtlsServer = true;
  }

  async performClientHandshake(dtlsCfg: DtlsConfig, peer: Address, timeoutMs: number): Promise<void> {
    this.dtlsCtx = dtlsCfg.buildContext();
    const session = this.dtlsCtx.newSession(false);
    this.dtlsSessions.set(addressKey(peer), session);

    const initial = session.initiate();
    if (initial.length > 0) {
      await this._sendRaw(initial, peer);
    }

    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("DTLS handshake timed out")), timeoutMs);

      const onData = (data: Buffer, addr: Address) => {
        if (addressKey(addr) !== addressKey(peer)) return;
        if (!isDtlsRecord(data[0])) return;
        try {
          const { outbound } = session.receive(data);
          if (outbound.length > 0) this._sendRaw(outbound, peer).catch(() => {});
          if (session.isReady) {
            this.socket.off("rawmessage" as never, onData);
            clearTimeout(timer);
            resolve();
          }
        } catch (e) {
          this.socket.off("rawmessage" as never, onData);
          clearTimeout(timer);
          reject(e);
        }
      };

      this.socket.on("message", (msg, rinfo) => {
        const addr = { address: rinfo.address, port: rinfo.port };
        if (isDtlsRecord(msg[0])) {
          onData(msg, addr);
        }
      });
    });
  }

  removeDtlsSession(addr: Address): void {
    const key = addressKey(addr);
    const session = this.dtlsSessions.get(key);
    if (session) {
      session.destroy();
      this.dtlsSessions.delete(key);
    }
  }

  send(data: Buffer, addr: Address): void {
    const key = addressKey(addr);
    const session = this.dtlsSessions.get(key);
    if (session?.isReady) {
      const encrypted = session.wrap(data);
      if (encrypted.length > 0) {
        this.socket.send(encrypted, addr.port, addr.address);
      }
      return;
    }
    this.socket.send(data, addr.port, addr.address);
  }

  sendPacket(pkt: NeonPacket, addr: Address): void {
    this.send(serializePacket(pkt), addr);
  }

  private _sendRaw(data: Buffer, addr: Address): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socket.send(data, addr.port, addr.address, (err) => (err ? reject(err) : resolve()));
    });
  }

  private _onRaw(data: Buffer, addr: Address): void {
    if (!data.length) return;

    if (this.config.enforceBufferSize && data.length === this.config.bufferSize) return;

    if (data.length > 0 && isDtlsRecord(data[0])) {
      this._handleDtls(data, addr);
      return;
    }

    if (data.length < HEADER_SIZE) return;

    try {
      const pkt = parsePacket(data);
      this.emit("packet", pkt, addr);
    } catch {}
  }

  private _handleDtls(data: Buffer, addr: Address): void {
    const key = addressKey(addr);
    let session = this.dtlsSessions.get(key);

    if (!session) {
      if (!this.isDtlsServer || !this.dtlsCtx) return;
      try {
        session = this.dtlsCtx.newSession(true);
        this.dtlsSessions.set(key, session);
      } catch {
        return;
      }
    }

    let plaintext: Buffer | null;
    let outbound: Buffer;
    try {
      ({ plaintext, outbound } = session.receive(data));
    } catch {
      this.dtlsSessions.get(key)?.destroy();
      this.dtlsSessions.delete(key);
      return;
    }

    if (outbound.length > 0) {
      this.socket.send(outbound, addr.port, addr.address);
    }

    if (!plaintext || plaintext.length < HEADER_SIZE) return;

    try {
      const pkt = parsePacket(plaintext);
      this.emit("packet", pkt, addr);
    } catch {}
  }

  localAddress(): Address {
    const a = this.socket.address() as AddressInfo;
    return { address: a.address, port: a.port };
  }

  get isClosed(): boolean {
    return this.closed;
  }

  close(): void {
    if (!this.closed) {
      this.closed = true;
      for (const session of this.dtlsSessions.values()) session.destroy();
      this.dtlsSessions.clear();
      this.dtlsCtx?.destroy();
      this.dtlsCtx = null;
      try {
        this.socket.close();
      } catch {}
    }
  }
}
