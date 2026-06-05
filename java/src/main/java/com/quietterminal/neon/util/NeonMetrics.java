package com.quietterminal.neon.util;

import java.util.concurrent.atomic.LongAdder;

/** Lock-free packet counters for monitoring relay and host throughput. */
public final class NeonMetrics {

    private final LongAdder packetsReceived = new LongAdder();
    private final LongAdder packetsSent = new LongAdder();
    private final LongAdder packetsDropped = new LongAdder();
    private final LongAdder packetsRateLimited = new LongAdder();
    private final LongAdder retransmits = new LongAdder();

    public void recordReceived() {
        packetsReceived.increment();
    }

    public void recordSent() {
        packetsSent.increment();
    }

    public void recordDropped() {
        packetsDropped.increment();
    }

    public void recordRateLimited() {
        packetsRateLimited.increment();
    }

    public void recordRetransmit() {
        retransmits.increment();
    }

    public long getPacketsReceived() {
        return packetsReceived.sum();
    }

    public long getPacketsSent() {
        return packetsSent.sum();
    }

    public long getPacketsDropped() {
        return packetsDropped.sum();
    }

    public long getPacketsRateLimited() {
        return packetsRateLimited.sum();
    }

    public long getRetransmits() {
        return retransmits.sum();
    }

    public void reset() {
        packetsReceived.reset();
        packetsSent.reset();
        packetsDropped.reset();
        packetsRateLimited.reset();
        retransmits.reset();
    }
}
