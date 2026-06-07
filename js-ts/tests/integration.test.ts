/**
 * Full integration tests: NeonRelay + NeonHost + NeonClient over loopback UDP.
 * These tests exercise the complete packet flow without any mocking.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { NeonRelay } from "../src/relay";
import { NeonHost } from "../src/host";
import { NeonClient } from "../src/client";
import { NeonConfig } from "../src/_config";
import { SessionConfigPayload, PacketTypeRegistryPayload } from "../src/_protocol";
import { GamePacketRegistry } from "../src/_registry";

const SESSION_A = 10001;
const SESSION_B = 10002;

function testConfig(overrides: Partial<ConstructorParameters<typeof NeonConfig>[0]> = {}): NeonConfig {
  return new NeonConfig({
    clientConnectionTimeoutMs: 3000,
    hostGracefulShutdownTimeoutMs: 100,
    clientPingIntervalMs: 60_000,
    hostProcessingLoopSleepMs: 10,
    clientProcessingLoopSleepMs: 10,
    relayCleanupIntervalMs: 60_000,
    enforceBufferSize: false,
    ...overrides,
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function startRelay(sessionOffset = 0): Promise<NeonRelay> {
  const relay = new NeonRelay("127.0.0.1:0", testConfig());
  await relay.start();
  return relay;
}

async function startHost(relay: NeonRelay, sessionId: number): Promise<NeonHost> {
  const port = relay.localAddress().port;
  const host = new NeonHost(sessionId, `127.0.0.1:${port}`, testConfig());
  await host.start();
  return host;
}

async function connectClient(relay: NeonRelay, sessionId: number, name: string): Promise<NeonClient> {
  const port = relay.localAddress().port;
  const client = new NeonClient(name, testConfig());
  const ok = await client.connect(sessionId, `127.0.0.1:${port}`);
  if (!ok) throw new Error(`Client '${name}' failed to connect`);
  return client;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("relay: host registration", () => {
  let relay: NeonRelay;

  beforeEach(async () => {
    relay = await startRelay();
  });

  afterEach(() => {
    if (relay.isRunning) relay.stop();
  });

  it("starts and stops cleanly", async () => {
    expect(relay.isRunning).toBe(true);
    relay.stop();
    expect(relay.isRunning).toBe(false);
  });
});

describe("host lifecycle", () => {
  let relay: NeonRelay;
  let host: NeonHost;

  beforeEach(async () => {
    relay = await startRelay();
    host = await startHost(relay, SESSION_A);
  });

  afterEach(async () => {
    if (host.isRunning) await host.stop();
    if (relay.isRunning) relay.stop();
  });

  it("host registers and is RUNNING", () => {
    expect(host.isRunning).toBe(true);
  });

  it("host stops cleanly", async () => {
    await host.stop();
    expect(host.isRunning).toBe(false);
  });
});

describe("client connects", () => {
  let relay: NeonRelay;
  let host: NeonHost;
  let client: NeonClient;

  beforeEach(async () => {
    relay = await startRelay();
    host = await startHost(relay, SESSION_A);
    client = await connectClient(relay, SESSION_A, "alice");
  });

  afterEach(async () => {
    if (client.isRunning) client.stop();
    if (host.isRunning) await host.stop();
    if (relay.isRunning) relay.stop();
  });

  it("client is RUNNING with a valid clientId >= 2", () => {
    expect(client.isRunning).toBe(true);
    expect(client.currentClientId).not.toBeNull();
    expect(client.currentClientId!).toBeGreaterThanOrEqual(2);
  });

  it("host fires client connect callback", async () => {
    await host.stop();

    // Restart to get fresh callbacks
    const relay2 = new NeonRelay("127.0.0.1:0", testConfig());
    await relay2.start();
    const host2 = await startHost(relay2, SESSION_B);

    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("callback not fired")), 3000);
      host2.setClientConnectCallback((id, name) => {
        clearTimeout(timer);
        expect(id).toBeGreaterThanOrEqual(2);
        expect(name).toBe("bob");
        resolve();
      });
      connectClient(relay2, SESSION_B, "bob").catch(reject);
    });

    const port2 = relay2.localAddress().port;
    const c2 = new NeonClient("bob", testConfig());
    const ok = await c2.connect(SESSION_B, `127.0.0.1:${port2}`);
    // May already have connected in the promise above; either is fine
    if (c2.isRunning) c2.stop();
    await host2.stop();
    relay2.stop();
  });

  it("host sends session config to client", async () => {
    // Re-run a fresh scenario and capture the SessionConfig callback
    const relay2 = new NeonRelay("127.0.0.1:0", testConfig());
    await relay2.start();
    const host2 = await startHost(relay2, SESSION_B);
    const port2 = relay2.localAddress().port;

    let capturedConfig: SessionConfigPayload | null = null;
    const c2 = new NeonClient("player1", testConfig());
    c2.setSessionConfigCallback((sc) => { capturedConfig = sc; });

    const ok = await c2.connect(SESSION_B, `127.0.0.1:${port2}`);
    expect(ok).toBe(true);

    // Give a moment for async delivery
    await sleep(200);

    expect(capturedConfig).not.toBeNull();
    expect(capturedConfig!.tickRate).toBe(60);

    c2.stop();
    await host2.stop();
    relay2.stop();
  });
});

describe("session config and registry delivery", () => {
  it("delivers SESSION_CONFIG and PACKET_TYPE_REGISTRY to client", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const registry = new GamePacketRegistry();
    registry.register(0x10, "POSITION", "Player position update");
    registry.register(0x11, "CHAT", "Chat message");

    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    host.setGamePacketRegistry(registry);
    await host.start();

    let gotConfig = false;
    let gotRegistry: PacketTypeRegistryPayload | null = null;

    const client = new NeonClient("tester", testConfig());
    client.setSessionConfigCallback(() => { gotConfig = true; });
    client.setPacketTypeRegistryCallback((r) => { gotRegistry = r; });

    await client.connect(SESSION_A, `127.0.0.1:${port}`);
    await sleep(300);

    expect(gotConfig).toBe(true);
    expect(gotRegistry).not.toBeNull();
    expect(gotRegistry!.entries.length).toBe(2);
    expect(gotRegistry!.entries[0].name).toBe("POSITION");
    expect(gotRegistry!.entries[1].name).toBe("CHAT");

    client.stop();
    await host.stop();
    relay.stop();
  });
});

describe("game packet routing", () => {
  it("client can send a game packet to the host (unhandled callback fires)", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    let hostGotType: number | null = null;
    host.setUnhandledPacketCallback((type) => { hostGotType = type; });
    await host.start();

    const client = new NeonClient("sender", testConfig());
    await client.connect(SESSION_A, `127.0.0.1:${port}`);

    await sleep(100);
    client.sendPacket(Buffer.from([0xAA, 0xBB]), 0x10, 1);
    await sleep(200);

    expect(hostGotType).toBe(0x10);

    client.stop();
    await host.stop();
    relay.stop();
  });

  it("host can send a game packet to a client (unhandled callback fires)", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    await host.start();

    let clientGotType: number | null = null;
    const client = new NeonClient("receiver", testConfig());
    client.setUnhandledPacketCallback((type) => { clientGotType = type; });
    await client.connect(SESSION_A, `127.0.0.1:${port}`);

    await sleep(100);
    host.sendPacket(Buffer.from([0x01]), 0x12, client.currentClientId!);
    await sleep(200);

    expect(clientGotType).toBe(0x12);

    client.stop();
    await host.stop();
    relay.stop();
  });

  it("host broadcasts to all clients", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    await host.start();

    const received: number[] = [];
    const c1 = new NeonClient("p1", testConfig());
    const c2 = new NeonClient("p2", testConfig());
    c1.setUnhandledPacketCallback(() => received.push(1));
    c2.setUnhandledPacketCallback(() => received.push(2));

    await c1.connect(SESSION_A, `127.0.0.1:${port}`);
    await c2.connect(SESSION_A, `127.0.0.1:${port}`);

    await sleep(100);
    host.sendPacket(Buffer.from([0x01]), 0x15, 0); // broadcast
    await sleep(300);

    expect(received).toContain(1);
    expect(received).toContain(2);

    c1.stop();
    c2.stop();
    await host.stop();
    relay.stop();
  });
});

describe("connect deny", () => {
  it("second client with same name is denied", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;
    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    await host.start();

    const c1 = new NeonClient("sameuser", testConfig());
    const ok1 = await c1.connect(SESSION_A, `127.0.0.1:${port}`);
    expect(ok1).toBe(true);

    const c2 = new NeonClient("sameuser", testConfig());
    const ok2 = await c2.connect(SESSION_A, `127.0.0.1:${port}`);
    expect(ok2).toBe(false);

    if (c1.isRunning) c1.stop();
    await host.stop();
    relay.stop();
  });

  it("connecting to non-existent session is denied", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const client = new NeonClient("nobody", new NeonConfig({
      clientConnectionTimeoutMs: 1000,
      enforceBufferSize: false,
    }));
    const ok = await client.connect(99999, `127.0.0.1:${port}`);
    expect(ok).toBe(false);

    relay.stop();
  });
});

describe("disconnect", () => {
  it("client disconnect fires host disconnect callback", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    let disconnectedId: number | null = null;
    host.setClientDisconnectCallback((id) => { disconnectedId = id; });
    await host.start();

    const client = new NeonClient("leaving", testConfig());
    await client.connect(SESSION_A, `127.0.0.1:${port}`);
    const cid = client.currentClientId!;

    await sleep(100);
    client.stop();
    await sleep(300);

    expect(disconnectedId).toBe(cid);

    await host.stop();
    relay.stop();
  });
});

describe("multiple clients full scenario", () => {
  it("two clients can exchange packets via host", async () => {
    const relay = new NeonRelay("127.0.0.1:0", testConfig());
    await relay.start();
    const port = relay.localAddress().port;

    const host = new NeonHost(SESSION_A, `127.0.0.1:${port}`, testConfig());
    await host.start();

    const c1 = new NeonClient("alpha", testConfig());
    const c2 = new NeonClient("beta", testConfig());

    let c2GotPacket = false;
    c2.setUnhandledPacketCallback(() => { c2GotPacket = true; });

    await c1.connect(SESSION_A, `127.0.0.1:${port}`);
    await c2.connect(SESSION_A, `127.0.0.1:${port}`);

    await sleep(100);

    // c1 sends to c2 via relay (relay routes by destId via host... actually relay routes directly)
    c1.sendPacket(Buffer.from([0x01]), 0x10, c2.currentClientId!);
    await sleep(200);

    expect(c2GotPacket).toBe(true);

    c1.stop();
    c2.stop();
    await host.stop();
    relay.stop();
  });
});
