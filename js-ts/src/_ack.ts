import { NeonPacket } from "./_protocol";

interface AckEntry {
  packet: NeonPacket;
  sentAt: number;
  retries: number;
}

export interface AckProcessResult {
  needsRetry: number[];
  failed: number[];
}

export class AckStateMachine {
  private pending = new Map<number, AckEntry>();
  private readonly timeoutMs: number;
  private readonly maxRetries: number;

  constructor(ackTimeoutMs: number, maxRetries: number) {
    this.timeoutMs = ackTimeoutMs;
    this.maxRetries = maxRetries;
  }

  track(sequence: number, packet: NeonPacket): void {
    this.pending.set(sequence, { packet, sentAt: Date.now(), retries: 0 });
  }

  process(): AckProcessResult {
    const now = Date.now();
    const needsRetry: number[] = [];
    const failed: number[] = [];
    for (const [seq, entry] of this.pending) {
      if (now - entry.sentAt >= this.timeoutMs) {
        if (entry.retries >= this.maxRetries) {
          failed.push(seq);
        } else {
          needsRetry.push(seq);
        }
      }
    }
    return { needsRetry, failed };
  }

  markResent(sequence: number): NeonPacket | null {
    const entry = this.pending.get(sequence);
    if (!entry) return null;
    this.pending.set(sequence, { packet: entry.packet, sentAt: Date.now(), retries: entry.retries + 1 });
    return entry.packet;
  }

  acknowledge(sequence: number): void {
    this.pending.delete(sequence);
  }

  getPacket(sequence: number): NeonPacket | null {
    return this.pending.get(sequence)?.packet ?? null;
  }

  get hasPending(): boolean {
    return this.pending.size > 0;
  }
}
