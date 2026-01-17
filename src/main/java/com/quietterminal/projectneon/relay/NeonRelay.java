package com.quietterminal.projectneon.relay;

import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.util.LoggerConfig;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Neon protocol relay server.
 * Routes packets between hosts and clients in a completely payload-agnostic manner.
 * Implements Lifecycle for clean start/stop semantics.
 */
public class NeonRelay implements AutoCloseable, Lifecycle {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(NeonRelay.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    private final NeonSocket socket;
    private final SessionManager sessionManager;
    private final Map<SocketAddress, PendingConnection> pendingConnections;
    private final Map<SocketAddress, RateLimiter> rateLimiters;
    private final NeonConfig config;
    private final RelaySemantics relaySemantics;
    private long lastCleanupTime;

    private final AtomicReference<Lifecycle.State> lifecycleState = new AtomicReference<>(Lifecycle.State.CREATED);
    private final List<Lifecycle.StateChangeListener> stateChangeListeners = new CopyOnWriteArrayList<>();
    private volatile Thread runningThread;

    /**
     * Creates a relay with default configuration.
     */
    public NeonRelay(String bindAddress) throws IOException {
        this(bindAddress, new NeonConfig());
    }

    /**
     * Creates a relay with custom configuration.
     */
    public NeonRelay(String bindAddress, NeonConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        config.validate();

        this.config = config;

        String[] parts = bindAddress.split(":");
        int port = parts.length == 2 ? Integer.parseInt(parts[1]) : config.getRelayPort();

        this.socket = new NeonSocket(port, config);
        this.socket.setBlocking(true);
        this.socket.setSoTimeout(config.getRelaySocketTimeoutMs());
        this.sessionManager = new SessionManager();
        this.pendingConnections = new ConcurrentHashMap<>();
        this.rateLimiters = new ConcurrentHashMap<>();
        this.relaySemantics = new RelaySemantics();
        this.lastCleanupTime = System.currentTimeMillis();

        System.out.println("Relay listening on " + socket.getLocalAddress());
    }

    @Override
    public Lifecycle.State getState() {
        return lifecycleState.get();
    }

    @Override
    public void start() {
        Lifecycle.State current = lifecycleState.get();
        if (current != Lifecycle.State.CREATED && current != Lifecycle.State.STOPPED) {
            throw new IllegalStateException("Cannot start from state " + current);
        }

        if (!lifecycleState.compareAndSet(current, Lifecycle.State.STARTING)) {
            throw new IllegalStateException("State changed during start");
        }
        notifyStateChange(current, Lifecycle.State.STARTING, null);

        lifecycleState.set(Lifecycle.State.RUNNING);
        notifyStateChange(Lifecycle.State.STARTING, Lifecycle.State.RUNNING, null);
        logger.log(Level.INFO, "Relay started on {0}", socket.getLocalAddress());
    }

    @Override
    public void stop() {
        Lifecycle.State current = lifecycleState.get();
        if (current != Lifecycle.State.RUNNING && current != Lifecycle.State.STARTING) {
            return;
        }

        if (!lifecycleState.compareAndSet(current, Lifecycle.State.STOPPING)) {
            return;
        }
        notifyStateChange(current, Lifecycle.State.STOPPING, null);

        if (runningThread != null) {
            runningThread.interrupt();
        }

        try {
            close();
            lifecycleState.set(Lifecycle.State.STOPPED);
            notifyStateChange(Lifecycle.State.STOPPING, Lifecycle.State.STOPPED, null);
            logger.log(Level.INFO, "Relay stopped");
        } catch (Exception e) {
            lifecycleState.set(Lifecycle.State.FAILED);
            notifyStateChange(Lifecycle.State.STOPPING, Lifecycle.State.FAILED, e);
        }
    }

    @Override
    public void addStateChangeListener(Lifecycle.StateChangeListener listener) {
        if (listener != null) {
            stateChangeListeners.add(listener);
        }
    }

    @Override
    public void removeStateChangeListener(Lifecycle.StateChangeListener listener) {
        stateChangeListeners.remove(listener);
    }

    private void notifyStateChange(Lifecycle.State oldState, Lifecycle.State newState, Throwable cause) {
        for (Lifecycle.StateChangeListener listener : stateChangeListeners) {
            try {
                listener.onStateChange(oldState, newState, cause);
            } catch (Exception e) {
                logger.log(Level.WARNING, "State change listener threw exception", e);
            }
        }
    }

    /**
     * Runs the relay processing loop.
     * Call start() first to initialize the relay.
     *
     * @throws IOException if a network error occurs
     * @throws InterruptedException if the thread is interrupted
     */
    public void run() throws IOException, InterruptedException {
        if (lifecycleState.get() != Lifecycle.State.RUNNING) {
            throw new IllegalStateException("Relay must be started before running");
        }
        runningThread = Thread.currentThread();
        try {
            while (lifecycleState.get() == Lifecycle.State.RUNNING) {
                processPackets();
                performCleanup();
                Thread.sleep(config.getRelayMainLoopSleepMs());
            }
        } finally {
            runningThread = null;
        }
    }

    /**
     * Starts the relay and runs the processing loop.
     * Combines start() and run() for convenience.
     *
     * @throws IOException if a network error occurs
     * @throws InterruptedException if the thread is interrupted
     */
    public void startAndRun() throws IOException, InterruptedException {
        start();
        run();
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
        if (!rateLimiters.containsKey(source) && rateLimiters.size() >= config.getMaxRateLimiters()) {
            logger.log(Level.WARNING, "Rate limiter capacity exceeded for {0} - dropping packet", source);
            return;
        }

        RateLimiter limiter = rateLimiters.computeIfAbsent(source,
            k -> new RateLimiter(config.getMaxPacketsPerSecond(), config));

        if (!limiter.allowPacket()) {
            if (limiter.isThrottled()) {
                logger.log(Level.WARNING, "Rate limit exceeded for {0} (THROTTLED after {1} violations)",
                    new Object[]{source, limiter.getViolationCount()});
            } else {
                logger.log(Level.WARNING, "Rate limit exceeded for {0}", source);
            }
            return;
        }

        PacketHeader header = packet.header();

        if (header.magic() != PacketHeader.MAGIC) {
            logger.log(Level.WARNING, "Invalid magic number from {0}", source);
            return;
        }

        switch (packet.payload()) {
            case PacketPayload.ConnectRequest request -> handleConnectRequest(request, source);
            case PacketPayload.ConnectAccept accept -> handleConnectAccept(accept, source, header);
            case PacketPayload.ReconnectRequest request -> handleReconnectRequest(request, source);
            case PacketPayload.DisconnectNotice ignored -> handleDisconnectNotice(source, header);
            default -> {
                routePacket(packet, source);
            }
        }
    }

    private void handleConnectRequest(PacketPayload.ConnectRequest request, SocketAddress source) throws IOException {
        int sessionId = request.targetSessionId();

        int totalConnections = sessionManager.getTotalConnections();
        if (totalConnections >= config.getMaxTotalConnections()) {
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny("Relay is full");
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_DENY.getValue(), (short) 0, (byte) 0, (byte) 0
            );
            NeonPacket denyPacket = new NeonPacket(header, deny);
            socket.sendPacket(denyPacket, source);
            logger.log(Level.WARNING, "Connection denied for {0}: relay is full ({1}/{2}) [SessionID={3}]",
                new Object[]{source, totalConnections, config.getMaxTotalConnections(), sessionId});
            return;
        }

        int currentClients = sessionManager.getClientCount(sessionId);
        if (currentClients >= config.getMaxClientsPerSession()) {
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny("Session is full");
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_DENY.getValue(), (short) 0, (byte) 0, (byte) 0
            );
            NeonPacket denyPacket = new NeonPacket(header, deny);
            socket.sendPacket(denyPacket, source);
            logger.log(Level.WARNING, "Connection denied for {0}: session {1} is full ({2}/{3})",
                new Object[]{source, sessionId, currentClients, config.getMaxClientsPerSession()});
            return;
        }

        if (pendingConnections.size() >= config.getMaxPendingConnections()) {
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny("Too many pending connections");
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_DENY.getValue(), (short) 0, (byte) 0, (byte) 0
            );
            NeonPacket denyPacket = new NeonPacket(header, deny);
            socket.sendPacket(denyPacket, source);
            logger.log(Level.WARNING, "Connection denied for {0}: pending connections queue full ({1}/{2}) [SessionID={3}]",
                new Object[]{source, pendingConnections.size(), config.getMaxPendingConnections(), sessionId});
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
            SocketAddress clientAddr = findPendingClientAddress(sessionId);
            if (clientAddr != null) {
                sessionManager.registerPeer(sessionId, clientId, clientAddr, false);
                pendingConnections.remove(clientAddr);
                System.out.println("Client " + clientId + " joined session " + sessionId);
            }

            routeToClient(sessionId, clientId, accept, header);
        }
    }

    private void handleReconnectRequest(PacketPayload.ReconnectRequest request, SocketAddress source) throws IOException {
        int sessionId = request.targetSessionId();

        Optional<SocketAddress> hostAddr = sessionManager.getHost(sessionId);
        if (hostAddr.isPresent()) {
            PacketHeader header = PacketHeader.create(
                PacketType.RECONNECT_REQUEST.getValue(), (short) 0, request.previousClientId(), (byte) 1
            );
            NeonPacket forwardPacket = new NeonPacket(header, request);
            socket.sendPacket(forwardPacket, hostAddr.get());

            sessionManager.updatePeerAddress(sessionId, request.previousClientId(), source);
            logger.log(Level.INFO, "Reconnect request forwarded for client {0} [SessionID={1}]",
                new Object[]{request.previousClientId(), sessionId});
        } else {
            PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny("Session not found");
            PacketHeader header = PacketHeader.create(
                PacketType.CONNECT_DENY.getValue(), (short) 0, (byte) 0, (byte) 0
            );
            NeonPacket denyPacket = new NeonPacket(header, deny);
            socket.sendPacket(denyPacket, source);
            logger.log(Level.WARNING, "Reconnect request for non-existent session {0}", sessionId);
        }
    }

    private void handleDisconnectNotice(SocketAddress source, PacketHeader header) throws IOException {
        Optional<Integer> sessionId = sessionManager.getSessionForPeer(source);
        if (sessionId.isEmpty()) {
            logger.log(Level.WARNING, "Disconnect notice from unknown peer {0}", source);
            return;
        }

        byte clientId = header.clientId();
        int session = sessionId.get();

        PacketPayload.DisconnectNotice notice = new PacketPayload.DisconnectNotice();
        NeonPacket noticePacket = NeonPacket.create(
            PacketType.DISCONNECT_NOTICE, header.sequence(), clientId, (byte) 0, notice
        );

        List<PeerInfo> peers = sessionManager.getPeers(session);
        for (PeerInfo peer : peers) {
            if (!peer.addr().equals(source)) {
                socket.sendPacket(noticePacket, peer.addr());
            }
        }

        sessionManager.removePeer(source);
        pendingConnections.remove(source);
        rateLimiters.remove(source);

        logger.log(Level.INFO, "Client {0} disconnected from session {1}",
            new Object[]{clientId, session});
    }

    private void routePacket(NeonPacket packet, SocketAddress source) throws IOException {
        sessionManager.updateLastSeen(source);

        Optional<String> validationError = relaySemantics.validateForForwarding(packet);
        if (validationError.isPresent()) {
            logger.log(Level.WARNING, "Packet validation failed from {0}: {1}",
                new Object[]{source, validationError.get()});
            return;
        }

        RelaySemantics.RoutingDecision decision = relaySemantics.determineRouting(
            packet, source, sessionManager
        );

        switch (decision) {
            case RelaySemantics.RoutingDecision.Unicast unicast -> {
                socket.sendPacket(unicast.packet(), unicast.destination());
            }
            case RelaySemantics.RoutingDecision.Broadcast broadcast -> {
                for (SocketAddress dest : broadcast.destinations()) {
                    socket.sendPacket(broadcast.packet(), dest);
                }
            }
            case RelaySemantics.RoutingDecision.Unroutable unroutable -> {
                logger.log(Level.WARNING, "Unroutable packet from {0}: destination={1}, reason={2}",
                    new Object[]{source, unroutable.destinationId(), unroutable.reason()});
            }
            case RelaySemantics.RoutingDecision.RelayHandled handled -> {
                logger.log(Level.FINE, "Relay handled packet from {0}: {1}",
                    new Object[]{source, handled.action()});
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

    private SocketAddress findPendingClientAddress(int sessionId) {
        for (Map.Entry<SocketAddress, PendingConnection> entry : pendingConnections.entrySet()) {
            if (entry.getValue().sessionId() == sessionId) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void performCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < config.getRelayCleanupIntervalMs()) {
            return;
        }

        sessionManager.cleanupStale(config.getRelayClientTimeoutMs());

        Instant pendingCutoff = Instant.now().minusMillis(config.getRelayPendingConnectionTimeoutMs());
        pendingConnections.entrySet().removeIf(entry -> {
            if (entry.getValue().requestTime().isBefore(pendingCutoff)) {
                System.out.println("Cleaned up stale pending connection: " + entry.getKey());
                return true;
            }
            return false;
        });

        Set<SocketAddress> activeAddresses = sessionManager.getActiveAddresses();
        rateLimiters.keySet().removeIf(addr -> {
            if (!activeAddresses.contains(addr) && !pendingConnections.containsKey(addr)) {
                return true;
            }
            return false;
        });

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
class SessionManager implements RelaySemantics.PeerLookup {
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

    public void updatePeerAddress(int sessionId, byte clientId, SocketAddress newAddr) {
        List<PeerInfo> peers = sessions.get(sessionId);
        if (peers != null) {
            PeerInfo oldPeer = peers.stream()
                .filter(p -> p.clientId() == clientId)
                .findFirst()
                .orElse(null);

            if (oldPeer != null) {
                peers.remove(oldPeer);
                peerLookup.remove(oldPeer.addr());
            }

            PeerInfo newPeer = new PeerInfo(newAddr, clientId, sessionId, Instant.now(), oldPeer != null && oldPeer.isHost());
            peers.add(newPeer);
            peerLookup.put(newAddr, newPeer);
        }
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

    @Override
    public List<SocketAddress> getAllPeersExcept(int sessionId, SocketAddress exclude) {
        List<PeerInfo> peers = sessions.get(sessionId);
        if (peers == null) return List.of();

        List<SocketAddress> result = new ArrayList<>();
        for (PeerInfo peer : peers) {
            if (!peer.addr().equals(exclude)) {
                result.add(peer.addr());
            }
        }
        return result;
    }

    public int getClientCount(int sessionId) {
        List<PeerInfo> peers = sessions.get(sessionId);
        return peers != null ? peers.size() : 0;
    }

    public int getTotalConnections() {
        return peerLookup.size();
    }

    public Set<SocketAddress> getActiveAddresses() {
        return new HashSet<>(peerLookup.keySet());
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

    public void removePeer(SocketAddress addr) {
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
 * Token bucket rate limiter with flood detection for DoS protection.
 * Tracks violations and implements progressive throttling for repeat offenders.
 */
class RateLimiter {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(RateLimiter.class.getName());
        com.quietterminal.projectneon.util.LoggerConfig.configureLogger(logger);
    }

    private final int maxPacketsPerSecond;
    private final NeonConfig config;
    private int tokens;
    private long lastRefillTime;

    private int violationCount;
    private long firstViolationTime;
    private boolean isThrottled;

    public RateLimiter(int maxPacketsPerSecond, NeonConfig config) {
        this.maxPacketsPerSecond = maxPacketsPerSecond;
        this.config = config;
        this.tokens = maxPacketsPerSecond;
        this.lastRefillTime = System.currentTimeMillis();
        this.violationCount = 0;
        this.firstViolationTime = 0;
        this.isThrottled = false;
    }

    public synchronized boolean allowPacket() {
        refillTokens();
        checkFloodStatus();

        int effectiveLimit = isThrottled ? maxPacketsPerSecond / config.getThrottlePenaltyDivisor() : maxPacketsPerSecond;

        if (tokens > 0 && tokens <= effectiveLimit) {
            tokens--;
            return true;
        }

        recordViolation();
        return false;
    }

    private void refillTokens() {
        long now = System.currentTimeMillis();
        long timePassed = now - lastRefillTime;

        if (timePassed >= config.getTokenRefillIntervalMs()) {
            int tokensToAdd = (int) (timePassed / 1000) * maxPacketsPerSecond;
            tokens = Math.min(maxPacketsPerSecond, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }

    private void recordViolation() {
        long now = System.currentTimeMillis();

        if (firstViolationTime == 0 || now - firstViolationTime > config.getFloodWindowMs()) {
            firstViolationTime = now;
            violationCount = 1;
        } else {
            violationCount++;

            if (violationCount >= config.getFloodThreshold() && !isThrottled) {
                isThrottled = true;
                logger.log(Level.WARNING,
                    "Flood detected: throttling activated after {0} violations", violationCount);
            }
        }
    }

    private void checkFloodStatus() {
        long now = System.currentTimeMillis();

        if (isThrottled && now - firstViolationTime > config.getFloodWindowMs()) {
            isThrottled = false;
            violationCount = 0;
            firstViolationTime = 0;
        }
    }

    public boolean isThrottled() {
        return isThrottled;
    }

    public int getViolationCount() {
        return violationCount;
    }
}
