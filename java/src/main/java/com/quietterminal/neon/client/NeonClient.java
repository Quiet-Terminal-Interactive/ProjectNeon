package com.quietterminal.neon.client;

import com.quietterminal.neon.core.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Relay-connected UDP client for the Neon multiplayer protocol.
 *
 * <p>
 * A {@code NeonClient} connects to a session hosted by a
 * {@link com.quietterminal.neon.host.NeonHost}
 * through a {@link com.quietterminal.neon.relay.NeonRelay}. The connection
 * handshake is synchronous;
 * ongoing packet processing is driven either by calling {@link #run()} in a
 * dedicated thread or by
 * periodically calling {@link #processPackets()} from the game loop.
 *
 * <p>
 * Typical usage:
 * 
 * <pre>{@code
 * NeonClient client = new NeonClient("player1", NeonConfig.defaults());
 * client.setSessionConfigCallback(cfg -> System.out.println("tick rate: " + cfg.tickRate()));
 * if (client.connect(sessionId, "relay.example.com:9000")) {
 *     Thread.ofVirtual().start(client::run);
 * }
 * }</pre>
 */
public final class NeonClient extends AbstractLifecycle implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NeonClient.class.getName());

    private NeonSocket socket;
    private final NeonConfig config;
    private final String name;
    private SocketAddress relayAddr;
    private volatile Byte clientId;
    private volatile Integer sessionId;
    private volatile Long sessionToken;
    private final AtomicInteger nextSequence = new AtomicInteger(0);
    private volatile long lastPingTime = System.currentTimeMillis();

    private Consumer<PacketPayload.Pong> pongCallback;
    private Consumer<PacketPayload.SessionConfig> sessionConfigCallback;
    private Consumer<PacketPayload.PacketTypeRegistry> packetTypeRegistryCallback;
    private TriConsumer<Byte, Byte, byte[]> unhandledPacketCallback;
    private Consumer<Byte> disconnectCallback;

    /**
     * Creates a new client with the given display name and configuration.
     * Binds a local UDP socket on an OS-assigned port.
     *
     * @param name   player display name sent to the host during connection
     * @param config protocol configuration
     * @throws IOException if the local socket cannot be bound
     */
    public NeonClient(String name, NeonConfig config) throws IOException {
        this.name = name;
        this.config = config;
        this.socket = new NeonSocket(config);
    }

    /**
     * Connects to a session through a relay. Blocks until the relay accepts or
     * denies the connection,
     * or until the connection timeout elapses.
     *
     * @param sessionId    the session to join
     * @param relayAddress relay address in {@code "host:port"} format
     * @return {@code true} if the connection was accepted; {@code false} if denied
     *         or timed out
     */
    public boolean connect(int sessionId, String relayAddress) {
        this.sessionId = sessionId;
        this.relayAddr = parseAddress(relayAddress);
        try {
            start();
            return true;
        } catch (Exception e) {
            logger.warning("Connection failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    protected void doStart() throws Exception {
        if (config.isDtlsEnabled())
            socket.performClientHandshake(config.getSslContext(), relayAddr, config.getClientConnectionTimeoutMs());

        socket.sendPacket(NeonPacket.create(PacketType.CONNECT_REQUEST, nextSeq(), (byte) 0, (byte) 1,
                new PacketPayload.ConnectRequest(PacketHeader.VERSION, name, sessionId, 0)), relayAddr);

        socket.setBlocking(true);
        socket.setTimeout(config.getClientConnectionTimeoutMs());
        try {
            while (true) {
                NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
                if (received == null)
                    throw new IOException("No response from relay");
                if (received.packet().payload() instanceof PacketPayload.ConnectAccept ca) {
                    this.clientId = ca.clientId();
                    this.sessionToken = ca.token();
                    logger.info("Connected as client " + (clientId & 0xFF) + " to session " + sessionId);
                    return;
                }
                if (received.packet().payload() instanceof PacketPayload.ConnectDeny deny) {
                    throw new IOException("Connection denied: " + deny.reason());
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Connection timed out", e);
        } finally {
            socket.setBlocking(false);
            socket.setTimeout(0);
        }
    }

    @Override
    protected void doStop() throws Exception {
        try {
            socket.sendPacket(NeonPacket.create(PacketType.DISCONNECT_NOTICE, nextSeq(),
                    clientId != null ? clientId : (byte) 0, (byte) 0,
                    new PacketPayload.DisconnectNotice()), relayAddr);
        } catch (IOException ignored) {
        }
        socket.close();
    }

    /**
     * Drives the client processing loop until {@link #stop()} is called or an
     * unrecoverable
     * IO error occurs. Drains inbound packets and fires auto-pings on each
     * iteration.
     * Intended to run in a dedicated virtual thread:
     * 
     * <pre>{@code
     * Thread.ofVirtual().start(client::run);
     * }</pre>
     */
    public void run() {
        while (isRunning()) {
            try {
                processPackets();
                checkAutoPing();
                Thread.sleep(config.getClientProcessingLoopSleepMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    logger.warning("IO error in client loop: " + e.getMessage());
                }
                break;
            }
        }
    }

    /**
     * Drains all currently buffered inbound packets and fires the relevant
     * callbacks.
     * Use this instead of {@link #run()} when integrating with an external game
     * loop.
     *
     * @throws IOException if a socket error occurs while receiving
     */
    public void processPackets() throws IOException {
        NeonSocket.ReceivedNeonPacket received;
        while ((received = socket.receivePacket()) != null) {
            handlePacket(received.packet());
        }
    }

    private void handlePacket(NeonPacket packet) throws IOException {
        PacketHeader header = packet.header();
        if (clientId != null && header.destinationId() != 0 && header.destinationId() != clientId) {
            return;
        }
        switch (packet.payload()) {
            case PacketPayload.Pong p -> {
                if (pongCallback != null)
                    pongCallback.accept(p);
            }
            case PacketPayload.SessionConfig sc ->
                handleSessionConfig(sc, header.sequence());
            case PacketPayload.PacketTypeRegistry reg -> {
                if (packetTypeRegistryCallback != null)
                    packetTypeRegistryCallback.accept(reg);
            }
            case PacketPayload.Ping ping ->
                sendPong(ping.timestamp());
            case @SuppressWarnings("unused") PacketPayload.DisconnectNotice ignored -> {
                if (disconnectCallback != null)
                    disconnectCallback.accept(header.clientId());
            }
            case PacketPayload.GamePacket gp -> {
                if (unhandledPacketCallback != null)
                    unhandledPacketCallback.accept(header.packetType(), header.clientId(), gp.payload());
            }
            default -> {
            }
        }
    }

    private void handleSessionConfig(PacketPayload.SessionConfig sc, short sequence) throws IOException {
        if (sessionConfigCallback != null)
            sessionConfigCallback.accept(sc);
        socket.sendPacket(NeonPacket.create(PacketType.ACK, nextSeq(),
                clientId != null ? clientId : (byte) 0, (byte) 0,
                new PacketPayload.Ack(List.of(sequence))), relayAddr);
    }

    private void sendPong(long timestamp) throws IOException {
        socket.sendPacket(NeonPacket.create(PacketType.PONG, nextSeq(),
                clientId != null ? clientId : (byte) 0, (byte) 1,
                new PacketPayload.Pong(timestamp)), relayAddr);
    }

    private void checkAutoPing() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastPingTime >= config.getClientPingIntervalMs()) {
            lastPingTime = now;
            socket.sendPacket(NeonPacket.create(PacketType.PING, nextSeq(),
                    clientId != null ? clientId : (byte) 0, (byte) 1,
                    new PacketPayload.Ping(now)), relayAddr);
        }
    }

    /**
     * Attempts to rejoin the last session using the stored session token.
     * Uses the max reconnect attempts from configuration with exponential backoff.
     * Creates a new socket if the existing one is closed.
     *
     * @return {@code true} if reconnection succeeded
     */
    public boolean reconnect() {
        return reconnect(config.getClientMaxReconnectAttempts());
    }

    /**
     * Attempts to rejoin the last session using the stored session token,
     * retrying up to {@code maxAttempts} times with exponential backoff.
     *
     * @param maxAttempts maximum number of reconnect attempts
     * @return {@code true} if reconnection succeeded
     */
    public boolean reconnect(int maxAttempts) {
        if (sessionToken == null || sessionId == null || clientId == null || relayAddr == null) {
            logger.warning("Cannot reconnect: missing session state");
            return false;
        }
        if (socket.isClosed()) {
            try {
                socket = new NeonSocket(config);
                if (config.isDtlsEnabled())
                    socket.performClientHandshake(config.getSslContext(), relayAddr,
                            config.getClientConnectionTimeoutMs());
            } catch (IOException e) {
                logger.warning("Failed to recreate socket for reconnect: " + e.getMessage());
                return false;
            }
        }
        long delay = config.getClientInitialReconnectDelayMs();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                delay = Math.min(delay * 2, config.getClientMaxReconnectDelayMs());
            }
            if (attemptReconnect())
                return true;
        }
        return false;
    }

    private boolean attemptReconnect() {
        try {
            socket.sendPacket(NeonPacket.create(PacketType.RECONNECT_REQUEST, nextSeq(),
                    clientId, (byte) 1,
                    new PacketPayload.ReconnectRequest(sessionToken, sessionId, clientId)), relayAddr);

            socket.setBlocking(true);
            socket.setTimeout(config.getClientConnectionTimeoutMs());
            try {
                while (true) {
                    NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
                    if (received == null)
                        return false;
                    if (received.packet().payload() instanceof PacketPayload.ConnectAccept ca) {
                        this.sessionToken = ca.token();
                        logger.info("Reconnected as client " + (clientId & 0xFF));
                        return true;
                    }
                    if (received.packet().payload() instanceof PacketPayload.ConnectDeny d) {
                        logger.warning("Reconnect denied: " + d.reason());
                        return false;
                    }
                }
            } catch (SocketTimeoutException e) {
                return false;
            } finally {
                socket.setBlocking(false);
                socket.setTimeout(0);
            }
        } catch (IOException e) {
            logger.warning("Reconnect attempt failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sends a game-specific payload through the relay.
     *
     * @param payload    raw payload bytes
     * @param packetType application-defined packet type byte registered via
     *                   {@link GamePacketRegistry}
     * @param destId     destination client ID, or {@code 0} to broadcast to all
     *                   session peers
     * @throws IOException if the send fails
     */
    public void sendPacket(byte[] payload, byte packetType, byte destId) throws IOException {
        PacketHeader header = PacketHeader.create(packetType, nextSeq(),
                clientId != null ? clientId : (byte) 0, destId);
        socket.sendPacket(new NeonPacket(header, new PacketPayload.GamePacket(payload)), relayAddr);
    }

    /**
     * Callback fired when a {@link PacketPayload.Pong} is received in response to
     * an auto-ping.
     */
    public void setPongCallback(Consumer<PacketPayload.Pong> cb) {
        this.pongCallback = cb;
    }

    /**
     * Callback fired when the host delivers a {@link PacketPayload.SessionConfig}.
     * The client ACKs automatically.
     */
    public void setSessionConfigCallback(Consumer<PacketPayload.SessionConfig> cb) {
        this.sessionConfigCallback = cb;
    }

    /**
     * Callback fired when the host broadcasts its
     * {@link PacketPayload.PacketTypeRegistry}.
     */
    public void setPacketTypeRegistryCallback(Consumer<PacketPayload.PacketTypeRegistry> cb) {
        this.packetTypeRegistryCallback = cb;
    }

    /**
     * Callback fired for packets whose type is not handled internally.
     * Arguments are {@code (packetType, senderId, payload)}.
     */
    public void setUnhandledPacketCallback(TriConsumer<Byte, Byte, byte[]> cb) {
        this.unhandledPacketCallback = cb;
    }

    /**
     * Callback fired when a {@link PacketPayload.DisconnectNotice} is received for
     * another peer. The argument is the disconnecting client's ID.
     */
    public void setDisconnectCallback(Consumer<Byte> cb) {
        this.disconnectCallback = cb;
    }

    /**
     * Returns the client ID assigned by the host, or {@code null} if not yet
     * connected.
     */
    public Byte getClientId() {
        return clientId;
    }

    /** Returns the local address of the underlying UDP socket. */
    public InetSocketAddress getLocalAddress() throws IOException {
        return socket.getLocalAddress();
    }

    private short nextSeq() {
        return (short) nextSequence.getAndIncrement();
    }

    private static InetSocketAddress parseAddress(String address) {
        int lastColon = address.lastIndexOf(':');
        return new InetSocketAddress(
                address.substring(0, lastColon),
                Integer.parseInt(address.substring(lastColon + 1)));
    }

    @Override
    public void close() throws Exception {
        if (isRunning())
            stop();
        else if (!socket.isClosed())
            socket.close();
    }
}
