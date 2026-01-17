package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Core metrics collection for Project Neon networking.
 *
 * <p>Provides atomic counters and statistics for:
 * <ul>
 *   <li>Packet counts (sent, received, dropped, retried)</li>
 *   <li>Byte counts (sent, received)</li>
 *   <li>Error counts by type</li>
 *   <li>Latency tracking (min, max, average)</li>
 *   <li>Connection statistics</li>
 * </ul>
 *
 * <p>All operations are thread-safe and lock-free where possible.
 *
 * <p>Example usage:
 * <pre>{@code
 * NeonMetrics metrics = NeonMetrics.create();
 *
 * // Record packet activity
 * metrics.recordPacketSent(150);  // 150 bytes
 * metrics.recordPacketReceived(200);
 * metrics.recordLatency(45);  // 45ms RTT
 *
 * // Get statistics
 * NeonMetrics.Snapshot snapshot = metrics.snapshot();
 * System.out.println("Packets sent: " + snapshot.packetsSent());
 * System.out.println("Average latency: " + snapshot.averageLatencyMs() + "ms");
 * }</pre>
 *
 * @since 1.1
 */
@PublicAPI
public final class NeonMetrics {

    private final LongAdder packetsSent = new LongAdder();
    private final LongAdder packetsReceived = new LongAdder();
    private final LongAdder packetsDropped = new LongAdder();
    private final LongAdder packetsRetried = new LongAdder();

    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();

    private final LongAdder connectionsAccepted = new LongAdder();
    private final LongAdder connectionsDenied = new LongAdder();
    private final LongAdder disconnections = new LongAdder();
    private final LongAdder reconnections = new LongAdder();

    private final LongAdder acksReceived = new LongAdder();
    private final LongAdder ackTimeouts = new LongAdder();

    private final Map<String, LongAdder> errorCounts = new ConcurrentHashMap<>();

    private final AtomicLong minLatencyNs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatencyNs = new AtomicLong(0);
    private final LongAdder totalLatencyNs = new LongAdder();
    private final LongAdder latencySamples = new LongAdder();

    private final long createdAt;
    private final AtomicLong lastActivityAt;

    private NeonMetrics() {
        this.createdAt = System.nanoTime();
        this.lastActivityAt = new AtomicLong(System.nanoTime());
    }

    /**
     * Creates a new metrics instance.
     */
    public static NeonMetrics create() {
        return new NeonMetrics();
    }

    /**
     * Records a packet being sent.
     *
     * @param sizeBytes the size of the packet in bytes
     */
    public void recordPacketSent(int sizeBytes) {
        packetsSent.increment();
        bytesSent.add(sizeBytes);
        updateLastActivity();
    }

    /**
     * Records a packet being received.
     *
     * @param sizeBytes the size of the packet in bytes
     */
    public void recordPacketReceived(int sizeBytes) {
        packetsReceived.increment();
        bytesReceived.add(sizeBytes);
        updateLastActivity();
    }

    /**
     * Records a packet being dropped (e.g., validation failure, rate limit).
     */
    public void recordPacketDropped() {
        packetsDropped.increment();
    }

    /**
     * Records a packet retry (for reliable delivery).
     */
    public void recordPacketRetried() {
        packetsRetried.increment();
    }

    /**
     * Records a connection being accepted.
     */
    public void recordConnectionAccepted() {
        connectionsAccepted.increment();
        updateLastActivity();
    }

    /**
     * Records a connection being denied.
     */
    public void recordConnectionDenied() {
        connectionsDenied.increment();
    }

    /**
     * Records a disconnection.
     */
    public void recordDisconnection() {
        disconnections.increment();
    }

    /**
     * Records a successful reconnection.
     */
    public void recordReconnection() {
        reconnections.increment();
        updateLastActivity();
    }

    /**
     * Records an ACK being received.
     */
    public void recordAckReceived() {
        acksReceived.increment();
    }

    /**
     * Records an ACK timeout (failed to receive ACK within timeout period).
     */
    public void recordAckTimeout() {
        ackTimeouts.increment();
    }

    /**
     * Records an error by type.
     *
     * @param errorType the type/category of error
     */
    public void recordError(String errorType) {
        errorCounts.computeIfAbsent(errorType, k -> new LongAdder()).increment();
    }

    /**
     * Records a latency measurement in milliseconds.
     *
     * @param latencyMs the round-trip latency in milliseconds
     */
    public void recordLatency(long latencyMs) {
        long latencyNs = latencyMs * 1_000_000;
        recordLatencyNanos(latencyNs);
    }

    /**
     * Records a latency measurement in nanoseconds.
     *
     * @param latencyNs the round-trip latency in nanoseconds
     */
    public void recordLatencyNanos(long latencyNs) {
        totalLatencyNs.add(latencyNs);
        latencySamples.increment();

        long currentMin = minLatencyNs.get();
        while (latencyNs < currentMin) {
            if (minLatencyNs.compareAndSet(currentMin, latencyNs)) {
                break;
            }
            currentMin = minLatencyNs.get();
        }

        long currentMax = maxLatencyNs.get();
        while (latencyNs > currentMax) {
            if (maxLatencyNs.compareAndSet(currentMax, latencyNs)) {
                break;
            }
            currentMax = maxLatencyNs.get();
        }
    }

    private void updateLastActivity() {
        lastActivityAt.set(System.nanoTime());
    }

    /**
     * Resets all metrics to zero.
     */
    public void reset() {
        packetsSent.reset();
        packetsReceived.reset();
        packetsDropped.reset();
        packetsRetried.reset();
        bytesSent.reset();
        bytesReceived.reset();
        connectionsAccepted.reset();
        connectionsDenied.reset();
        disconnections.reset();
        reconnections.reset();
        acksReceived.reset();
        ackTimeouts.reset();
        errorCounts.clear();
        minLatencyNs.set(Long.MAX_VALUE);
        maxLatencyNs.set(0);
        totalLatencyNs.reset();
        latencySamples.reset();
    }

    /**
     * Creates a snapshot of current metrics.
     *
     * @return an immutable snapshot of all metrics
     */
    public Snapshot snapshot() {
        long samples = latencySamples.sum();
        double avgLatencyMs = samples > 0 ? (totalLatencyNs.sum() / (double) samples) / 1_000_000.0 : 0;
        long minLat = minLatencyNs.get();
        long maxLat = maxLatencyNs.get();

        return new Snapshot(
            packetsSent.sum(),
            packetsReceived.sum(),
            packetsDropped.sum(),
            packetsRetried.sum(),
            bytesSent.sum(),
            bytesReceived.sum(),
            connectionsAccepted.sum(),
            connectionsDenied.sum(),
            disconnections.sum(),
            reconnections.sum(),
            acksReceived.sum(),
            ackTimeouts.sum(),
            Map.copyOf(errorCounts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().sum()))),
            minLat == Long.MAX_VALUE ? 0 : minLat / 1_000_000.0,
            maxLat / 1_000_000.0,
            avgLatencyMs,
            samples,
            (System.nanoTime() - createdAt) / 1_000_000_000.0,
            (System.nanoTime() - lastActivityAt.get()) / 1_000_000_000.0
        );
    }

    /**
     * Immutable snapshot of metrics at a point in time.
     */
    @PublicAPI
    public record Snapshot(
        long packetsSent,
        long packetsReceived,
        long packetsDropped,
        long packetsRetried,
        long bytesSent,
        long bytesReceived,
        long connectionsAccepted,
        long connectionsDenied,
        long disconnections,
        long reconnections,
        long acksReceived,
        long ackTimeouts,
        Map<String, Long> errorCounts,
        double minLatencyMs,
        double maxLatencyMs,
        double averageLatencyMs,
        long latencySamples,
        double uptimeSeconds,
        double secondsSinceLastActivity
    ) {
        /**
         * Gets the total number of packets (sent + received).
         */
        public long totalPackets() {
            return packetsSent + packetsReceived;
        }

        /**
         * Gets the total bytes (sent + received).
         */
        public long totalBytes() {
            return bytesSent + bytesReceived;
        }

        /**
         * Gets the packet drop rate as a percentage.
         */
        public double dropRatePercent() {
            long total = packetsSent + packetsReceived;
            if (total == 0) return 0;
            return (packetsDropped * 100.0) / total;
        }

        /**
         * Gets the retry rate as a percentage of packets sent.
         */
        public double retryRatePercent() {
            if (packetsSent == 0) return 0;
            return (packetsRetried * 100.0) / packetsSent;
        }

        /**
         * Gets the average packets per second based on uptime.
         */
        public double packetsPerSecond() {
            if (uptimeSeconds <= 0) return 0;
            return totalPackets() / uptimeSeconds;
        }

        /**
         * Gets the average throughput in bytes per second.
         */
        public double bytesPerSecond() {
            if (uptimeSeconds <= 0) return 0;
            return totalBytes() / uptimeSeconds;
        }

        /**
         * Gets the total error count across all error types.
         */
        public long totalErrors() {
            return errorCounts.values().stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public String toString() {
            return String.format(
                "Metrics[packets=%d/%d, bytes=%d/%d, dropped=%d (%.1f%%), " +
                "latency=%.1f/%.1f/%.1fms, connections=%d/%d, uptime=%.1fs]",
                packetsSent, packetsReceived, bytesSent, bytesReceived,
                packetsDropped, dropRatePercent(),
                minLatencyMs, averageLatencyMs, maxLatencyMs,
                connectionsAccepted, connectionsDenied, uptimeSeconds);
        }
    }
}
