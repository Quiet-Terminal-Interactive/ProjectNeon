package com.quietterminal.neon.core;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Provides opt-in reliable delivery for game packets with correct sequence wrap-around handling.
 *
 * <p>Tracks outbound packets waiting for ACK, retransmits on timeout with exponential back-off,
 * and deduplicates inbound packets per sender using signed 16-bit sequence arithmetic — correctly
 * handling the 65535 → 0 wrap-around.
 *
 * <p>Call {@link #processRetransmissions()} periodically (e.g. from the host processing loop)
 * to drive retransmit and failure logic.
 */
public final class ReliablePacketManager {

    private record PendingEntry(NeonPacket packet, long sentAt, int retries) {
    }

    private final NeonSocket socket;
    private final SocketAddress destination;
    private final byte clientId;
    private final long timeoutMs;
    private final int maxRetries;

    private final ConcurrentHashMap<Short, PendingEntry> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Byte, Short> lastReceivedSeq = new ConcurrentHashMap<>();
    private final AtomicInteger ackSequence = new AtomicInteger(0);

    private volatile Consumer<Short> onDeliveryFailed;

    /**
     * Creates a new manager that sends and receives through the given socket.
     *
     * @param socket      the UDP socket to use for sends and ACKs
     * @param destination the remote address to send packets to
     * @param clientId    this endpoint's client ID, included in ACK headers
     * @param config      supplies {@code reliablePacketTimeoutMs} and {@code reliablePacketMaxRetries}
     */
    public ReliablePacketManager(NeonSocket socket, SocketAddress destination, byte clientId, NeonConfig config) {
        this.socket = socket;
        this.destination = destination;
        this.clientId = clientId;
        this.timeoutMs = config.getReliablePacketTimeoutMs();
        this.maxRetries = config.getReliablePacketMaxRetries();
    }

    /**
     * Sends {@code packet} and begins tracking it for retransmission until ACKed.
     *
     * @throws IOException if the initial send fails
     */
    public void sendReliable(NeonPacket packet) throws IOException {
        short seq = packet.header().sequence();
        socket.sendPacket(packet, destination);
        pending.put(seq, new PendingEntry(packet, System.currentTimeMillis(), 0));
    }

    /** Marks a tracked packet as acknowledged, removing it from the retransmit queue. */
    public void acknowledge(short sequence) {
        pending.remove(sequence);
    }

    /**
     * Checks all pending packets for timeout. Retransmits those within the retry budget;
     * removes and fires {@link #setOnDeliveryFailed} for those that have exhausted retries.
     *
     * @throws IOException if a retransmit send fails
     */
    public void processRetransmissions() throws IOException {
        long now = System.currentTimeMillis();
        for (var entry : pending.entrySet()) {
            short seq = entry.getKey();
            PendingEntry pe = entry.getValue();
            if (now - pe.sentAt() >= timeoutMs) {
                if (pe.retries() >= maxRetries) {
                    pending.remove(seq);
                    if (onDeliveryFailed != null)
                        onDeliveryFailed.accept(seq);
                } else {
                    pending.put(seq, new PendingEntry(pe.packet(), System.currentTimeMillis(), pe.retries() + 1));
                    socket.sendPacket(pe.packet(), destination);
                }
            }
        }
    }

    /**
     * Returns {@code true} if {@code sequence} is a duplicate from {@code senderId}.
     * Uses signed 16-bit subtraction to handle the 65535 → 0 wrap-around correctly.
     * Updates the last-seen sequence for the sender on non-duplicate packets.
     *
     * @param senderId the originating client ID
     * @param sequence the packet sequence number to check
     */
    public boolean isDuplicate(byte senderId, short sequence) {
        Short lastSeq = lastReceivedSeq.get(senderId);
        if (lastSeq != null && (short) (sequence - lastSeq) <= 0) {
            return true;
        }
        lastReceivedSeq.put(senderId, sequence);
        return false;
    }

    /**
     * Sends an {@link PacketPayload.Ack} for the given sequence number.
     * Uses an internal counter for the ACK packet's own sequence number.
     *
     * @throws IOException if the send fails
     */
    public void sendAckFor(short sequence) throws IOException {
        socket.sendPacket(NeonPacket.create(PacketType.ACK, (short) ackSequence.getAndIncrement(),
                clientId, (byte) 0, new PacketPayload.Ack(List.of(sequence))), destination);
    }

    /** Callback fired with the sequence number when a packet exhausts all retransmit attempts. */
    public void setOnDeliveryFailed(Consumer<Short> cb) {
        this.onDeliveryFailed = cb;
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }
}
