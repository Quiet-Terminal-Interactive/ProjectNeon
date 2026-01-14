package com.quietterminal.projectneon.core;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages batched ACK processing to reduce packet overhead.
 * Collects multiple ACK sequence numbers and sends them in a single packet.
 */
public class BatchAckManager {
    private final NeonSocket socket;
    private final SocketAddress relayAddr;
    private final byte clientId;
    private final ConcurrentLinkedQueue<Short> pendingAcks;
    private final int maxBatchSize;
    private final long maxBatchDelayMs;
    private long lastFlushTime;

    /**
     * Creates a batch ACK manager.
     *
     * @param socket the socket to send ACKs through
     * @param relayAddr the relay address
     * @param clientId the client ID sending ACKs
     * @param maxBatchSize maximum number of ACKs to batch
     * @param maxBatchDelayMs maximum time to wait before flushing
     */
    public BatchAckManager(NeonSocket socket, SocketAddress relayAddr, byte clientId,
                          int maxBatchSize, long maxBatchDelayMs) {
        this.socket = socket;
        this.relayAddr = relayAddr;
        this.clientId = clientId;
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayMs = maxBatchDelayMs;
        this.pendingAcks = new ConcurrentLinkedQueue<>();
        this.lastFlushTime = System.currentTimeMillis();
    }

    /**
     * Queues a sequence number for ACK batching.
     *
     * @param sequence the sequence number to acknowledge
     */
    public void queueAck(short sequence) {
        pendingAcks.offer(sequence);
    }

    /**
     * Flushes pending ACKs if batch is full or delay exceeded.
     *
     * @return number of ACKs sent
     * @throws IOException if send fails
     */
    public int flushIfNeeded() throws IOException {
        long now = System.currentTimeMillis();
        boolean timeoutExceeded = (now - lastFlushTime) >= maxBatchDelayMs;
        boolean batchFull = pendingAcks.size() >= maxBatchSize;

        if ((timeoutExceeded || batchFull) && !pendingAcks.isEmpty()) {
            return flush();
        }

        return 0;
    }

    /**
     * Forces immediate flush of all pending ACKs.
     *
     * @return number of ACKs sent
     * @throws IOException if send fails
     */
    public int flush() throws IOException {
        if (pendingAcks.isEmpty()) {
            return 0;
        }

        List<Short> batch = new ArrayList<>();
        Short seq;
        while ((seq = pendingAcks.poll()) != null && batch.size() < maxBatchSize) {
            batch.add(seq);
        }

        if (!batch.isEmpty()) {
            PacketPayload.Ack ack = new PacketPayload.Ack(batch);
            NeonPacket packet = NeonPacket.create(
                PacketType.ACK, (short) 0, clientId, (byte) 1, ack
            );
            socket.sendPacket(packet, relayAddr);
            lastFlushTime = System.currentTimeMillis();
        }

        return batch.size();
    }

    /**
     * Gets the number of pending ACKs waiting to be sent.
     *
     * @return pending ACK count
     */
    public int getPendingCount() {
        return pendingAcks.size();
    }
}
