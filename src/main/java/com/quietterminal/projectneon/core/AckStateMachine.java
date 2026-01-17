package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.util.LoggerConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized state machine for managing acknowledgments in reliable packet delivery.
 *
 * <p>This class provides a unified ACK tracking mechanism that can be used by both
 * hosts and clients. It handles:
 * <ul>
 *   <li>Tracking packets awaiting acknowledgment</li>
 *   <li>Timeout detection and retransmission signaling</li>
 *   <li>ACK reception and pending packet removal</li>
 *   <li>Retry counting and failure detection</li>
 * </ul>
 *
 * <p>The state machine operates on the following states for each tracked packet:
 * <pre>
 *   PENDING -> (timeout) -> RETRY_NEEDED -> (resent) -> PENDING
 *                                        -> (max retries) -> FAILED
 *          -> (ack received) -> ACKNOWLEDGED
 * </pre>
 */
public class AckStateMachine {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(AckStateMachine.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    /**
     * State of a tracked packet.
     */
    public enum PacketState {
        PENDING,
        RETRY_NEEDED,
        ACKNOWLEDGED,
        FAILED
    }

    /**
     * Information about a packet awaiting acknowledgment.
     */
    public record PendingPacket(
        short sequence,
        NeonPacket packet,
        long sentTime,
        int retryCount,
        PacketState state
    ) {
        /**
         * Creates a new pending packet with incremented retry count and updated sent time.
         */
        public PendingPacket withRetry(long newSentTime) {
            return new PendingPacket(sequence, packet, newSentTime, retryCount + 1, PacketState.PENDING);
        }

        /**
         * Creates a copy with a new state.
         */
        public PendingPacket withState(PacketState newState) {
            return new PendingPacket(sequence, packet, sentTime, retryCount, newState);
        }
    }

    /**
     * Result of processing pending packets.
     */
    public record ProcessResult(
        List<PendingPacket> needsRetry,
        List<PendingPacket> failed
    ) {}

    private final Map<Short, PendingPacket> pendingPackets = new ConcurrentHashMap<>();
    private final int timeoutMs;
    private final int maxRetries;

    private Consumer<PendingPacket> onAcknowledged;
    private Consumer<PendingPacket> onFailed;

    /**
     * Creates an ACK state machine with the specified timeout and retry settings.
     *
     * @param timeoutMs Milliseconds before a packet is considered timed out
     * @param maxRetries Maximum number of retries before marking as failed
     */
    public AckStateMachine(int timeoutMs, int maxRetries) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
    }

    /**
     * Creates an ACK state machine from NeonConfig settings.
     *
     * @param config The configuration to use
     * @param useHostSettings If true, uses host ACK settings; otherwise uses reliable packet settings
     */
    public static AckStateMachine fromConfig(NeonConfig config, boolean useHostSettings) {
        if (useHostSettings) {
            return new AckStateMachine(config.getHostAckTimeoutMs(), config.getHostMaxAckRetries());
        } else {
            return new AckStateMachine(config.getReliablePacketTimeoutMs(), config.getReliablePacketMaxRetries());
        }
    }

    /**
     * Tracks a packet for acknowledgment.
     *
     * @param sequence The sequence number to track
     * @param packet The packet that was sent
     */
    public void track(short sequence, NeonPacket packet) {
        PendingPacket pending = new PendingPacket(
            sequence, packet, System.currentTimeMillis(), 0, PacketState.PENDING
        );
        pendingPackets.put(sequence, pending);
        logger.log(Level.FINE, "Tracking packet with sequence {0}", sequence);
    }

    /**
     * Records that an ACK was received for a sequence number.
     *
     * @param sequence The acknowledged sequence number
     * @return true if the sequence was being tracked, false otherwise
     */
    public boolean acknowledge(short sequence) {
        PendingPacket removed = pendingPackets.remove(sequence);
        if (removed != null) {
            logger.log(Level.FINE, "ACK received for sequence {0}", sequence);
            if (onAcknowledged != null) {
                onAcknowledged.accept(removed.withState(PacketState.ACKNOWLEDGED));
            }
            return true;
        }
        return false;
    }

    /**
     * Records that ACKs were received for multiple sequence numbers.
     *
     * @param sequences The acknowledged sequence numbers
     * @return The number of sequences that were being tracked
     */
    public int acknowledgeAll(List<Short> sequences) {
        int count = 0;
        for (Short seq : sequences) {
            if (acknowledge(seq)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Processes all pending packets, checking for timeouts.
     *
     * @return Result containing packets that need retry and packets that have failed
     */
    public ProcessResult process() {
        long now = System.currentTimeMillis();
        List<PendingPacket> needsRetry = new ArrayList<>();
        List<PendingPacket> failed = new ArrayList<>();
        List<Short> toRemove = new ArrayList<>();

        for (Map.Entry<Short, PendingPacket> entry : pendingPackets.entrySet()) {
            PendingPacket pending = entry.getValue();

            if (now - pending.sentTime() >= timeoutMs) {
                if (pending.retryCount() >= maxRetries) {
                    logger.log(Level.WARNING, "Packet {0} failed after {1} retries",
                        new Object[]{pending.sequence(), maxRetries});
                    failed.add(pending.withState(PacketState.FAILED));
                    toRemove.add(entry.getKey());
                    if (onFailed != null) {
                        onFailed.accept(pending.withState(PacketState.FAILED));
                    }
                } else {
                    needsRetry.add(pending.withState(PacketState.RETRY_NEEDED));
                }
            }
        }

        toRemove.forEach(pendingPackets::remove);
        return new ProcessResult(needsRetry, failed);
    }

    /**
     * Marks a packet as resent, updating its timestamp and retry count.
     *
     * @param sequence The sequence number that was resent
     */
    public void markResent(short sequence) {
        PendingPacket existing = pendingPackets.get(sequence);
        if (existing != null) {
            pendingPackets.put(sequence, existing.withRetry(System.currentTimeMillis()));
            logger.log(Level.FINE, "Packet {0} marked as resent (retry {1})",
                new Object[]{sequence, existing.retryCount() + 1});
        }
    }

    /**
     * Removes a packet from tracking without acknowledging it.
     *
     * @param sequence The sequence number to remove
     * @return The removed packet, or null if not found
     */
    public PendingPacket remove(short sequence) {
        return pendingPackets.remove(sequence);
    }

    /**
     * Checks if a sequence number is currently being tracked.
     *
     * @param sequence The sequence number to check
     * @return true if the sequence is being tracked
     */
    public boolean isTracking(short sequence) {
        return pendingPackets.containsKey(sequence);
    }

    /**
     * Gets the number of packets currently awaiting acknowledgment.
     *
     * @return The count of pending packets
     */
    public int pendingCount() {
        return pendingPackets.size();
    }

    /**
     * Checks if there are any packets awaiting acknowledgment.
     *
     * @return true if there are pending packets
     */
    public boolean hasPending() {
        return !pendingPackets.isEmpty();
    }

    /**
     * Gets all currently pending packets.
     *
     * @return Unmodifiable view of pending packets
     */
    public Collection<PendingPacket> getPendingPackets() {
        return Collections.unmodifiableCollection(pendingPackets.values());
    }

    /**
     * Clears all pending packets.
     */
    public void clear() {
        pendingPackets.clear();
    }

    /**
     * Sets a callback to be invoked when a packet is successfully acknowledged.
     *
     * @param callback The callback to invoke
     */
    public void setOnAcknowledged(Consumer<PendingPacket> callback) {
        this.onAcknowledged = callback;
    }

    /**
     * Sets a callback to be invoked when a packet fails (exceeds max retries).
     *
     * @param callback The callback to invoke
     */
    public void setOnFailed(Consumer<PendingPacket> callback) {
        this.onFailed = callback;
    }

    /**
     * Gets the configured timeout in milliseconds.
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Gets the configured maximum retry count.
     */
    public int getMaxRetries() {
        return maxRetries;
    }
}
