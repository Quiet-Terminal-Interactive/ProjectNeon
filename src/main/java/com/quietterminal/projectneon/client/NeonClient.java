package com.quietterminal.projectneon.client;

import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.util.LoggerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Neon protocol client implementation.
 */
public class NeonClient implements AutoCloseable {
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

    /**
     * Runs the client in a blocking loop, processing packets every 10ms.
     */
    public void run() throws IOException, InterruptedException {
        while (true) {
            processPackets();
            Thread.sleep(config.getClientProcessingLoopSleepMs());
        }
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
