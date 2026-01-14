package com.quietterminal.projectneon.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks performance metrics for Neon protocol operations.
 * Thread-safe counters for measuring throughput, latency, and resource usage.
 */
public class PerformanceMetrics {
    private final LongAdder packetsSent = new LongAdder();
    private final LongAdder packetsReceived = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();
    private final LongAdder packetsDropped = new LongAdder();
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    private final LatencyTracker latencyTracker = new LatencyTracker();

    public void recordPacketSent(int bytes) {
        packetsSent.increment();
        bytesSent.add(bytes);
    }

    public void recordPacketReceived(int bytes) {
        packetsReceived.increment();
        bytesReceived.add(bytes);
    }

    public void recordPacketDropped() {
        packetsDropped.increment();
    }

    public void recordLatency(long latencyMs) {
        latencyTracker.record(latencyMs);
    }

    public long getPacketsSent() {
        return packetsSent.sum();
    }

    public long getPacketsReceived() {
        return packetsReceived.sum();
    }

    public long getBytesSent() {
        return bytesSent.sum();
    }

    public long getBytesReceived() {
        return bytesReceived.sum();
    }

    public long getPacketsDropped() {
        return packetsDropped.sum();
    }

    public double getPacketsPerSecond() {
        long elapsedMs = System.currentTimeMillis() - startTime.get();
        if (elapsedMs == 0) return 0;
        return (packetsSent.sum() + packetsReceived.sum()) * 1000.0 / elapsedMs;
    }

    public double getThroughputMbps() {
        long elapsedMs = System.currentTimeMillis() - startTime.get();
        if (elapsedMs == 0) return 0;
        long totalBytes = bytesSent.sum() + bytesReceived.sum();
        return (totalBytes * 8.0 * 1000.0) / (elapsedMs * 1024.0 * 1024.0);
    }

    public LatencyStats getLatencyStats() {
        return latencyTracker.getStats();
    }

    public void reset() {
        packetsSent.reset();
        packetsReceived.reset();
        bytesSent.reset();
        bytesReceived.reset();
        packetsDropped.reset();
        latencyTracker.reset();
        startTime.set(System.currentTimeMillis());
    }

    public String getSummary() {
        return String.format(
            "Packets: sent=%d, received=%d, dropped=%d | " +
            "Throughput: %.2f pps, %.2f Mbps | " +
            "Latency: p50=%.1fms, p95=%.1fms, p99=%.1fms",
            getPacketsSent(), getPacketsReceived(), getPacketsDropped(),
            getPacketsPerSecond(), getThroughputMbps(),
            getLatencyStats().p50(), getLatencyStats().p95(), getLatencyStats().p99()
        );
    }

    public record LatencyStats(double p50, double p95, double p99, double avg, long min, long max) {}

    private static class LatencyTracker {
        private final long[] samples = new long[1000];
        private int index = 0;
        private int count = 0;

        public synchronized void record(long latencyMs) {
            samples[index] = latencyMs;
            index = (index + 1) % samples.length;
            if (count < samples.length) {
                count++;
            }
        }

        public synchronized LatencyStats getStats() {
            if (count == 0) {
                return new LatencyStats(0, 0, 0, 0, 0, 0);
            }

            long[] sorted = new long[count];
            System.arraycopy(samples, 0, sorted, 0, count);
            java.util.Arrays.sort(sorted);

            long sum = 0;
            long min = sorted[0];
            long max = sorted[count - 1];
            for (int i = 0; i < count; i++) {
                sum += sorted[i];
            }

            return new LatencyStats(
                sorted[count / 2],
                sorted[(int)(count * 0.95)],
                sorted[(int)(count * 0.99)],
                sum / (double)count,
                min,
                max
            );
        }

        public synchronized void reset() {
            index = 0;
            count = 0;
        }
    }
}
