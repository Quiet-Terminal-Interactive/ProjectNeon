package com.quietterminal.projectneon.host;

import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.util.LoggerConfig;
import com.quietterminal.projectneon.util.VirtualThreads;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Neon protocol host implementation.
 * The host manages game sessions and coordinates clients through a relay.
 * Implements Lifecycle for clean start/stop semantics.
 */
public class NeonHost implements AutoCloseable, Lifecycle {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(NeonHost.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    private static final byte HOST_CLIENT_ID = 1;
    private static final byte FIRST_CLIENT_ID = 2;

    private final NeonSocket socket;
    private final NeonConfig config;
    private final int sessionId;
    private SocketAddress relayAddr;
    private byte nextClientId = FIRST_CLIENT_ID;
    private short nextSequence = 0;

    private final Map<Byte, String> connectedClients = new ConcurrentHashMap<>();
    private final AckStateMachine ackStateMachine;
    private final Map<Short, Byte> sequenceToClient = new ConcurrentHashMap<>();
    private final Map<Byte, Long> clientTokens = new ConcurrentHashMap<>();
    private final Map<Byte, DisconnectedClient> disconnectedClients = new ConcurrentHashMap<>();
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    private TriConsumer<Byte, String, Integer> clientConnectCallback;
    private BiConsumer<String, String> clientDenyCallback;
    private Consumer<Byte> pingReceivedCallback;
    private BiConsumer<Byte, Byte> unhandledPacketCallback;
    private Consumer<Byte> clientDisconnectCallback;

    private final AtomicReference<Lifecycle.State> lifecycleState = new AtomicReference<>(Lifecycle.State.CREATED);
    private final List<Lifecycle.StateChangeListener> stateChangeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Thread runningThread;

    /**
     * Creates a host with default configuration.
     */
    public NeonHost(int sessionId, String relayAddress) throws IOException {
        this(sessionId, relayAddress, new NeonConfig());
    }

    /**
     * Creates a host with custom configuration.
     */
    public NeonHost(int sessionId, String relayAddress, NeonConfig config) throws IOException {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("Session ID must be a positive integer, got: " + sessionId);
        }
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        config.validate();

        this.sessionId = sessionId;
        this.config = config;
        this.socket = new NeonSocket(config);
        this.socket.setBlocking(true);
        this.socket.setSoTimeout(config.getHostSocketTimeoutMs());
        this.ackStateMachine = AckStateMachine.fromConfig(config, true);

        String[] parts = relayAddress.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid relay address format. Expected host:port");
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        this.relayAddr = new InetSocketAddress(host, port);
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

        try {
            doStart();
            lifecycleState.set(Lifecycle.State.RUNNING);
            notifyStateChange(Lifecycle.State.STARTING, Lifecycle.State.RUNNING, null);
            logger.log(Level.INFO, "Host started [SessionID={0}]", sessionId);
        } catch (Exception e) {
            lifecycleState.set(Lifecycle.State.FAILED);
            notifyStateChange(Lifecycle.State.STARTING, Lifecycle.State.FAILED, e);
            throw new RuntimeException("Host failed to start", e);
        }
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
            logger.log(Level.INFO, "Host stopped [SessionID={0}]", sessionId);
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

    private void doStart() throws IOException {
        long hostToken = secureRandom.nextLong();
        clientTokens.put(HOST_CLIENT_ID, hostToken);

        PacketPayload.ConnectAccept registration = new PacketPayload.ConnectAccept(
            HOST_CLIENT_ID, sessionId, hostToken
        );
        NeonPacket packet = NeonPacket.create(
            PacketType.CONNECT_ACCEPT, nextSequence++, HOST_CLIENT_ID, (byte) 0, registration
        );
        socket.sendPacket(packet, relayAddr);

        System.out.println("Host registered with session ID: " + sessionId);
    }

    /**
     * Runs the host processing loop.
     * Call start() first to register with the relay.
     *
     * @throws IOException if a network error occurs
     * @throws InterruptedException if the thread is interrupted
     */
    public void run() throws IOException, InterruptedException {
        if (lifecycleState.get() != Lifecycle.State.RUNNING) {
            throw new IllegalStateException("Host must be started before running");
        }
        runningThread = Thread.currentThread();
        try {
            while (lifecycleState.get() == Lifecycle.State.RUNNING) {
                processPackets();
                checkPendingAcks();
                Thread.sleep(config.getHostProcessingLoopSleepMs());
            }
        } finally {
            runningThread = null;
        }
    }

    /**
     * Starts the host, registers with relay, and runs the processing loop.
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
     * Starts the host in a background thread using virtual threads if available (Java 21+).
     * Falls back to platform threads on older JVMs.
     *
     * @return the started thread
     */
    public Thread startAsync() {
        return VirtualThreads.startVirtualThread(() -> {
            try {
                startAndRun();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Host thread IO error", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Level.INFO, "Host thread interrupted");
            }
        });
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

                handlePacket(received.packet());
                count++;
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
        }
        return count;
    }

    private void handlePacket(NeonPacket packet) throws IOException {
        PacketHeader header = packet.header();

        switch (packet.payload()) {
            case PacketPayload.ConnectRequest request -> handleConnectRequest(request, header);
            case PacketPayload.ReconnectRequest request -> handleReconnectRequest(request, header);
            case PacketPayload.Ping ping -> {
                if (pingReceivedCallback != null) {
                    pingReceivedCallback.accept(header.clientId());
                }
                sendPong(ping.timestamp(), header.clientId());
            }
            case PacketPayload.Ack ack -> {
                for (Short seq : ack.acknowledgedSequences()) {
                    if (ackStateMachine.acknowledge(seq)) {
                        sequenceToClient.remove(seq);
                    }
                }
            }
            case PacketPayload.DisconnectNotice ignored -> {
                byte disconnectedClientId = header.clientId();
                String clientName = connectedClients.remove(disconnectedClientId);
                Long token = clientTokens.get(disconnectedClientId);

                if (token != null) {
                    disconnectedClients.put(disconnectedClientId,
                        new DisconnectedClient(clientName != null ? clientName : "", token, System.currentTimeMillis()));
                    logger.log(Level.INFO, "Client {0} ({1}) added to disconnected clients for reconnection [SessionID={2}]",
                        new Object[]{disconnectedClientId, clientName, sessionId});
                } else {
                    logger.log(Level.WARNING, "Client {0} disconnected but has no token, reconnection not possible [SessionID={1}]",
                        new Object[]{disconnectedClientId, sessionId});
                }

                sequenceToClient.entrySet().removeIf(e -> e.getValue() == disconnectedClientId);
                if (clientDisconnectCallback != null) {
                    clientDisconnectCallback.accept(disconnectedClientId);
                }
                logger.log(Level.INFO, "Client {0} disconnected [SessionID={1}]",
                    new Object[]{disconnectedClientId, sessionId});
            }
            default -> {
                if (unhandledPacketCallback != null) {
                    unhandledPacketCallback.accept(header.packetType(), header.clientId());
                }
            }
        }
    }

    private void handleConnectRequest(PacketPayload.ConnectRequest request, PacketHeader header) throws IOException {
        String clientName = request.desiredName();

        if (connectedClients.containsValue(clientName)) {
            sendConnectDeny(clientName, "Name already in use");
            if (clientDenyCallback != null) {
                clientDenyCallback.accept(clientName, "Name already in use");
            }
            return;
        }

        byte assignedId = nextClientId++;
        connectedClients.put(assignedId, clientName);

        long clientToken = secureRandom.nextLong();
        clientTokens.put(assignedId, clientToken);

        PacketPayload.ConnectAccept accept = new PacketPayload.ConnectAccept(assignedId, sessionId, clientToken);
        NeonPacket acceptPacket = NeonPacket.create(
            PacketType.CONNECT_ACCEPT, nextSequence++, HOST_CLIENT_ID, (byte) 0, accept
        );
        socket.sendPacket(acceptPacket, relayAddr);

        if (clientConnectCallback != null) {
            clientConnectCallback.accept(assignedId, clientName, sessionId);
        }

        try {
            Thread.sleep(config.getHostReliabilityDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        short seq = nextSequence++;
        PacketPayload.SessionConfig sessionConfig = new PacketPayload.SessionConfig(
            PacketHeader.VERSION, (short) 60, (short) 1024
        );
        NeonPacket configPacket = NeonPacket.create(
            PacketType.SESSION_CONFIG, seq, HOST_CLIENT_ID, assignedId, sessionConfig
        );
        socket.sendPacket(configPacket, relayAddr);

        ackStateMachine.track(seq, configPacket);
        sequenceToClient.put(seq, assignedId);

        PacketPayload.PacketTypeRegistry registry = new PacketPayload.PacketTypeRegistry(List.of());
        NeonPacket registryPacket = NeonPacket.create(
            PacketType.PACKET_TYPE_REGISTRY, nextSequence++, HOST_CLIENT_ID, assignedId, registry
        );
        socket.sendPacket(registryPacket, relayAddr);
    }

    private void handleReconnectRequest(PacketPayload.ReconnectRequest request, PacketHeader header) throws IOException {
        byte clientId = request.previousClientId();
        long providedToken = request.sessionToken();

        DisconnectedClient disconnected = disconnectedClients.get(clientId);
        if (disconnected == null) {
            sendConnectDeny("", "Session expired or not found");
            logger.log(Level.WARNING, "Reconnect attempt for unknown client {0} [SessionID={1}]",
                new Object[]{clientId, sessionId});
            return;
        }

        if (disconnected.token() != providedToken) {
            sendConnectDeny("", "Invalid session token");
            logger.log(Level.WARNING, "Reconnect attempt with invalid token for client {0} [SessionID={1}]",
                new Object[]{clientId, sessionId});
            return;
        }

        long now = System.currentTimeMillis();
        if (now - disconnected.disconnectTime() > config.getHostSessionTokenTimeoutMs()) {
            disconnectedClients.remove(clientId);
            sendConnectDeny("", "Session timeout exceeded");
            logger.log(Level.WARNING, "Reconnect attempt after timeout for client {0} [SessionID={1}]",
                new Object[]{clientId, sessionId});
            return;
        }

        connectedClients.put(clientId, disconnected.name());
        clientTokens.put(clientId, providedToken);
        disconnectedClients.remove(clientId);

        long newToken = secureRandom.nextLong();
        clientTokens.put(clientId, newToken);

        PacketPayload.ConnectAccept accept = new PacketPayload.ConnectAccept(clientId, sessionId, newToken);
        NeonPacket acceptPacket = NeonPacket.create(
            PacketType.CONNECT_ACCEPT, nextSequence++, HOST_CLIENT_ID, clientId, accept
        );
        socket.sendPacket(acceptPacket, relayAddr);

        if (clientConnectCallback != null) {
            clientConnectCallback.accept(clientId, disconnected.name(), sessionId);
        }

        logger.log(Level.INFO, "Client {0} reconnected [SessionID={1}]",
            new Object[]{clientId, sessionId});
    }

    private void sendConnectDeny(String clientName, String reason) throws IOException {
        PacketPayload.ConnectDeny deny = new PacketPayload.ConnectDeny(reason);
        NeonPacket packet = NeonPacket.create(
            PacketType.CONNECT_DENY, nextSequence++, HOST_CLIENT_ID, (byte) 0, deny
        );
        socket.sendPacket(packet, relayAddr);
    }

    private void sendPong(long originalTimestamp, byte destinationId) throws IOException {
        PacketPayload.Pong pong = new PacketPayload.Pong(originalTimestamp);
        NeonPacket packet = NeonPacket.create(
            PacketType.PONG, nextSequence++, HOST_CLIENT_ID, destinationId, pong
        );
        socket.sendPacket(packet, relayAddr);
    }

    private void checkPendingAcks() throws IOException {
        AckStateMachine.ProcessResult result = ackStateMachine.process();

        for (AckStateMachine.PendingPacket pending : result.needsRetry()) {
            socket.sendPacket(pending.packet(), relayAddr);
            ackStateMachine.markResent(pending.sequence());
        }

        for (AckStateMachine.PendingPacket failed : result.failed()) {
            Byte clientId = sequenceToClient.remove(failed.sequence());
            if (clientId != null) {
                logger.log(Level.WARNING, "Client {0} failed to ACK after {1} retries [SessionID={2}, Sequence={3}]",
                    new Object[]{clientId, config.getHostMaxAckRetries(), sessionId, failed.sequence()});
            }
        }
    }

    public int getSessionId() {
        return sessionId;
    }

    public int getClientCount() {
        return connectedClients.size();
    }

    public Map<Byte, String> getConnectedClients() {
        return new HashMap<>(connectedClients);
    }

    public void setClientConnectCallback(TriConsumer<Byte, String, Integer> callback) {
        this.clientConnectCallback = callback;
    }

    public void setClientDenyCallback(BiConsumer<String, String> callback) {
        this.clientDenyCallback = callback;
    }

    public void setPingReceivedCallback(Consumer<Byte> callback) {
        this.pingReceivedCallback = callback;
    }

    public void setUnhandledPacketCallback(BiConsumer<Byte, Byte> callback) {
        this.unhandledPacketCallback = callback;
    }

    public void setClientDisconnectCallback(Consumer<Byte> callback) {
        this.clientDisconnectCallback = callback;
    }

    @Override
    public void close() throws IOException {
        if (relayAddr != null) {
            long shutdownStart = System.currentTimeMillis();

            try {
                PacketPayload.DisconnectNotice notice = new PacketPayload.DisconnectNotice();
                NeonPacket packet = NeonPacket.create(
                    PacketType.DISCONNECT_NOTICE, nextSequence++, HOST_CLIENT_ID, (byte) 0, notice
                );
                socket.sendPacket(packet, relayAddr);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to send disconnect notice [SessionID={0}]",
                    new Object[]{sessionId});
            }

            while (ackStateMachine.hasPending() &&
                   System.currentTimeMillis() - shutdownStart < config.getHostGracefulShutdownTimeoutMs()) {
                try {
                    processPackets();
                    Thread.sleep(config.getHostProcessingLoopSleepMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Error during graceful shutdown [SessionID={0}]", sessionId);
                    break;
                }
            }

            if (ackStateMachine.hasPending()) {
                logger.log(Level.WARNING, "Graceful shutdown timeout: {0} pending ACKs remaining [SessionID={1}]",
                    new Object[]{ackStateMachine.pendingCount(), sessionId});
            }
        }
        socket.close();
    }

    /**
     * Tracks disconnected clients for reconnection support.
     */
    private record DisconnectedClient(
        String name,
        long token,
        long disconnectTime
    ) {}

    /**
     * Functional interface for callbacks with three parameters.
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
