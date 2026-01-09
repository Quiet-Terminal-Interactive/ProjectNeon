package com.quietterminal.projectneon.core;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional reliability layer for game packets.
 * Provides ordered, reliable delivery of game packets using sequence numbers and ACKs.
 *
 * <p>Usage example:
 * <pre>{@code
 * ReliablePacketManager reliableManager = new ReliablePacketManager(socket, relayAddr);
 *
 * // Sending a reliable packet
 * byte[] gameData = ...;
 * reliableManager.sendReliable(gameData, destinationClientId);
 *
 * // In your game loop
 * reliableManager.processRetransmissions();
 * }</pre>
 */
public class ReliablePacketManager {
    private static final Logger logger = Logger.getLogger(ReliablePacketManager.class.getName());
    private static final int DEFAULT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_MAX_RETRIES = 5;

    private final NeonSocket socket;
    private final SocketAddress relayAddr;
    private final byte clientId;
    private short nextReliableSequence = 0;

    private final Map<Short, PendingReliablePacket> pendingPackets = new ConcurrentHashMap<>();
    private final Map<Byte, Short> lastReceivedSequence = new ConcurrentHashMap<>();

    private int timeoutMs = DEFAULT_TIMEOUT_MS;
    private int maxRetries = DEFAULT_MAX_RETRIES;

    /**
     * Creates a new reliable packet manager.
     *
     * @param socket The socket to send packets through
     * @param relayAddr The relay address
     * @param clientId This client's ID
     */
    public ReliablePacketManager(NeonSocket socket, SocketAddress relayAddr, byte clientId) {
        this.socket = socket;
        this.relayAddr = relayAddr;
        this.clientId = clientId;
    }

    /**
     * Sends a reliable game packet that will be retransmitted until acknowledged.
     *
     * @param payload The game data to send
     * @param destinationId The destination client ID
     * @return The sequence number assigned to this packet
     * @throws IOException if sending fails
     */
    public short sendReliable(byte[] payload, byte destinationId) throws IOException {
        short sequence = nextReliableSequence++;

        PacketPayload.GamePacket gamePacket = new PacketPayload.GamePacket(payload);
        NeonPacket packet = NeonPacket.create(
            PacketType.GAME_PACKET, sequence, clientId, destinationId, gamePacket
        );

        socket.sendPacket(packet, relayAddr);

        pendingPackets.put(sequence, new PendingReliablePacket(
            packet, System.currentTimeMillis(), 0
        ));

        return sequence;
    }

    /**
     * Processes ACKs and retransmits packets that haven't been acknowledged.
     * Should be called regularly (e.g., in the game loop).
     *
     * @throws IOException if retransmission fails
     */
    public void processRetransmissions() throws IOException {
        long now = System.currentTimeMillis();
        List<Short> toRemove = new ArrayList<>();

        for (Map.Entry<Short, PendingReliablePacket> entry : pendingPackets.entrySet()) {
            PendingReliablePacket pending = entry.getValue();

            if (now - pending.lastSentTime() >= timeoutMs) {
                if (pending.retryCount() >= maxRetries) {
                    logger.log(Level.WARNING, "Reliable packet {0} failed after {1} retries",
                        new Object[]{entry.getKey(), maxRetries});
                    toRemove.add(entry.getKey());
                } else {
                    socket.sendPacket(pending.packet(), relayAddr);
                    pendingPackets.put(entry.getKey(), new PendingReliablePacket(
                        pending.packet(), now, pending.retryCount() + 1
                    ));
                }
            }
        }

        toRemove.forEach(pendingPackets::remove);
    }

    /**
     * Handles an ACK packet for reliable delivery.
     * Call this when you receive an ACK packet in your packet handler.
     *
     * @param acknowledgedSequences List of sequence numbers being acknowledged
     */
    public void handleAck(List<Short> acknowledgedSequences) {
        for (Short seq : acknowledgedSequences) {
            if (pendingPackets.remove(seq) != null) {
                logger.log(Level.FINE, "Reliable packet {0} acknowledged", seq);
            }
        }
    }

    /**
     * Checks if a received packet is a duplicate based on sequence number.
     * Also sends an ACK for the received packet.
     *
     * @param fromClientId The sender's client ID
     * @param sequence The packet sequence number
     * @return true if this is a duplicate packet that should be ignored
     * @throws IOException if sending ACK fails
     */
    public boolean handleReceivedReliable(byte fromClientId, short sequence) throws IOException {
        Short lastSeq = lastReceivedSequence.get(fromClientId);

        sendAckFor(sequence);

        if (lastSeq != null && sequence <= lastSeq) {
            return true;
        }

        lastReceivedSequence.put(fromClientId, sequence);
        return false;
    }

    private void sendAckFor(short sequence) throws IOException {
        PacketPayload.Ack ack = new PacketPayload.Ack(List.of(sequence));
        NeonPacket packet = NeonPacket.create(
            PacketType.ACK, (short) 0, clientId, (byte) 0, ack
        );
        socket.sendPacket(packet, relayAddr);
    }

    /**
     * Sets the timeout for retransmissions.
     *
     * @param timeoutMs Timeout in milliseconds
     */
    public void setTimeout(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Sets the maximum number of retries before giving up.
     *
     * @param maxRetries Maximum retry count
     */
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * Returns the number of packets pending acknowledgment.
     *
     * @return Pending packet count
     */
    public int getPendingCount() {
        return pendingPackets.size();
    }

    /**
     * Tracks a packet awaiting acknowledgment.
     */
    private record PendingReliablePacket(
        NeonPacket packet,
        long lastSentTime,
        int retryCount
    ) {}
}
