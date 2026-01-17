package com.quietterminal.projectneon.client;

import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.util.LoggerConfig;
import com.quietterminal.projectneon.util.VirtualThreads;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Neon protocol client implementation.
 * Implements Lifecycle for clean start/stop semantics.
 */
public class NeonClient implements AutoCloseable, Lifecycle {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(NeonClient.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    private NeonSocket socket;
    private final NeonConfig config;
    private final String name;
    private SocketAddress relayAddr;
    private Byte clientId;
    private Integer sessionId;
    private Long sessionToken;
    private short nextSequence = 0;

    private boolean autoPing = true;
    private long pingIntervalMs;
    private long lastPingTime = 0;

    private BiConsumer<Long, Long> pongCallback;
    private TriConsumer<Byte, Short, Short> sessionConfigCallback;
    private Consumer<PacketPayload.PacketTypeRegistry> packetTypeRegistryCallback;
    private BiConsumer<Byte, Byte> unhandledPacketCallback;
    private BiConsumer<Byte, Byte> wrongDestinationCallback;
    private Consumer<Byte> disconnectCallback;

    private final AtomicReference<Lifecycle.State> lifecycleState = new AtomicReference<>(Lifecycle.State.CREATED);
    private final List<Lifecycle.StateChangeListener> stateChangeListeners = new CopyOnWriteArrayList<>();
    private volatile Thread runningThread;
    private volatile int pendingSessionId;
    private volatile String pendingRelayAddress;

    /**
     * Creates a client with default configuration.
     */
    public NeonClient(String name) throws IOException {
        this(name, new NeonConfig());
    }

    /**
     * Creates a client with custom configuration.
     */
    public NeonClient(String name, NeonConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        config.validate();

        this.name = name;
        this.config = config;
        this.socket = new NeonSocket(config);
        this.socket.setBlocking(true);
        this.socket.setSoTimeout(config.getClientSocketTimeoutMs());
        this.pingIntervalMs = config.getClientPingIntervalMs();
    }

    /**
     * Connects to a relay server and joins a session.
     */
    public boolean connect(int sessionId, String relayAddress) throws IOException {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("Session ID must be a positive integer, got: " + sessionId);
        }
        String[] parts = relayAddress.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid relay address format. Expected host:port");
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        this.relayAddr = new InetSocketAddress(host, port);
        this.sessionId = sessionId;

        socket.setSoTimeout(config.getClientConnectionTimeoutMs());

        PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
            PacketHeader.VERSION, name, sessionId, 0
        );
        NeonPacket packet = NeonPacket.create(
            PacketType.CONNECT_REQUEST, nextSequence++, (byte) 0, (byte) 1, request
        );
        socket.sendPacket(packet, relayAddr);

        try {
            while (true) {
                NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
                if (received == null) continue;

                if (received.packet().payload() instanceof PacketPayload.ConnectAccept accept) {
                    this.clientId = accept.assignedClientId();
                    this.sessionId = accept.sessionId();
                    this.sessionToken = accept.sessionToken();

                    NeonPacket confirmation = NeonPacket.create(
                        PacketType.CONNECT_ACCEPT, nextSequence++, clientId, (byte) 0, accept
                    );
                    socket.sendPacket(confirmation, relayAddr);

                    socket.setSoTimeout(config.getClientSocketTimeoutMs());
                    logger.log(Level.INFO, "Connected to session {0} as client {1} [Token={2}]",
                        new Object[]{sessionId, clientId, sessionToken});
                    return true;
                }

                if (received.packet().payload() instanceof PacketPayload.ConnectDeny deny) {
                    logger.log(Level.WARNING, "Connection denied: {0} [SessionID={1}, ClientName={2}]",
                        new Object[]{deny.reason(), sessionId, name});
                    return false;
                }
            }
        } catch (IOException e) {
            socket.setSoTimeout(config.getClientSocketTimeoutMs());
            throw e;
        }
    }

    /**
     * Processes incoming packets. Should be called regularly in the game loop.
     * Returns the number of packets processed.
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

        if (autoPing && clientId != null) {
            long now = System.currentTimeMillis();
            if (now - lastPingTime >= pingIntervalMs) {
                sendPing();
                lastPingTime = now;
            }
        }

        return count;
    }

    private void handlePacket(NeonPacket packet) throws IOException {
        PacketHeader header = packet.header();

        if (clientId != null && header.destinationId() != clientId && header.destinationId() != 0) {
            if (wrongDestinationCallback != null) {
                wrongDestinationCallback.accept(clientId, header.destinationId());
            }
            return;
        }

        switch (packet.payload()) {
            case PacketPayload.Pong pong -> {
                long responseTime = System.currentTimeMillis() - pong.originalTimestamp();
                if (pongCallback != null) {
                    pongCallback.accept(responseTime, pong.originalTimestamp());
                }
            }
            case PacketPayload.SessionConfig config -> {
                if (sessionConfigCallback != null) {
                    sessionConfigCallback.accept(config.version(), config.tickRate(), config.maxPacketSize());
                }
                sendAck(header.sequence());
            }
            case PacketPayload.PacketTypeRegistry registry -> {
                if (packetTypeRegistryCallback != null) {
                    packetTypeRegistryCallback.accept(registry);
                }
            }
            case PacketPayload.Ping ping -> {
                sendPong(ping.timestamp());
            }
            case PacketPayload.DisconnectNotice ignored -> {
                if (disconnectCallback != null) {
                    disconnectCallback.accept(header.clientId());
                }
            }
            default -> {
                if (unhandledPacketCallback != null) {
                    unhandledPacketCallback.accept(header.packetType(), header.clientId());
                }
            }
        }
    }

    /**
     * Sends a ping to the host.
     */
    public void sendPing() throws IOException {
        if (clientId == null) {
            throw new IllegalStateException("Not connected");
        }
        long timestamp = System.currentTimeMillis();
        PacketPayload.Ping ping = new PacketPayload.Ping(timestamp);
        NeonPacket packet = NeonPacket.create(
            PacketType.PING, nextSequence++, clientId, (byte) 1, ping
        );
        socket.sendPacket(packet, relayAddr);
    }

    private void sendPong(long originalTimestamp) throws IOException {
        if (clientId == null) return;
        PacketPayload.Pong pong = new PacketPayload.Pong(originalTimestamp);
        NeonPacket packet = NeonPacket.create(
            PacketType.PONG, nextSequence++, clientId, (byte) 1, pong
        );
        socket.sendPacket(packet, relayAddr);
    }

    private void sendAck(short sequence) throws IOException {
        if (clientId == null) return;
        PacketPayload.Ack ack = new PacketPayload.Ack(java.util.List.of(sequence));
        NeonPacket packet = NeonPacket.create(
            PacketType.ACK, nextSequence++, clientId, (byte) 1, ack
        );
        socket.sendPacket(packet, relayAddr);
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

        if (pendingSessionId <= 0 || pendingRelayAddress == null) {
            throw new IllegalStateException("Must call setTarget() before start()");
        }

        if (!lifecycleState.compareAndSet(current, Lifecycle.State.STARTING)) {
            throw new IllegalStateException("State changed during start");
        }
        notifyStateChange(current, Lifecycle.State.STARTING, null);

        try {
            boolean connected = connect(pendingSessionId, pendingRelayAddress);
            if (connected) {
                lifecycleState.set(Lifecycle.State.RUNNING);
                notifyStateChange(Lifecycle.State.STARTING, Lifecycle.State.RUNNING, null);
                logger.log(Level.INFO, "Client started [SessionID={0}, ClientID={1}]",
                    new Object[]{sessionId, clientId});
            } else {
                lifecycleState.set(Lifecycle.State.FAILED);
                notifyStateChange(Lifecycle.State.STARTING, Lifecycle.State.FAILED, null);
            }
        } catch (Exception e) {
            lifecycleState.set(Lifecycle.State.FAILED);
            notifyStateChange(Lifecycle.State.STARTING, Lifecycle.State.FAILED, e);
            throw new RuntimeException("Client failed to start", e);
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
            logger.log(Level.INFO, "Client stopped [SessionID={0}, ClientID={1}]",
                new Object[]{sessionId, clientId});
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
     * Sets the target session and relay for connection.
     * Must be called before start() when using the Lifecycle interface.
     *
     * @param sessionId the session ID to connect to
     * @param relayAddress the relay address (host:port)
     * @return this client for chaining
     */
    public NeonClient setTarget(int sessionId, String relayAddress) {
        this.pendingSessionId = sessionId;
        this.pendingRelayAddress = relayAddress;
        return this;
    }

    /**
     * Runs the client in a blocking loop, processing packets.
     *
     * @throws IOException if a network error occurs
     * @throws InterruptedException if the thread is interrupted
     */
    public void run() throws IOException, InterruptedException {
        if (lifecycleState.get() != Lifecycle.State.RUNNING) {
            throw new IllegalStateException("Client must be started before running");
        }
        runningThread = Thread.currentThread();
        try {
            while (lifecycleState.get() == Lifecycle.State.RUNNING) {
                processPackets();
                Thread.sleep(config.getClientProcessingLoopSleepMs());
            }
        } finally {
            runningThread = null;
        }
    }

    /**
     * Starts the client, connects to relay, and runs the processing loop.
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
     * Starts the client in a background thread using virtual threads if available (Java 21+).
     * Falls back to platform threads on older JVMs.
     * Requires setTarget() to be called first.
     *
     * @return the started thread
     */
    public Thread startAsync() {
        return VirtualThreads.startVirtualThread(() -> {
            try {
                startAndRun();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Client thread IO error", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Level.INFO, "Client thread interrupted");
            }
        });
    }

    /**
     * Runs the client processing loop in a background thread.
     * Call connect() first to establish the connection.
     *
     * @return the started thread
     * @deprecated Use startAsync() instead
     */
    @Deprecated
    public Thread runAsync() {
        return VirtualThreads.startVirtualThread(() -> {
            try {
                if (lifecycleState.get() == Lifecycle.State.CREATED && clientId != null) {
                    lifecycleState.set(Lifecycle.State.RUNNING);
                }
                runningThread = Thread.currentThread();
                while (lifecycleState.get() == Lifecycle.State.RUNNING) {
                    processPackets();
                    Thread.sleep(config.getClientProcessingLoopSleepMs());
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Client thread IO error", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Level.INFO, "Client thread interrupted");
            } finally {
                runningThread = null;
            }
        });
    }

    public Optional<Byte> getClientId() {
        return Optional.ofNullable(clientId);
    }

    public Optional<Integer> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public Optional<Long> getSessionToken() {
        return Optional.ofNullable(sessionToken);
    }

    public boolean isConnected() {
        return clientId != null;
    }

    /**
     * Attempts to reconnect to the session using the stored session token.
     * Uses exponential backoff with configurable max attempts.
     *
     * @param maxAttempts Maximum number of reconnection attempts
     * @return true if reconnection succeeded, false otherwise
     */
    public boolean reconnect(int maxAttempts) throws IOException, InterruptedException {
        if (sessionToken == null || sessionId == null || clientId == null || relayAddr == null) {
            logger.log(Level.WARNING, "Cannot reconnect: missing session state");
            return false;
        }

        if (!socket.isClosed()) {
            logger.log(Level.WARNING, "Cannot reconnect: socket is still open [SessionID={0}, ClientID={1}]",
                new Object[]{sessionId, clientId});
            return false;
        }

        int attempt = 0;
        int delayMs = config.getClientInitialReconnectDelayMs();

        while (attempt < maxAttempts) {
            attempt++;
            logger.log(Level.INFO, "Reconnection attempt {0}/{1} [SessionID={2}, ClientID={3}]",
                new Object[]{attempt, maxAttempts, sessionId, clientId});

            try {
                if (attemptReconnect()) {
                    logger.log(Level.INFO, "Reconnection successful [SessionID={0}, ClientID={1}]",
                        new Object[]{sessionId, clientId});
                    return true;
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Reconnection attempt {0} failed: {1}",
                    new Object[]{attempt, e.getMessage()});
            }

            if (attempt < maxAttempts) {
                Thread.sleep(delayMs);
                delayMs = Math.min(delayMs * 2, config.getClientMaxReconnectDelayMs());
            }
        }

        logger.log(Level.WARNING, "Reconnection failed after {0} attempts [SessionID={1}]",
            new Object[]{maxAttempts, sessionId});
        return false;
    }

    /**
     * Attempts to reconnect using default max attempts.
     */
    public boolean reconnect() throws IOException, InterruptedException {
        return reconnect(config.getClientMaxReconnectAttempts());
    }

    private boolean attemptReconnect() throws IOException {
        if (socket.isClosed()) {
            socket = new NeonSocket(config);
            socket.setBlocking(true);
        }

        socket.setSoTimeout(config.getClientConnectionTimeoutMs());

        PacketPayload.ReconnectRequest request = new PacketPayload.ReconnectRequest(
            sessionToken, sessionId, clientId
        );
        NeonPacket packet = NeonPacket.create(
            PacketType.RECONNECT_REQUEST, nextSequence++, clientId, (byte) 1, request
        );
        socket.sendPacket(packet, relayAddr);

        try {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < config.getClientConnectionTimeoutMs()) {
                NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
                if (received == null) continue;

                if (received.packet().payload() instanceof PacketPayload.ConnectAccept accept) {
                    this.sessionToken = accept.sessionToken();
                    socket.setSoTimeout(config.getClientSocketTimeoutMs());
                    return true;
                }

                if (received.packet().payload() instanceof PacketPayload.ConnectDeny deny) {
                    logger.log(Level.WARNING, "Reconnection denied: {0} [SessionID={1}, ClientID={2}]",
                        new Object[]{deny.reason(), sessionId, clientId});
                    return false;
                }
            }
            return false;
        } finally {
            socket.setSoTimeout(config.getClientSocketTimeoutMs());
        }
    }

    public void setAutoPing(boolean enabled) {
        this.autoPing = enabled;
    }

    public void setPingInterval(Duration interval) {
        this.pingIntervalMs = interval.toMillis();
    }

    public void setPongCallback(BiConsumer<Long, Long> callback) {
        this.pongCallback = callback;
    }

    public void setSessionConfigCallback(TriConsumer<Byte, Short, Short> callback) {
        this.sessionConfigCallback = callback;
    }

    public void setPacketTypeRegistryCallback(Consumer<PacketPayload.PacketTypeRegistry> callback) {
        this.packetTypeRegistryCallback = callback;
    }

    public void setUnhandledPacketCallback(BiConsumer<Byte, Byte> callback) {
        this.unhandledPacketCallback = callback;
    }

    public void setWrongDestinationCallback(BiConsumer<Byte, Byte> callback) {
        this.wrongDestinationCallback = callback;
    }

    public void setDisconnectCallback(Consumer<Byte> callback) {
        this.disconnectCallback = callback;
    }

    @Override
    public void close() throws IOException {
        if (clientId != null && relayAddr != null) {
            try {
                PacketPayload.DisconnectNotice notice = new PacketPayload.DisconnectNotice();
                NeonPacket packet = NeonPacket.create(
                    PacketType.DISCONNECT_NOTICE, nextSequence++, clientId, (byte) 0, notice
                );
                socket.sendPacket(packet, relayAddr);
                Thread.sleep(config.getClientDisconnectNoticeDelayMs());
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to send disconnect notice [ClientID={0}, SessionID={1}]",
                    new Object[]{clientId, sessionId});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        socket.close();
    }

    /**
     * Functional interface for callbacks with three parameters.
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
