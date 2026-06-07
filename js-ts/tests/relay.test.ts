/**
 * NeonRelay unit tests using raw dgram sockets to simulate hosts and clients.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import * as dgram from "dgram";
import { NeonRelay } from "../src/relay";
import { NeonConfig } from "../src/_config";
import {
  makePacket,
  serializePacket,
  parsePacket,
  PacketType,
  isConnectAccept,
  isConnectDeny,
  isConnectRequest,
  ConnectAcceptPayload,
  ConnectDenyPayload,
  ConnectRequestPayload,
  HostRegisterPayload,
  DisconnectNoticePayload,
} from "../src/_protocol";

function testConfig(): NeonConfig {
  return new NeonConfig({
    clientConnectionTimeoutMs: 2000,
    relayCleanupIntervalMs: 60_000,
    enforceBufferSize: false,
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// RawPeer: a dgram socket that sends/receives raw Neon packets
// ---------------------------------------------------------------------------

class RawPeer {
  private sock: dgram.Socket;
  private _port = 0;
  private relayAddr: { address: string; port: number } = { address: "127.0.0.1", port: 0 };
  private received: ReturnType<typeof parsePacket>[] = [];
  private waiters: Array<(pkt: ReturnType<typeof parsePacket>) => void> = [];

  constructor() {
    this.sock = dgram.createSocket("udp4");
    this.sock.on("message", (msg) => {
      try {
        const pkt = parsePacket(msg);
        const waiter = this.waiters.shift();
        if (waiter) waiter(pkt);
        else this.received.push(pkt);
      } catch {
        // ignore malformed
      }
    });
  }

  async bind(): Promise<void> {
    await new Promise<void>((resolve) => this.sock.bind(0, "127.0.0.1", resolve));
    this._port = (this.sock.address() as dgram.AddressInfo).port;
  }

  setRelayAddress(address: string, port: number): void {
    this.relayAddr = { address, port };
  }

  send(pkt: ReturnType<typeof makePacket>): void {
    const buf = serializePacket(pkt);
    this.sock.send(buf, this.relayAddr.port, this.relayAddr.address);
  }

  waitForPacket(timeoutMs = 2000): Promise<ReturnType<typeof parsePacket>> {
    return new Promise((resolve, reject) => {
      const existing = this.received.shift();
      if (existing) { resolve(existing); return; }
      const timer = setTimeout(() => reject(new Error("Timeout waiting for packet")), timeoutMs);
      this.waiters.push((pkt) => { clearTimeout(timer); resolve(pkt); });
    });
  }

  get port(): number { return this._port; }

  close(): void { try { this.sock.close(); } catch {} }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("relay: HOST_REGISTER → CONNECT_ACCEPT(clientId=1)", () => {
  let relay: NeonRelay;
  let host: RawPeer;

  beforeEach(async () => {
    relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    host = new RawPeer();
    await host.bind();
    host.setRelayAddress("127.0.0.1", relay.localAddress().port);
  });

  afterEach(() => {
    if (relay.isRunning) relay.stop();
    host.close();
  });

  it("relay acknowledges HOST_REGISTER with CONNECT_ACCEPT(clientId=1)", async () => {
    const payload: HostRegisterPayload = { sessionId: 42, hostToken: BigInt("0xaabbccddaabbccdd") };
    host.send(makePacket(PacketType.HOST_REGISTER, 0, 1, 0, payload));

    const resp = await host.waitForPacket();
    expect(isConnectAccept(resp.payload)).toBe(true);
    expect((resp.payload as ConnectAcceptPayload).clientId).toBe(1);
    expect((resp.payload as ConnectAcceptPayload).sessionId).toBe(42);
  });
});

describe("relay: CONNECT_REQUEST flow", () => {
  let relay: NeonRelay;
  let hostPeer: RawPeer;
  let clientPeer: RawPeer;

  beforeEach(async () => {
    relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    hostPeer = new RawPeer();
    clientPeer = new RawPeer();
    await hostPeer.bind();
    await clientPeer.bind();
    hostPeer.setRelayAddress("127.0.0.1", relay.localAddress().port);
    clientPeer.setRelayAddress("127.0.0.1", relay.localAddress().port);

    // Register host
    const regPkt: HostRegisterPayload = { sessionId: 55, hostToken: 1n };
    hostPeer.send(makePacket(PacketType.HOST_REGISTER, 0, 1, 0, regPkt));
    await hostPeer.waitForPacket(); // discard HOST ACK
  });

  afterEach(() => {
    if (relay.isRunning) relay.stop();
    hostPeer.close();
    clientPeer.close();
  });

  it("relay forwards CONNECT_REQUEST to host", async () => {
    const req: ConnectRequestPayload = { clientVersion: 1, name: "player1", sessionId: 55, gameId: 0 };
    clientPeer.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req));

    const forwarded = await hostPeer.waitForPacket();
    expect(isConnectRequest(forwarded.payload)).toBe(true);
    expect((forwarded.payload as ConnectRequestPayload).name).toBe("player1");
  });

  it("relay forwards CONNECT_ACCEPT from host to waiting client", async () => {
    const req: ConnectRequestPayload = { clientVersion: 1, name: "player1", sessionId: 55, gameId: 0 };
    clientPeer.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req));
    await hostPeer.waitForPacket(); // forward

    // Host sends CONNECT_ACCEPT
    const accept: ConnectAcceptPayload = { clientId: 2, sessionId: 55, token: 0xdeadbeefn };
    hostPeer.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 1, 0, accept));

    const clientResp = await clientPeer.waitForPacket(2000);
    expect(isConnectAccept(clientResp.payload)).toBe(true);
    expect((clientResp.payload as ConnectAcceptPayload).clientId).toBe(2);
  });

  it("relay sends CONNECT_DENY when session not found", async () => {
    const req: ConnectRequestPayload = { clientVersion: 1, name: "lost", sessionId: 9999, gameId: 0 };
    clientPeer.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req));

    const resp = await clientPeer.waitForPacket(2000);
    expect(isConnectDeny(resp.payload)).toBe(true);
    expect((resp.payload as ConnectDenyPayload).reason).toMatch(/session/i);
  });
});

describe("relay: packet routing", () => {
  let relay: NeonRelay;
  let hostPeer: RawPeer;
  let c1: RawPeer;
  let c2: RawPeer;

  beforeEach(async () => {
    relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    hostPeer = new RawPeer();
    c1 = new RawPeer();
    c2 = new RawPeer();
    await hostPeer.bind();
    await c1.bind();
    await c2.bind();

    const rp = relay.localAddress().port;
    hostPeer.setRelayAddress("127.0.0.1", rp);
    c1.setRelayAddress("127.0.0.1", rp);
    c2.setRelayAddress("127.0.0.1", rp);

    // Register host
    const regPkt: HostRegisterPayload = { sessionId: 77, hostToken: 1n };
    hostPeer.send(makePacket(PacketType.HOST_REGISTER, 0, 1, 0, regPkt));
    await hostPeer.waitForPacket();

    // Connect c1
    const req1: ConnectRequestPayload = { clientVersion: 1, name: "c1", sessionId: 77, gameId: 0 };
    c1.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req1));
    await hostPeer.waitForPacket(); // forward
    const accept1: ConnectAcceptPayload = { clientId: 2, sessionId: 77, token: 10n };
    hostPeer.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 1, 0, accept1));
    await c1.waitForPacket();

    // Connect c2
    const req2: ConnectRequestPayload = { clientVersion: 1, name: "c2", sessionId: 77, gameId: 0 };
    c2.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req2));
    await hostPeer.waitForPacket();
    const accept2: ConnectAcceptPayload = { clientId: 3, sessionId: 77, token: 20n };
    hostPeer.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 1, 0, accept2));
    await c2.waitForPacket();
  });

  afterEach(() => {
    if (relay.isRunning) relay.stop();
    hostPeer.close();
    c1.close();
    c2.close();
  });

  it("unicast: c1 sends to c2 (destId=3), c2 receives it", async () => {
    const gamePkt = makePacket(0x10, 5, 2, 3, { rawByte: 0x10, payload: Buffer.from([0xAB]) });
    c1.send(gamePkt);

    const received = await c2.waitForPacket(1500);
    expect(received.header.packetType).toBe(0x10);
    expect(received.header.clientId).toBe(2);
    expect(received.header.destinationId).toBe(3);
  });

  it("broadcast: c1 sends to destId=0, both host and c2 receive it", async () => {
    const gamePkt = makePacket(0x11, 6, 2, 0, { rawByte: 0x11, payload: Buffer.from([0xFF]) });
    c1.send(gamePkt);

    const fromC2 = await c2.waitForPacket(1500);
    const fromHost = await hostPeer.waitForPacket(1500);
    expect(fromC2.header.packetType).toBe(0x11);
    expect(fromHost.header.packetType).toBe(0x11);
  });

  it("relay does not echo broadcast back to sender", async () => {
    // c1 broadcasts; c1 should NOT receive its own packet
    let c1GotEcho = false;
    const original = c1["waitForPacket"].bind(c1);
    const gamePkt = makePacket(0x12, 7, 2, 0, { rawByte: 0x12, payload: Buffer.from([0x01]) });
    c1.send(gamePkt);

    // Wait for others to receive it
    await c2.waitForPacket(1000);
    await hostPeer.waitForPacket(1000);

    // c1 should NOT have received anything
    const c1Internal = c1 as unknown as { received: unknown[] };
    expect(c1Internal.received.length).toBe(0);
  });
});

describe("relay: disconnect handling", () => {
  let relay: NeonRelay;
  let hostPeer: RawPeer;
  let c1: RawPeer;

  beforeEach(async () => {
    relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    hostPeer = new RawPeer();
    c1 = new RawPeer();
    await hostPeer.bind();
    await c1.bind();

    const rp = relay.localAddress().port;
    hostPeer.setRelayAddress("127.0.0.1", rp);
    c1.setRelayAddress("127.0.0.1", rp);

    const regPkt: HostRegisterPayload = { sessionId: 88, hostToken: 1n };
    hostPeer.send(makePacket(PacketType.HOST_REGISTER, 0, 1, 0, regPkt));
    await hostPeer.waitForPacket();

    const req: ConnectRequestPayload = { clientVersion: 1, name: "leaving", sessionId: 88, gameId: 0 };
    c1.send(makePacket(PacketType.CONNECT_REQUEST, 0, 0, 1, req));
    await hostPeer.waitForPacket();
    const accept: ConnectAcceptPayload = { clientId: 2, sessionId: 88, token: 1n };
    hostPeer.send(makePacket(PacketType.CONNECT_ACCEPT, 0, 1, 0, accept));
    await c1.waitForPacket();
  });

  afterEach(() => {
    if (relay.isRunning) relay.stop();
    hostPeer.close();
    c1.close();
  });

  it("relay broadcasts DISCONNECT_NOTICE when a peer disconnects", async () => {
    const notice: DisconnectNoticePayload = { _tag: "disconnect" };
    c1.send(makePacket(PacketType.DISCONNECT_NOTICE, 0, 2, 0, notice));

    const relayed = await hostPeer.waitForPacket(1500);
    expect(relayed.header.packetType).toBe(PacketType.DISCONNECT_NOTICE);
  });
});
