package com.quietterminal.projectneon.relay;

import com.quietterminal.projectneon.core.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neon protocol relay server.
 * Routes packets between hosts and clients in a completely payload-agnostic manner.
 */
public class NeonRelay implements AutoCloseable {
    private static final int DEFAULT_PORT = 7777;
    private static final long CLEANUP_INTERVAL_MS = 5000;
    private static final long CLIENT_TIMEOUT_MS = 15000;
    private static final int MAX_PACKETS_PER_SECOND = 100;
    private static final int MAX_CLIENTS_PER_SESSION = 32;

    private final NeonSocket socket;
    private final SessionManager sessionManager;
    private final Map<SocketAddress, PendingConnection> pendingConnections;
    private final Map<SocketAddress, RateLimiter> rateLimiters;
    private long lastCleanupTime;

    public NeonRelay(String bindAddress) throws IOException {
        String[] parts = bindAddress.split(":");
        int port = parts.length == 2 ? Integer.parseInt(parts[1]) : DEFAULT_PORT;

        this.socket = new NeonSocket(port);
        this.socket.setBlocking(true);
        this.socket.setSoTimeout(100); // 100ms timeout for responsive processing
        this.sessionManager = new SessionManager();
        this.pendingConnections = new ConcurrentHashMap<>();
        this.rateLimiters = new ConcurrentHashMap<>();
        this.lastCleanupTime = System.currentTimeMillis();

        System.out.println("Relay listening on " + socket.getLocalAddress());
    }

    /**
     * Starts the relay in a blocking loop.
     */
    public void start() throws IOException, InterruptedException {
        while (true) {
            processPackets();
            performCleanup();
            Thread.sleep(1);
        }
    }

    /**
     * Processes incoming packets. Returns the number of packets processed.
     */
    public int processPackets() throws IOException {
        int count = 0;
        while (true) {
            try {
                NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
                if (received == null) break;

                handlePacket(received.packet(), received.source());
                count++;
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
        }
        return count;
    }

    private void handlePacket(NeonPacket packet, SocketAddress source) throws IOException {
        // Rate limiting check
        RateLimiter limiter = rateLimiters.computeIfAbsent(source,
            k -> new RateLimiter(MAX_PACKETS_PER_SECOND));

        if (!limiter.allowPacket()) {
            System.err.println("Rate limit exceeded for " + source);
            return;
        }

        PacketHeader header = packet.header();

        if (header.magic() != PacketHeader.MAGIC) {
            System.err.println("Invalid magic number from " + source);
            return;
        }

        switch (packet.payload()) {
            case PacketPayload.ConnectRequest request -> handleConnectRequest(request, source);
            case PacketPayload.ConnectAccept accept -> handleConnectAccept(accept, source, header);
            default -> {
                routePacket(packet, source);
            }
        }
    }

    private void handleConnectRequest(PacketPayload.ConnectRequest request, SocketAddress source) throws IOException {
        int sessionId = request.targetSessionId();

        // Check if session has reached maximum client capacity
        int currentClients = sessionManager.getClientCount(sessionId);
        if (currentClients >= MAX_CLIENTS_PER_SESSION) {
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny("Session is full");
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_DENY.getValue(), (short) 0, (byte) 0, (byte) 0
            );
            NeonPacket denyPacket = new NeonPacket(header, deny);
            socket.sendPacket(denyPacket, source);
            System.err.println("Connection denied for " + source + ": session " + sessionId + " is full (" + currentClients + "/" + MAX_CLIENTS_PER_SESSION + ")");
            return;
        }

        pendingConnections.put(source, new PendingConnection(
            sessionId, request.desiredName(), Instant.now()
        ));

        Optional<SocketAddress> hostAddr = sessionManager.getHost(sessionId);
        if (hostAddr.isPresent()) {
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_REQUEST.getValue(), (short) 0, (byte) 0, (byte) 1
            );
            NeonPacket forwardPacket = new NeonPacket(header, request);
            socket.sendPacket(forwardPacket, hostAddr.get());
        } else {
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny("Session not found");
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_DENY.getValue(), (short) 0, (byte) 0, (byte) 0
            );
            NeonPacket denyPacket = new NeonPacket(header, deny);
            socket.sendPacket(denyPacket, source);
        }
    }

    private void handleConnectAccept(PacketPayload.ConnectAccept accept, SocketAddress source, PacketHeader header) throws IOException {
        int sessionId = accept.sessionId();
        byte clientId = accept.assignedClientId();

        if (clientId == 1) {
            sessionManager.registerHost(sessionId, source);
            System.out.println("Host registered for session " + sessionId + " from " + source);
        } else {
            PendingConnection pending = findPendingBySessionId(sessionId);
            if (pending != null) {
                sessionManager.registerPeer(sessionId, clientId, source, false);
                pendingConnections.remove(source);
                System.out.println("Client " + clientId + " joined session " + sessionId);
            }

            routeToClient(sessionId, clientId, accept, header);
        }
    }

    @SuppressWarnings("unused")
    private void routePacket(NeonPacket packet, SocketAddress source) throws IOException {
        PacketHeader header = packet.header();
        byte clientId = header.clientId();
        byte destId = header.destinationId();

        sessionManager.updateLastSeen(source);

        Optional<Integer> sessionId = sessionManager.getSessionForPeer(source);
        if (sessionId.isEmpty()) {
            System.err.println("No session found for peer " + source);
            return;
        }

        int session = sessionId.get();

        if (destId == 0) {
            List<PeerInfo> peers = sessionManager.getPeers(session);
            for (PeerInfo peer : peers) {
                if (!peer.addr().equals(source)) {
                    socket.sendPacket(packet, peer.addr());
                }
            }
        } else {
            Optional<SocketAddress> targetAddr = sessionManager.getPeerAddress(session, destId);
            if (targetAddr.isPresent()) {
                socket.sendPacket(packet, targetAddr.get());
            } else {
                System.err.println("Target client " + destId + " not found in session " + session);
            }
        }
    }

    private void routeToClient(int sessionId, byte clientId, PacketPayload payload, PacketHeader originalHeader) throws IOException {
        Optional<SocketAddress> addr = sessionManager.getPeerAddress(sessionId, clientId);
        if (addr.isPresent()) {
            PacketHeader header = PacketHeader.create(
                originalHeader.packetType(), originalHeader.sequence(), originalHeader.clientId(), clientId
            );
            NeonPacket packet = new NeonPacket(header, payload);
            socket.sendPacket(packet, addr.get());
        }
    }

    private PendingConnection findPendingBySessionId(int sessionId) {
        for (PendingConnection pending : pendingConnections.values()) {
            if (pending.sessionId() == sessionId) {
                return pending;
            }
        }
        return null;
    }

    private void performCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }

        sessionManager.cleanupStale(CLIENT_TIMEOUT_MS);
        lastCleanupTime = now;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /**
     * Tracks pending client connections.
     */
    private record PendingConnection(int sessionId, String name, Instant requestTime) {}
}

/**
 * Manages sessions and peer routing.
 */
class SessionManager {
    private final Map<Integer, List<PeerInfo>> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, SocketAddress> hosts = new ConcurrentHashMap<>();
    private final Map<SocketAddress, PeerInfo> peerLookup = new ConcurrentHashMap<>();

    public void registerHost(int sessionId, SocketAddress addr) {
        hosts.put(sessionId, addr);
        registerPeer(sessionId, (byte) 1, addr, true);
    }

    public void registerPeer(int sessionId, byte clientId, SocketAddress addr, boolean isHost) {
        PeerInfo peer = new PeerInfo(addr, clientId, sessionId, Instant.now(), isHost);
        sessions.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(peer);
        peerLookup.put(addr, peer);
    }

    public Optional<SocketAddress> getHost(int sessionId) {
        return Optional.ofNullable(hosts.get(sessionId));
    }

    public Optional<SocketAddress> getPeerAddress(int sessionId, byte clientId) {
        List<PeerInfo> peers = sessions.get(sessionId);
        if (peers == null) return Optional.empty();

        return peers.stream()
            .filter(p -> p.clientId() == clientId)
            .map(PeerInfo::addr)
            .findFirst();
    }

    public List<PeerInfo> getPeers(int sessionId) {
        return sessions.getOrDefault(sessionId, List.of());
    }

    public int getClientCount(int sessionId) {
        List<PeerInfo> peers = sessions.get(sessionId);
        return peers != null ? peers.size() : 0;
    }

    public Optional<Integer> getSessionForPeer(SocketAddress addr) {
        PeerInfo peer = peerLookup.get(addr);
        return peer != null ? Optional.of(peer.sessionId()) : Optional.empty();
    }

    public void updateLastSeen(SocketAddress addr) {
        PeerInfo peer = peerLookup.get(addr);
        if (peer != null) {
            PeerInfo updated = new PeerInfo(
                peer.addr(), peer.clientId(), peer.sessionId(), Instant.now(), peer.isHost()
            );
            peerLookup.put(addr, updated);

            List<PeerInfo> peers = sessions.get(peer.sessionId());
            if (peers != null) {
                peers.removeIf(p -> p.addr().equals(addr));
                peers.add(updated);
            }
        }
    }

    public void cleanupStale(long timeoutMs) {
        Instant cutoff = Instant.now().minusMillis(timeoutMs);
        List<SocketAddress> toRemove = new ArrayList<>();

        for (PeerInfo peer : peerLookup.values()) {
            if (peer.isHost()) continue;

            if (peer.lastSeen().isBefore(cutoff)) {
                toRemove.add(peer.addr());
            }
        }

        for (SocketAddress addr : toRemove) {
            PeerInfo peer = peerLookup.remove(addr);
            if (peer != null) {
                List<PeerInfo> peers = sessions.get(peer.sessionId());
                if (peers != null) {
                    peers.removeIf(p -> p.addr().equals(addr));
                    if (peers.isEmpty()) {
                        sessions.remove(peer.sessionId());
                        hosts.remove(peer.sessionId());
                    }
                }
                System.out.println("Cleaned up stale peer: " + addr);
            }
        }
    }
}

/**
 * Information about a peer in a session.
 */
record PeerInfo(
    SocketAddress addr,
    byte clientId,
    int sessionId,
    Instant lastSeen,
    boolean isHost
) {}

/**
 * Token bucket rate limiter for DoS protection.
 */
class RateLimiter {
    private final int maxPacketsPerSecond;
    private int tokens;
    private long lastRefillTime;

    public RateLimiter(int maxPacketsPerSecond) {
        this.maxPacketsPerSecond = maxPacketsPerSecond;
        this.tokens = maxPacketsPerSecond;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public synchronized boolean allowPacket() {
        refillTokens();

        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }

    private void refillTokens() {
        long now = System.currentTimeMillis();
        long timePassed = now - lastRefillTime;

        if (timePassed >= 1000) {
            int tokensToAdd = (int) (timePassed / 1000) * maxPacketsPerSecond;
            tokens = Math.min(maxPacketsPerSecond, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
}
