package com.quietterminal.neon.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class AckStateMachine {

    public record ProcessResult(List<Short> needsRetry, List<Short> failed) {
    }

    private record AckEntry(NeonPacket packet, long sentAt, int retries) {
    }

    private final ConcurrentHashMap<Short, AckEntry> pending = new ConcurrentHashMap<>();
    private final long ackTimeoutMs;
    private final int maxAckRetries;

    public AckStateMachine(long ackTimeoutMs, int maxAckRetries) {
        this.ackTimeoutMs = ackTimeoutMs;
        this.maxAckRetries = maxAckRetries;
    }

    public void track(short sequence, NeonPacket packet) {
        pending.put(sequence, new AckEntry(packet, System.currentTimeMillis(), 0));
    }

    public ProcessResult process() {
        long now = System.currentTimeMillis();
        List<Short> needsRetry = new ArrayList<>();
        List<Short> failed = new ArrayList<>();
        pending.forEach((seq, entry) -> {
            if (now - entry.sentAt() >= ackTimeoutMs) {
                if (entry.retries() >= maxAckRetries) {
                    failed.add(seq);
                } else {
                    needsRetry.add(seq);
                }
            }
        });
        return new ProcessResult(needsRetry, failed);
    }

    public NeonPacket markResent(short sequence) {
        AckEntry entry = pending.get(sequence);
        if (entry == null)
            return null;
        pending.put(sequence, new AckEntry(entry.packet(), System.currentTimeMillis(), entry.retries() + 1));
        return entry.packet();
    }

    public void acknowledge(short sequence) {
        pending.remove(sequence);
    }

    public NeonPacket getPacket(short sequence) {
        AckEntry entry = pending.get(sequence);
        return entry != null ? entry.packet() : null;
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }
}
