package com.quietterminal.projectneon.relay;

import com.quietterminal.projectneon.core.NeonPacket;
import com.quietterminal.projectneon.core.PacketHeader;

import java.net.SocketAddress;
import java.util.Optional;

/**
 * Defines the honest relay semantics for packet forwarding.
 *
 * <p>Honest relay guarantees:
 * <ul>
 *   <li>Packets are forwarded without payload modification</li>
 *   <li>Headers are preserved exactly as received (except for routing metadata)</li>
 *   <li>Routing decisions are deterministic and based solely on header fields</li>
 *   <li>No packet reordering within a single source-destination pair</li>
 *   <li>Delivery failures are reported, not silently dropped</li>
 * </ul>
 */
public class RelaySemantics {

    /**
     * Result of a routing decision.
     */
    public sealed interface RoutingDecision {
        /**
         * Packet should be forwarded to a single destination.
         */
        record Unicast(SocketAddress destination, NeonPacket packet) implements RoutingDecision {}

        /**
         * Packet should be broadcast to multiple destinations.
         */
        record Broadcast(java.util.List<SocketAddress> destinations, NeonPacket packet) implements RoutingDecision {}

        /**
         * Packet cannot be routed - destination not found.
         */
        record Unroutable(byte destinationId, String reason) implements RoutingDecision {}

        /**
         * Packet is a control packet handled by the relay itself.
         */
        record RelayHandled(String action) implements RoutingDecision {}
    }

    /**
     * Determines the routing decision for a packet.
     *
     * @param packet The packet to route
     * @param source The source address
     * @param sessionLookup Function to look up peer addresses
     * @return The routing decision
     */
    public RoutingDecision determineRouting(
            NeonPacket packet,
            SocketAddress source,
            PeerLookup sessionLookup) {

        PacketHeader header = packet.header();
        byte destId = header.destinationId();

        Optional<Integer> sessionId = sessionLookup.getSessionForPeer(source);
        if (sessionId.isEmpty()) {
            return new RoutingDecision.Unroutable(destId, "Source not in any session");
        }

        int session = sessionId.get();

        if (destId == 0) {
            java.util.List<SocketAddress> targets = sessionLookup.getAllPeersExcept(session, source);
            if (targets.isEmpty()) {
                return new RoutingDecision.Unroutable(destId, "No other peers in session");
            }
            return new RoutingDecision.Broadcast(targets, packet);
        } else {
            Optional<SocketAddress> target = sessionLookup.getPeerAddress(session, destId);
            if (target.isEmpty()) {
                return new RoutingDecision.Unroutable(destId, "Destination client not found in session");
            }
            return new RoutingDecision.Unicast(target.get(), packet);
        }
    }

    /**
     * Validates that a packet meets relay forwarding requirements.
     *
     * @param packet The packet to validate
     * @return Empty if valid, error message if invalid
     */
    public Optional<String> validateForForwarding(NeonPacket packet) {
        PacketHeader header = packet.header();

        if (header.magic() != PacketHeader.MAGIC) {
            return Optional.of("Invalid magic number");
        }

        if (packet.payload() == null) {
            return Optional.of("Null payload");
        }

        return Optional.empty();
    }

    /**
     * Interface for looking up peer information in sessions.
     */
    public interface PeerLookup {
        Optional<Integer> getSessionForPeer(SocketAddress addr);
        Optional<SocketAddress> getPeerAddress(int sessionId, byte clientId);
        java.util.List<SocketAddress> getAllPeersExcept(int sessionId, SocketAddress exclude);
    }
}
