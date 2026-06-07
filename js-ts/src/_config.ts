import type { DtlsConfig } from "./dtls";

export interface NeonConfigOptions {
  bufferSize?: number;
  enforceBufferSize?: boolean;
  relayPort?: number;
  relayCleanupIntervalMs?: number;
  relayClientTimeoutMs?: number;
  relayMainLoopSleepMs?: number;
  maxPendingConnections?: number;
  maxRateLimiters?: number;
  maxPacketsPerSecond?: number;
  maxClientsPerSession?: number;
  hostAckTimeoutMs?: number;
  hostMaxAckRetries?: number;
  hostSessionTokenTimeoutMs?: number;
  hostGracefulShutdownTimeoutMs?: number;
  hostProcessingLoopSleepMs?: number;
  hostSessionTickRate?: number;
  hostSessionMaxPacketSize?: number;
  clientConnectionTimeoutMs?: number;
  clientPingIntervalMs?: number;
  clientInitialReconnectDelayMs?: number;
  clientMaxReconnectDelayMs?: number;
  clientMaxReconnectAttempts?: number;
  clientProcessingLoopSleepMs?: number;
  reliablePacketTimeoutMs?: number;
  reliablePacketMaxRetries?: number;
  dtlsConfig?: DtlsConfig | null;
}

export class NeonConfig {
  readonly bufferSize: number;
  readonly enforceBufferSize: boolean;
  readonly relayPort: number;
  readonly relayCleanupIntervalMs: number;
  readonly relayClientTimeoutMs: number;
  readonly relayMainLoopSleepMs: number;
  readonly maxPendingConnections: number;
  readonly maxRateLimiters: number;
  readonly maxPacketsPerSecond: number;
  readonly maxClientsPerSession: number;
  readonly hostAckTimeoutMs: number;
  readonly hostMaxAckRetries: number;
  readonly hostSessionTokenTimeoutMs: number;
  readonly hostGracefulShutdownTimeoutMs: number;
  readonly hostProcessingLoopSleepMs: number;
  readonly hostSessionTickRate: number;
  readonly hostSessionMaxPacketSize: number;
  readonly clientConnectionTimeoutMs: number;
  readonly clientPingIntervalMs: number;
  readonly clientInitialReconnectDelayMs: number;
  readonly clientMaxReconnectDelayMs: number;
  readonly clientMaxReconnectAttempts: number;
  readonly clientProcessingLoopSleepMs: number;
  readonly reliablePacketTimeoutMs: number;
  readonly reliablePacketMaxRetries: number;
  readonly dtlsConfig: DtlsConfig | null;

  constructor(opts: NeonConfigOptions = {}) {
    this.bufferSize = opts.bufferSize ?? 65535;
    this.enforceBufferSize = opts.enforceBufferSize ?? true;

    this.relayPort = opts.relayPort ?? 7777;
    this.relayCleanupIntervalMs = opts.relayCleanupIntervalMs ?? 5000;
    this.relayClientTimeoutMs = opts.relayClientTimeoutMs ?? 15000;
    this.relayMainLoopSleepMs = opts.relayMainLoopSleepMs ?? 1;
    this.maxPendingConnections = opts.maxPendingConnections ?? 64;
    this.maxRateLimiters = opts.maxRateLimiters ?? 1024;
    this.maxPacketsPerSecond = opts.maxPacketsPerSecond ?? 100;
    this.maxClientsPerSession = opts.maxClientsPerSession ?? 32;

    this.hostAckTimeoutMs = opts.hostAckTimeoutMs ?? 2000;
    this.hostMaxAckRetries = opts.hostMaxAckRetries ?? 5;
    this.hostSessionTokenTimeoutMs = opts.hostSessionTokenTimeoutMs ?? 300_000;
    this.hostGracefulShutdownTimeoutMs = opts.hostGracefulShutdownTimeoutMs ?? 3000;
    this.hostProcessingLoopSleepMs = opts.hostProcessingLoopSleepMs ?? 10;
    this.hostSessionTickRate = opts.hostSessionTickRate ?? 60;
    this.hostSessionMaxPacketSize = opts.hostSessionMaxPacketSize ?? 1200;

    this.clientConnectionTimeoutMs = opts.clientConnectionTimeoutMs ?? 5000;
    this.clientPingIntervalMs = opts.clientPingIntervalMs ?? 5000;
    this.clientInitialReconnectDelayMs = opts.clientInitialReconnectDelayMs ?? 1000;
    this.clientMaxReconnectDelayMs = opts.clientMaxReconnectDelayMs ?? 30_000;
    this.clientMaxReconnectAttempts = opts.clientMaxReconnectAttempts ?? 6;
    this.clientProcessingLoopSleepMs = opts.clientProcessingLoopSleepMs ?? 10;

    this.reliablePacketTimeoutMs = opts.reliablePacketTimeoutMs ?? 2000;
    this.reliablePacketMaxRetries = opts.reliablePacketMaxRetries ?? 5;

    this.dtlsConfig = opts.dtlsConfig ?? null;

    this._validate();
  }

  get isDtlsEnabled(): boolean {
    return this.dtlsConfig !== null;
  }

  private _validate(): void {
    if (this.bufferSize < 8) throw new Error("bufferSize must be >= 8");
    if (this.maxPendingConnections <= 0) throw new Error("maxPendingConnections must be > 0");
    if (this.maxRateLimiters <= 0) throw new Error("maxRateLimiters must be > 0");
    if (this.maxPacketsPerSecond <= 0) throw new Error("maxPacketsPerSecond must be > 0");
    if (this.maxClientsPerSession <= 0) throw new Error("maxClientsPerSession must be > 0");
    if (this.hostSessionTickRate <= 0) throw new Error("hostSessionTickRate must be > 0");
    if (this.hostSessionMaxPacketSize <= 0) throw new Error("hostSessionMaxPacketSize must be > 0");
  }
}
