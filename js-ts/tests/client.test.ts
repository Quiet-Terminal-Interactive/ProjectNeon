/**
 * NeonClient unit tests using a raw dgram socket as a mock relay.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import * as dgram from "dgram";
import { NeonClient } from "../src/client";
import { NeonConfig } from "../src/_config";
import {
  makePacket,
  serializePacket,
  parsePacket,
  PacketType,
  isConnectRequest,
  isPing,
  isAck,
  isDisconnectNotice,
  ConnectAcceptPayload,
  ConnectDenyPayload,
  ConnectRequestPayload,
  SessionConfigPayload,
  PacketTypeRegistryPayload,
  PingPayload,
  AckPayload,
} from "../src/_protocol";

function testConfig(): NeonConfig {
  return new NeonConfig({
    clientConnectionTimeoutMs: 2000,
    clientPingIntervalMs: 200,
    clientProcessingLoopSleepMs: 10,
    enforceBufferSize: false,
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// MockRelayServer: a dgram socket that acts as the relay
// ---------------------------------------------------------------------------

class MockRelayServer {
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
// Helper: connect a client via the mock relay
// ---------------------------------------------------------------------------

async function connectClient(
  relay: MockRelayServer,
  name: string,
  sessionId = 42,
  config = testConfig()
): Promise<{ client: NeonClient; clientPort: number; token: bigint; clientId: number }> {
  const client = new NeonClient(name, config);
  const connectPromise = client.connect(sessionId, `127.0.0.1:${relay.port}`);

  // Wait for CONNECT_REQUEST
  const [reqPkt, rinfo] = await relay.waitFor(2000);
  expect(isConnectRequest(reqPkt.payload)).toBe(true);

  const clientId = 2;
  const token = 0xcafebabedeadbeefn;
  const accept: ConnectAcceptPayload = { clientId, sessionId, token };
  relay.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 0, clientId, accept), rinfo.port);

  const ok = await connectPromise;
  expect(ok).toBe(true);

  return { client, clientPort: rinfo.port, token, clientId };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("NeonClient: connect flow", () => {
  let relay: MockRelayServer;

  beforeEach(async () => {
    relay = new MockRelayServer();
    await relay.bind();
  });

  afterEach(() => relay.close());

  it("connect() sends CONNECT_REQUEST with correct session and name", async () => {
    const client = new NeonClient("testuser", testConfig());
    const p = client.connect(99, `127.0.0.1:${relay.port}`);

    const [reqPkt, rinfo] = await relay.waitFor();
    expect(isConnectRequest(reqPkt.payload)).toBe(true);
    const payload = reqPkt.payload as ConnectRequestPayload;
    expect(payload.name).toBe("testuser");
    expect(payload.sessionId).toBe(99);
    expect(reqPkt.header.destinationId).toBe(1);

    // Accept
    const accept: ConnectAcceptPayload = { clientId: 3, sessionId: 99, token: 1n };
    relay.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 0, 3, accept), rinfo.port);
    const ok = await p;
    expect(ok).toBe(true);
    expect(client.currentClientId).toBe(3);

    client.stop();
  });

  it("connect() returns false on CONNECT_DENY", async () => {
    const client = new NeonClient("denied", testConfig());
    const p = client.connect(99, `127.0.0.1:${relay.port}`);

    const [, rinfo] = await relay.waitFor();
    const deny: ConnectDenyPayload = { reason: "Name taken" };
    relay.send(makePacket(PacketType.CONNECT_DENY, 0, 0, 0, deny), rinfo.port);

    const ok = await p;
    expect(ok).toBe(false);
  });

  it("connect() returns false on timeout", async () => {
    const cfg = new NeonConfig({ clientConnectionTimeoutMs: 200, enforceBufferSize: false });
    const client = new NeonClient("slow", cfg);
    // Do NOT respond — let it time out
    const [, rinfo] = await relay.waitFor(300).catch(() => [null, null]);
    const ok = await client.connect(99, `127.0.0.1:${relay.port}`);
    expect(ok).toBe(false);
  });
});

describe("NeonClient: session config callback", () => {
  let relay: MockRelayServer;
  let client: NeonClient;
  let clientPort: number;

  beforeEach(async () => {
    relay = new MockRelayServer();
    await relay.bind();
    ({ client, clientPort } = await connectClient(relay, "cfg-test"));
  });

  afterEach(() => {
    if (client.isRunning) client.stop();
    relay.close();
  });

  it("SESSION_CONFIG fires the callback and client sends ACK", async () => {
    let gotConfig: SessionConfigPayload | null = null;
    client.setSessionConfigCallback((sc) => { gotConfig = sc; });

    const sc: SessionConfigPayload = { version: 1, tickRate: 30, maxPacketSize: 800 };
    relay.send(makePacket(PacketType.SESSION_CONFIG, 7, 1, 2, sc), clientPort);

    // Wait for ACK
    const [ackPkt] = await relay.waitFor(1000);
    expect(isAck(ackPkt.payload)).toBe(true);
    expect((ackPkt.payload as AckPayload).sequences).toContain(7);

    await sleep(50);
    expect(gotConfig).not.toBeNull();
    expect(gotConfig!.tickRate).toBe(30);
  });

  it("PACKET_TYPE_REGISTRY fires the callback", async () => {
    let gotRegistry: PacketTypeRegistryPayload | null = null;
    client.setPacketTypeRegistryCallback((r) => { gotRegistry = r; });

    const reg: PacketTypeRegistryPayload = {
      entries: [{ packetId: 0x10, name: "MOVE", description: "" }],
    };
    relay.send(makePacket(PacketType.PACKET_TYPE_REGISTRY, 0, 1, 2, reg), clientPort);
    await sleep(100);

    expect(gotRegistry).not.toBeNull();
    expect(gotRegistry!.entries[0].name).toBe("MOVE");
  });
});

describe("NeonClient: auto-ping", () => {
  let relay: MockRelayServer;
  let client: NeonClient;
  let clientPort: number;

  beforeEach(async () => {
    relay = new MockRelayServer();
    await relay.bind();
    ({ client, clientPort } = await connectClient(relay, "pinger"));
  });

  afterEach(() => {
    if (client.isRunning) client.stop();
    relay.close();
  });

  it("client sends PING after ping interval elapses", async () => {
    // Wait a bit more than the ping interval (200ms in testConfig)
    const [pingPkt] = await relay.waitFor(600);
    expect(isPing(pingPkt.payload)).toBe(true);
    expect(typeof (pingPkt.payload as PingPayload).timestamp).toBe("bigint");
  });

  it("client reflects PONG when host sends PING", async () => {
    relay.send(makePacket(PacketType.PING, 0, 1, 2, { timestamp: 99999n }), clientPort);
    const [pong] = await relay.waitFor(500);
    expect(pong.header.packetType).toBe(PacketType.PONG);
  });
});

describe("NeonClient: sendPacket", () => {
  let relay: MockRelayServer;
  let client: NeonClient;

  beforeEach(async () => {
    relay = new MockRelayServer();
    await relay.bind();
    ({ client } = await connectClient(relay, "sender"));
  });

  afterEach(() => {
    if (client.isRunning) client.stop();
    relay.close();
  });

  it("sendPacket emits a game packet with correct type and destId", async () => {
    client.sendPacket(Buffer.from([0x01, 0x02]), 0x10, 1);
    const [pkt] = await relay.waitFor(500);
    expect(pkt.header.packetType).toBe(0x10);
    expect(pkt.header.destinationId).toBe(1);
    expect(pkt.header.clientId).toBe(2);
  });

  it("sequence numbers increment", async () => {
    client.sendPacket(Buffer.from([0x01]), 0x10, 1);
    client.sendPacket(Buffer.from([0x02]), 0x10, 1);

    const [p1] = await relay.waitFor(500);
    const [p2] = await relay.waitFor(500);
    expect(p2.header.sequence).toBeGreaterThan(p1.header.sequence);
  });
});

describe("NeonClient: disconnect", () => {
  let relay: MockRelayServer;
  let client: NeonClient;
  let clientPort: number;

  beforeEach(async () => {
    relay = new MockRelayServer();
    await relay.bind();
    ({ client, clientPort } = await connectClient(relay, "disconnector"));
  });

  afterEach(() => {
    if (client.isRunning) client.stop();
    relay.close();
  });

  it("stop() sends DISCONNECT_NOTICE and closes", async () => {
    client.stop();

    const [pkt] = await relay.waitFor(1000);
    expect(isDisconnectNotice(pkt.payload)).toBe(true);
    expect(client.isRunning).toBe(false);
  });

  it("incoming DISCONNECT_NOTICE fires callback", async () => {
    let dcClientId: number | null = null;
    client.setDisconnectCallback((id) => { dcClientId = id; });

    relay.send(makePacket(PacketType.DISCONNECT_NOTICE, 0, 1, 0, { _tag: "disconnect" }), clientPort);
    await sleep(100);

    expect(dcClientId).toBe(1);
  });
});

describe("NeonClient: packet filtering", () => {
  let relay: MockRelayServer;
  let client: NeonClient;
  let clientPort: number;

  beforeEach(async () => {
    relay = new MockRelayServer();
    await relay.bind();
    ({ client, clientPort } = await connectClient(relay, "filter"));
  });

  afterEach(() => {
    if (client.isRunning) client.stop();
    relay.close();
  });

  it("packets addressed to a different clientId are ignored", async () => {
    let fired = false;
    client.setUnhandledPacketCallback(() => { fired = true; });

    // Send a game packet addressed to clientId=99 (not ours = 2)
    relay.send(makePacket(0x10, 0, 3, 99, { rawByte: 0x10, payload: Buffer.from([0x01]) }), clientPort);
    await sleep(100);

    expect(fired).toBe(false);
  });

  it("broadcast packets (destId=0) are accepted", async () => {
    let fired = false;
    client.setUnhandledPacketCallback(() => { fired = true; });

    relay.send(makePacket(0x10, 0, 3, 0, { rawByte: 0x10, payload: Buffer.from([0x01]) }), clientPort);
    await sleep(100);

    expect(fired).toBe(true);
  });
});
