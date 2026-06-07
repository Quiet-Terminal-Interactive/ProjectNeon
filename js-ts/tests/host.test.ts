/**
 * NeonHost unit tests using a raw dgram socket as a mock relay.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import * as dgram from "dgram";
import { NeonHost } from "../src/host";
import { NeonConfig } from "../src/_config";
import {
  makePacket,
  serializePacket,
  parsePacket,
  PacketType,
  isHostRegister,
  isConnectAccept,
  isConnectDeny,
  isSessionConfig,
  isPacketTypeRegistry,
  isPong,
  ConnectAcceptPayload,
  ConnectDenyPayload,
  ConnectRequestPayload,
  ReconnectRequestPayload,
  SessionConfigPayload,
  PacketTypeRegistryPayload,
  HostRegisterPayload,
  DisconnectNoticePayload,
  AckPayload,
} from "../src/_protocol";
import { GamePacketRegistry } from "../src/_registry";

function testConfig(): NeonConfig {
  return new NeonConfig({
    clientConnectionTimeoutMs: 2000,
    hostGracefulShutdownTimeoutMs: 50,
    hostProcessingLoopSleepMs: 10,
    hostAckTimeoutMs: 500,
    hostMaxAckRetries: 2,
    enforceBufferSize: false,
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// MockRelay: a dgram socket that acts as the relay
// ---------------------------------------------------------------------------

class MockRelay {
  private sock: dgram.Socket;
  private _port = 0;
  private received: ReturnType<typeof parsePacket>[] = [];
  private sources: dgram.RemoteInfo[] = [];
  private waiters: Array<(pkt: ReturnType<typeof parsePacket>, rinfo: dgram.RemoteInfo) => void> = [];

  constructor() {
    this.sock = dgram.createSocket("udp4");
    this.sock.on("message", (msg, rinfo) => {
      try {
        const pkt = parsePacket(msg);
        const waiter = this.waiters.shift();
        if (waiter) waiter(pkt, rinfo);
        else { this.received.push(pkt); this.sources.push(rinfo); }
      } catch {}
    });
  }

  async bind(): Promise<void> {
    await new Promise<void>((resolve) => this.sock.bind(0, "127.0.0.1", resolve));
    this._port = (this.sock.address() as dgram.AddressInfo).port;
  }

  send(pkt: ReturnType<typeof makePacket>, port: number, address = "127.0.0.1"): void {
    this.sock.send(serializePacket(pkt), port, address);
  }

  waitFor(timeoutMs = 2000): Promise<[ReturnType<typeof parsePacket>, dgram.RemoteInfo]> {
    return new Promise((resolve, reject) => {
      const existing = this.received.shift();
      const src = this.sources.shift();
      if (existing && src) { resolve([existing, src]); return; }
      const timer = setTimeout(() => reject(new Error("MockRelay: timeout")), timeoutMs);
      this.waiters.push((pkt, rinfo) => { clearTimeout(timer); resolve([pkt, rinfo]); });
    });
  }

  get port(): number { return this._port; }

  close(): void { try { this.sock.close(); } catch {} }
}

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

async function startHost(relay: MockRelay, sessionId: number, config = testConfig()): Promise<{
  host: NeonHost;
  hostPort: number;
}> {
  const host = new NeonHost(sessionId, `127.0.0.1:${relay.port}`, config);

  // Concurrently start the host and handle the HOST_REGISTER exchange
  const startPromise = host.start();

  const [regPkt, rinfo] = await relay.waitFor(2000);
  expect(isHostRegister(regPkt.payload)).toBe(true);

  // Relay responds with CONNECT_ACCEPT(clientId=1)
  const accept: ConnectAcceptPayload = {
    clientId: 1,
    sessionId,
    token: (regPkt.payload as HostRegisterPayload).hostToken,
  };
  relay.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 0, 1, accept), rinfo.port);

  await startPromise;
  return { host, hostPort: rinfo.port };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("NeonHost: registration", () => {
  let relay: MockRelay;

  beforeEach(async () => {
    relay = new MockRelay();
    await relay.bind();
  });

  afterEach(() => relay.close());

  it("start() sends HOST_REGISTER to relay", async () => {
    const host = new NeonHost(111, `127.0.0.1:${relay.port}`, testConfig());
    const startP = host.start();

    const [pkt] = await relay.waitFor();
    expect(isHostRegister(pkt.payload)).toBe(true);
    expect((pkt.payload as HostRegisterPayload).sessionId).toBe(111);

    // Respond so start() can finish
    const accept: ConnectAcceptPayload = { clientId: 1, sessionId: 111, token: 0n };
    relay.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 0, 1, accept), host.localAddress().port);
    await startP;
    expect(host.isRunning).toBe(true);

    await host.stop();
  });

  it("start() times out if relay does not respond", async () => {
    const cfg = new NeonConfig({ clientConnectionTimeoutMs: 200, enforceBufferSize: false });
    const host = new NeonHost(222, `127.0.0.1:${relay.port}`, cfg);
    await expect(host.start()).rejects.toThrow(/timed out/i);
  });
});

describe("NeonHost: client connect flow", () => {
  let relay: MockRelay;
  let host: NeonHost;

  beforeEach(async () => {
    relay = new MockRelay();
    await relay.bind();
    ({ host } = await startHost(relay, 333));
  });

  afterEach(async () => {
    if (host.isRunning) await host.stop();
    relay.close();
  });

  it("CONNECT_REQUEST → host sends CONNECT_ACCEPT + SESSION_CONFIG + PACKET_TYPE_REGISTRY", async () => {
    const req: ConnectRequestPayload = { clientVersion: 1, name: "alice", sessionId: 333, gameId: 0 };
    const hostPort = host.localAddress().port;
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), hostPort);

    const [accept] = await relay.waitFor();
    expect(isConnectAccept(accept.payload)).toBe(true);
    const p = accept.payload as ConnectAcceptPayload;
    expect(p.clientId).toBeGreaterThanOrEqual(2);
    expect(p.sessionId).toBe(333);

    const [config] = await relay.waitFor();
    expect(isSessionConfig(config.payload)).toBe(true);
    expect((config.payload as SessionConfigPayload).tickRate).toBe(60);

    const [registry] = await relay.waitFor();
    expect(isPacketTypeRegistry(registry.payload)).toBe(true);
  });

  it("duplicate name → host sends CONNECT_DENY", async () => {
    const req: ConnectRequestPayload = { clientVersion: 1, name: "bob", sessionId: 333, gameId: 0 };
    const hostPort = host.localAddress().port;

    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), hostPort);
    const [accept] = await relay.waitFor(); // CONNECT_ACCEPT
    const acceptSeq = (accept.payload as ConnectAcceptPayload).clientId;
    await relay.waitFor(); // SESSION_CONFIG
    await relay.waitFor(); // PACKET_TYPE_REGISTRY

    // Send ACK for SESSION_CONFIG to stop retransmits
    const ack: AckPayload = { sequences: [acceptSeq] };
    relay.send(makePacket(PacketType.ACK, 0, 0, 1, ack), hostPort);

    // Second client with same name
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), hostPort);
    const [deny] = await relay.waitFor(2000);
    expect(isConnectDeny(deny.payload)).toBe(true);
    expect((deny.payload as ConnectDenyPayload).reason).toMatch(/name taken/i);
  });

  it("fires clientConnect callback", async () => {
    let callbackFired = false;
    host.setClientConnectCallback((id, name) => {
      expect(id).toBeGreaterThanOrEqual(2);
      expect(name).toBe("carol");
      callbackFired = true;
    });

    const req: ConnectRequestPayload = { clientVersion: 1, name: "carol", sessionId: 333, gameId: 0 };
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), host.localAddress().port);

    await relay.waitFor(); // CONNECT_ACCEPT
    await relay.waitFor(); // SESSION_CONFIG
    await relay.waitFor(); // PACKET_TYPE_REGISTRY
    await sleep(50);

    expect(callbackFired).toBe(true);
  });

  it("sends PACKET_TYPE_REGISTRY with registered packet types", async () => {
    const registry = new GamePacketRegistry();
    registry.register(0x10, "MOVE", "Player movement");
    host.setGamePacketRegistry(registry);

    const req: ConnectRequestPayload = { clientVersion: 1, name: "dani", sessionId: 333, gameId: 0 };
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), host.localAddress().port);

    await relay.waitFor(); // CONNECT_ACCEPT
    await relay.waitFor(); // SESSION_CONFIG
    const [regPkt] = await relay.waitFor(); // PACKET_TYPE_REGISTRY
    expect(isPacketTypeRegistry(regPkt.payload)).toBe(true);
    const entries = (regPkt.payload as PacketTypeRegistryPayload).entries;
    expect(entries.length).toBe(1);
    expect(entries[0].name).toBe("MOVE");
  });
});

describe("NeonHost: ping/pong", () => {
  let relay: MockRelay;
  let host: NeonHost;

  beforeEach(async () => {
    relay = new MockRelay();
    await relay.bind();
    ({ host } = await startHost(relay, 444));
  });

  afterEach(async () => {
    if (host.isRunning) await host.stop();
    relay.close();
  });

  it("host responds to PING with PONG", async () => {
    const ping = makePacket(PacketType.PING, 0, 2, 1, { timestamp: 12345n });
    relay.send(ping, host.localAddress().port);

    const [pongPkt] = await relay.waitFor(1000);
    expect(isPong(pongPkt.payload)).toBe(true);
    expect((pongPkt.payload as { originalTimestamp: bigint }).originalTimestamp).toBe(12345n);
  });
});

describe("NeonHost: disconnect", () => {
  let relay: MockRelay;
  let host: NeonHost;

  beforeEach(async () => {
    relay = new MockRelay();
    await relay.bind();
    ({ host } = await startHost(relay, 555));
  });

  afterEach(async () => {
    if (host.isRunning) await host.stop();
    relay.close();
  });

  it("DISCONNECT_NOTICE fires clientDisconnect callback", async () => {
    // First connect a client
    const req: ConnectRequestPayload = { clientVersion: 1, name: "evan", sessionId: 555, gameId: 0 };
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), host.localAddress().port);

    const [accept] = await relay.waitFor();
    const cid = (accept.payload as ConnectAcceptPayload).clientId;
    await relay.waitFor(); // SESSION_CONFIG
    await relay.waitFor(); // PACKET_TYPE_REGISTRY

    let disconnectedId: number | null = null;
    host.setClientDisconnectCallback((id) => { disconnectedId = id; });

    const notice: DisconnectNoticePayload = { _tag: "disconnect" };
    relay.send(makePacket(PacketType.DISCONNECT_NOTICE, 0, cid, 0, notice), host.localAddress().port);
    await sleep(100);

    expect(disconnectedId).toBe(cid);
  });
});

describe("NeonHost: reconnect", () => {
  let relay: MockRelay;
  let host: NeonHost;

  beforeEach(async () => {
    relay = new MockRelay();
    await relay.bind();
    ({ host } = await startHost(relay, 666));
  });

  afterEach(async () => {
    if (host.isRunning) await host.stop();
    relay.close();
  });

  it("valid RECONNECT_REQUEST → CONNECT_ACCEPT with new token", async () => {
    // Connect a client
    const req: ConnectRequestPayload = { clientVersion: 1, name: "frank", sessionId: 666, gameId: 0 };
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), host.localAddress().port);

    const [accept] = await relay.waitFor();
    const cid = (accept.payload as ConnectAcceptPayload).clientId;
    const token = (accept.payload as ConnectAcceptPayload).token;
    await relay.waitFor(); // SESSION_CONFIG
    await relay.waitFor(); // PACKET_TYPE_REGISTRY

    // Disconnect it
    const notice: DisconnectNoticePayload = { _tag: "disconnect" };
    relay.send(makePacket(PacketType.DISCONNECT_NOTICE, 0, cid, 0, notice), host.localAddress().port);
    await sleep(50);

    // Attempt reconnect
    const reconnect: ReconnectRequestPayload = { token, sessionId: 666, previousClientId: cid };
    relay.send(makePacket(PacketType.RECONNECT_REQUEST, 0, cid, 1, reconnect), host.localAddress().port);

    const [reaccept] = await relay.waitFor(1000);
    expect(isConnectAccept(reaccept.payload)).toBe(true);
    expect((reaccept.payload as ConnectAcceptPayload).clientId).toBe(cid);
    expect((reaccept.payload as ConnectAcceptPayload).token).not.toBe(token); // new token
  });

  it("invalid token → CONNECT_DENY", async () => {
    const req: ConnectRequestPayload = { clientVersion: 1, name: "grace", sessionId: 666, gameId: 0 };
    relay.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req), host.localAddress().port);
    const [accept] = await relay.waitFor();
    const cid = (accept.payload as ConnectAcceptPayload).clientId;
    await relay.waitFor();
    await relay.waitFor();

    const notice: DisconnectNoticePayload = { _tag: "disconnect" };
    relay.send(makePacket(PacketType.DISCONNECT_NOTICE, 0, cid, 0, notice), host.localAddress().port);
    await sleep(50);

    const reconnect: ReconnectRequestPayload = { token: 0xdeadbeefn, sessionId: 666, previousClientId: cid };
    relay.send(makePacket(PacketType.RECONNECT_REQUEST, 0, cid, 1, reconnect), host.localAddress().port);

    const [deny] = await relay.waitFor(1000);
    expect(isConnectDeny(deny.payload)).toBe(true);
  });
});
