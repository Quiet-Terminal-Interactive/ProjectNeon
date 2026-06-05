package com.quietterminal.neon.relay;

import com.quietterminal.neon.core.NeonPacket;

import java.net.SocketAddress;
import java.util.List;

final class RelaySemantics {

    public sealed interface RoutingDecision
            permits RoutingDecision.Unicast, RoutingDecision.Broadcast,
            RoutingDecision.Unroutable, RoutingDecision.RelayHandled {

        record Unicast(SocketAddress destination) implements RoutingDecision {
        }

        record Broadcast(List<SocketAddress> destinations) implements RoutingDecision {
        }

        record Unroutable(String reason) implements RoutingDecision {
        }

        record RelayHandled() implements RoutingDecision {
        }
    }

    public RoutingDecision determineRouting(NeonPacket packet, SocketAddress source, SessionManager sessionManager) {
        Integer sessionId = sessionManager.getSessionForPeer(source);
        if (sessionId == null) {
            return new RoutingDecision.Unroutable("Source not in any session");
        }

        byte destId = packet.header().destinationId();
        if (destId == 0) {
            List<SocketAddress> destinations = sessionManager.getAllPeersExcept(sessionId, source);
            if (destinations.isEmpty()) {
                return new RoutingDecision.Unroutable("No peers to broadcast to");
            }
            return new RoutingDecision.Broadcast(destinations);
        } else {
            SocketAddress dest = sessionManager.getPeerAddress(sessionId, destId);
            if (dest == null) {
                return new RoutingDecision.Unroutable("Destination client " + (destId & 0xFF) + " not found");
            }
            return new RoutingDecision.Unicast(dest);
        }
    }
}
