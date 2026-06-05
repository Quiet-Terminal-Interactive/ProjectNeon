package com.quietterminal.neon.core;

import java.util.ArrayList;
import java.util.List;

final class BatchAckManager {

    private final List<Short> pending = new ArrayList<>();

    public synchronized void addAck(short sequence) {
        pending.add(sequence);
    }

    public synchronized PacketPayload.Ack buildAndClear() {
        List<Short> sequences = new ArrayList<>(pending);
        pending.clear();
        return new PacketPayload.Ack(sequences);
    }

    public synchronized boolean hasPending() {
        return !pending.isEmpty();
    }
}
