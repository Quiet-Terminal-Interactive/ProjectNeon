import { NeonSocket, Address } from "./_socket";
import { NeonConfig } from "./_config";
import { NeonPacket, PacketType, AckPayload, makePacket, signed16 } from "./_protocol";

interface PendingEntry {
  packet: NeonPacket;
  sentAt: number;
  retries: number;
}

export class ReliablePacketManager {
  private pending = new Map<number, PendingEntry>();
  private lastReceivedSeq = new Map<number, number>();
  private ackSequence = 0;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private onDeliveryFailed: ((seq: number) => void) | null = null;

  constructor(
    private socket: NeonSocket,
    private destination: Address,
    private clientId: number,
    config: NeonConfig
  ) {
    this.timeoutMs = config.reliablePacketTimeoutMs;
    this.maxRetries = config.reliablePacketMaxRetries;
  }

  sendReliable(packet: NeonPacket): void {
    const seq = packet.header.sequence;
    this.socket.sendPacket(packet, this.destination);
    this.pending.set(seq, { packet, sentAt: Date.now(), retries: 0 });
  }

  acknowledge(sequence: number): void {
    this.pending.delete(sequence);
  }

  processRetransmissions(): void {
    const now = Date.now();
    const toFail: number[] = [];
    const toRetry: number[] = [];

    for (const [seq, entry] of this.pending) {
      if (now - entry.sentAt >= this.timeoutMs) {
        if (entry.retries >= this.maxRetries) {
          toFail.push(seq);
        } else {
          toRetry.push(seq);
        }
      }
    }

    for (const seq of toFail) {
      this.pending.delete(seq);
      this.onDeliveryFailed?.(seq);
    }

    for (const seq of toRetry) {
      const entry = this.pending.get(seq)!;
      this.pending.set(seq, { packet: entry.packet, sentAt: now, retries: entry.retries + 1 });
      this.socket.sendPacket(entry.packet, this.destination);
    }
  }

  isDuplicate(senderId: number, sequence: number): boolean {
    const last = this.lastReceivedSeq.get(senderId);
    if (last !== undefined && signed16(sequence - last) <= 0) return true;
    this.lastReceivedSeq.set(senderId, sequence);
    return false;
  }

  sendAckFor(sequence: number): void {
    const seq = this.ackSequence & 0xffff;
    this.ackSequence++;
    const payload: AckPayload = { sequences: [sequence] };
    const pkt = makePacket(PacketType.ACK, seq, this.clientId, 0, payload);
    this.socket.sendPacket(pkt, this.destination);
  }

  get hasPending(): boolean {
    return this.pending.size > 0;
  }

  setOnDeliveryFailed(cb: ((seq: number) => void) | null): void {
    this.onDeliveryFailed = cb;
  }
}
