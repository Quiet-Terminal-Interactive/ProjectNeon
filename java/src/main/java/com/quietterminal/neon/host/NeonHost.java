package com.quietterminal.neon.host;

import com.quietterminal.neon.core.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Session host for the Neon multiplayer protocol.
 *
 * <p>
 * A {@code NeonHost} registers a session with a
 * {@link com.quietterminal.neon.relay.NeonRelay},
 * accepts client connections, manages session state, and reliably delivers
 * {@link PacketPayload.SessionConfig}
 * to each connecting client. The host always occupies client ID {@code 1};
 * player IDs start at {@code 2}.
 *
 * <p>
 * Typical usage:
 * 
 * <pre>{@code
 * NeonHost host = new NeonHost(sessionId, "relay.example.com:9000", NeonConfig.defaults());
 * host.setClientConnectCallback((id, name, sid) -> System.out.println(name + " joined"));
 * Thread.ofVirtual().start(() -> host.startAndRun());
 * }</pre>
 */
public final class NeonHost extends AbstractLifecycle implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NeonHost.class.getName());

    private record DisconnectedClient(String name, long token, long disconnectedAt) {
    }

    private final NeonSocket socket;
    private final NeonConfig config;
    private final int sessionId;
    private final SocketAddress relayAddr;
    private final AtomicInteger nextClientId = new AtomicInteger(2);
    private final AtomicInteger nextSequence = new AtomicInteger(0);
    private final SecureRandom secureRandom = new SecureRandom();
    private final AckStateMachine ackStateMachine;
    private final ConcurrentHashMap<Byte, String> connectedClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Byte> connectedNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Byte, Long> clientTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Byte, DisconnectedClient> disconnectedClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Short, Byte> sequenceToClient = new ConcurrentHashMap<>();

    private TriConsumer<Byte, String, Integer> clientConnectCallback;
    private BiConsumer<String, String> clientDenyCallback;
    private Consumer<Byte> clientDisconnectCallback;
    private Consumer<Byte> pingReceivedCallback;
    private TriConsumer<Byte, Byte, byte[]> unhandledPacketCallback;
    private GamePacketRegistry gamePacketRegistry;

    /**
     * Creates a new host for the given session.
     *
     * @param sessionId    unique session identifier
     * @param relayAddress relay address in {@code "host:port"} format
     * @param config       protocol configuration
     * @throws IOException if the local socket cannot be bound
     */
    public NeonHost(int sessionId, String relayAddress, NeonConfig config) throws IOException {
        this.sessionId = sessionId;
        this.config = config;
        this.ackStateMachine = new AckStateMachine(config.getHostAckTimeoutMs(), config.getHostMaxAckRetries());
        int lastColon = relayAddress.lastIndexOf(':');
        String host = relayAddress.substring(0, lastColon);
        int port = Integer.parseInt(relayAddress.substring(lastColon + 1));
        this.relayAddr = new InetSocketAddress(host, port);
        this.socket = new NeonSocket(config);
    }

    /**
     * Callback fired when a client successfully joins or rejoins the session.
     * Arguments are {@code (clientId, playerName, sessionId)}.
     */
    public void setClientConnectCallback(TriConsumer<Byte, String, Integer> cb) {
        this.clientConnectCallback = cb;
    }

    /**
     * Callback fired when a connection request is denied.
     * Arguments are {@code (playerName, reason)}.
     */
    public void setClientDenyCallback(BiConsumer<String, String> cb) {
        this.clientDenyCallback = cb;
    }

    /**
     * Callback fired when a client disconnects. The argument is the disconnecting
     * client's ID.
     */
    public void setClientDisconnectCallback(Consumer<Byte> cb) {
        this.clientDisconnectCallback = cb;
    }

    /**
     * Callback fired when a PING is received from a client. The argument is the
     * sender's client ID.
     */
    public void setPingReceivedCallback(Consumer<Byte> cb) {
        this.pingReceivedCallback = cb;
    }

    /**
     * Callback fired for game-specific packets not handled by the protocol layer.
     * Arguments are {@code (packetType, senderId, payload)}.
     */
    public void setUnhandledPacketCallback(TriConsumer<Byte, Byte, byte[]> cb) {
        this.unhandledPacketCallback = cb;
    }

    /**
     * Sets the game packet registry used to advertise custom packet types to
     * connecting clients.
     * If not set, an empty registry is sent.
     */
    public void setGamePacketRegistry(GamePacketRegistry registry) {
        this.gamePacketRegistry = registry;
    }

    @Override
    protected void doStart() throws Exception {
        if (config.isDtlsEnabled())
            socket.performClientHandshake(config.getSslContext(), relayAddr, config.getClientConnectionTimeoutMs());

        long hostToken = secureRandom.nextLong();
        clientTokens.put((byte) 1, hostToken);
        socket.sendPacket(NeonPacket.create(PacketType.HOST_REGISTER, nextSeq(), (byte) 1, (byte) 0,
                new PacketPayload.HostRegister(sessionId, hostToken)), relayAddr);

        socket.setBlocking(true);
        socket.setTimeout(config.getClientConnectionTimeoutMs());
        try {
            while (true) {
                NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
                if (received == null)
                    throw new IOException("No response from relay during registration");
                if (received.packet().payload() instanceof PacketPayload.ConnectAccept ca && ca.clientId() == 1) {
                    logger.info("Registered with relay for session " + sessionId);
                    return;
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Relay registration timed out", e);
        } finally {
            socket.setBlocking(false);
            socket.setTimeout(0);
        }
    }

    @Override
    protected void doStop() throws Exception {
        try {
            socket.sendPacket(NeonPacket.create(PacketType.DISCONNECT_NOTICE, nextSeq(), (byte) 1, (byte) 0,
                    new PacketPayload.DisconnectNotice()), relayAddr);
        } catch (IOException ignored) {
        }

        long deadline = System.currentTimeMillis() + config.getHostGracefulShutdownTimeoutMs();
        while (ackStateMachine.hasPending() && System.currentTimeMillis() < deadline) {
            AckStateMachine.ProcessResult result = ackStateMachine.process();
            for (short seq : result.needsRetry()) {
                NeonPacket pkt = ackStateMachine.markResent(seq);
                if (pkt != null) {
                    try {
                        socket.sendPacket(pkt, relayAddr);
                    } catch (IOException ignored) {
                    }
                }
            }
            for (short seq : result.failed()) {
                ackStateMachine.acknowledge(seq);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        socket.close();
    }

    /**
     * Registers with the relay and then runs the packet processing loop until
     * {@link #stop()} is called.
     * Intended to run in a dedicated virtual thread.
     *
     * @throws Exception if registration with the relay fails
     */
    public void startAndRun() throws Exception {
        start();
        run();
    }

    private void run() {
        while (isRunning()) {
            try {
                processPackets();
                checkPendingAcks();
                Thread.sleep(config.getHostProcessingLoopSleepMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    logger.warning("IO error in host loop: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void processPackets() throws IOException {
        NeonSocket.ReceivedNeonPacket received;
        while ((received = socket.receivePacket()) != null) {
            handlePacket(received.packet());
        }
    }

    private void handlePacket(NeonPacket packet) throws IOException {
        PacketHeader header = packet.header();
        switch (packet.payload()) {
            case PacketPayload.ConnectRequest req -> handleConnectRequest(req, header);
            case PacketPayload.ReconnectRequest req -> handleReconnectRequest(req, header);
            case PacketPayload.Ping ping -> handlePing(ping, header);
            case PacketPayload.Ack ack -> handleAck(ack);
            case @SuppressWarnings("unused") PacketPayload.DisconnectNotice ignored -> handleDisconnect(header);
            default -> dispatchToGame(packet);
        }
    }

    private void handleConnectRequest(PacketPayload.ConnectRequest req, PacketHeader header) throws IOException {
        if (connectedClients.size() >= config.getMaxClientsPerSession()) {
            sendDeny(req.name(), "Session full");
            return;
        }
        Byte existing = connectedNames.putIfAbsent(req.name(), (byte) 0);
        if (existing != null) {
            sendDeny(req.name(), "Name taken");
            return;
        }
        int idInt = nextClientId.getAndIncrement();
        byte id = (byte) idInt;
        if (id == 0 || id == 1) {
            connectedNames.remove(req.name());
            sendDeny(req.name(), "Server full");
            return;
        }
        long token = secureRandom.nextLong();
        connectedClients.put(id, req.name());
        connectedNames.put(req.name(), id);
        clientTokens.put(id, token);

        socket.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, nextSeq(), (byte) 1, (byte) 0,
                new PacketPayload.ConnectAccept(id, sessionId, token)), relayAddr);

        if (clientConnectCallback != null)
            clientConnectCallback.accept(id, req.name(), sessionId);

        short cfgSeq = nextSeq();
        NeonPacket cfgPacket = NeonPacket.create(PacketType.SESSION_CONFIG, cfgSeq, (byte) 1, id,
                new PacketPayload.SessionConfig(PacketHeader.VERSION, config.getHostSessionTickRate(),
                        config.getHostSessionMaxPacketSize()));
        socket.sendPacket(cfgPacket, relayAddr);
        ackStateMachine.track(cfgSeq, cfgPacket);
        sequenceToClient.put(cfgSeq, id);

        PacketPayload.PacketTypeRegistry registry = (gamePacketRegistry != null && !gamePacketRegistry.isEmpty())
                ? gamePacketRegistry.buildRegistry()
                : new PacketPayload.PacketTypeRegistry(List.of());
        socket.sendPacket(NeonPacket.create(PacketType.PACKET_TYPE_REGISTRY, nextSeq(), (byte) 1, id,
                registry), relayAddr);

        logger.info("Client " + (id & 0xFF) + " (" + req.name() + ") connected to session " + sessionId);
    }

    private void handleReconnectRequest(PacketPayload.ReconnectRequest req, PacketHeader header) throws IOException {
        byte prevId = req.previousClientId();
        DisconnectedClient dc = disconnectedClients.get(prevId);
        if (dc == null) {
            sendDeny("", "Reconnect denied");
            return;
        }
        if (dc.token() != req.token()) {
            sendDeny(dc.name(), "Reconnect denied");
            return;
        }
        if (System.currentTimeMillis() - dc.disconnectedAt() > config.getHostSessionTokenTimeoutMs()) {
            disconnectedClients.remove(prevId);
            sendDeny(dc.name(), "Token expired");
            return;
        }
        disconnectedClients.remove(prevId);
        connectedClients.put(prevId, dc.name());
        connectedNames.put(dc.name(), prevId);
        long newToken = secureRandom.nextLong();
        clientTokens.put(prevId, newToken);

        socket.sendPacket(NeonPacket.create(PacketType.CONNECT_ACCEPT, nextSeq(), (byte) 1, (byte) 0,
                new PacketPayload.ConnectAccept(prevId, sessionId, newToken)), relayAddr);

        if (clientConnectCallback != null)
            clientConnectCallback.accept(prevId, dc.name(), sessionId);
        logger.info("Client " + (prevId & 0xFF) + " (" + dc.name() + ") reconnected to session " + sessionId);
    }

    private void handlePing(PacketPayload.Ping ping, PacketHeader header) throws IOException {
        socket.sendPacket(NeonPacket.create(PacketType.PONG, nextSeq(), (byte) 1, header.clientId(),
                new PacketPayload.Pong(ping.timestamp())), relayAddr);
        if (pingReceivedCallback != null)
            pingReceivedCallback.accept(header.clientId());
    }

    private void handleAck(PacketPayload.Ack ack) {
        for (short seq : ack.sequences()) {
            ackStateMachine.acknowledge(seq);
            sequenceToClient.remove(seq);
        }
    }

    private void handleDisconnect(PacketHeader header) {
        byte id = header.clientId();
        String name = connectedClients.remove(id);
        if (name == null)
            return;
        connectedNames.remove(name);
        Long token = clientTokens.get(id);
        disconnectedClients.put(id, new DisconnectedClient(name, token != null ? token : 0L,
                System.currentTimeMillis()));
        sequenceToClient.entrySet().removeIf(e -> e.getValue() == id);
        if (clientDisconnectCallback != null)
            clientDisconnectCallback.accept(id);
        logger.info("Client " + (id & 0xFF) + " (" + name + ") disconnected from session " + sessionId);
    }

    private void dispatchToGame(NeonPacket packet) {
        if (unhandledPacketCallback != null) {
            byte[] payload = packet.payload() instanceof PacketPayload.GamePacket gp ? gp.payload() : new byte[0];
            unhandledPacketCallback.accept(packet.header().packetType(), packet.header().clientId(), payload);
        }
    }

    private void checkPendingAcks() throws IOException {
        AckStateMachine.ProcessResult result = ackStateMachine.process();
        for (short seq : result.needsRetry()) {
            NeonPacket pkt = ackStateMachine.markResent(seq);
            if (pkt != null)
                socket.sendPacket(pkt, relayAddr);
        }
        for (short seq : result.failed()) {
            logger.warning("ACK permanently failed for sequence " + (seq & 0xFFFF));
            ackStateMachine.acknowledge(seq);
            sequenceToClient.remove(seq);
        }
    }

    private void sendDeny(String name, String reason) throws IOException {
        if (clientDenyCallback != null && !name.isEmpty())
            clientDenyCallback.accept(name, reason);
        socket.sendPacket(NeonPacket.create(PacketType.CONNECT_DENY, nextSeq(), (byte) 1, (byte) 0,
                new PacketPayload.ConnectDeny(reason)), relayAddr);
    }

    /**
     * Sends a game-specific payload through the relay.
     *
     * @param payload    raw payload bytes
     * @param packetType application-defined packet type byte
     * @param destId     destination client ID, or {@code 0} to broadcast to all
     *                   session peers
     * @throws IOException if the send fails
     */
    public void sendPacket(byte[] payload, byte packetType, byte destId) throws IOException {
        PacketHeader header = PacketHeader.create(packetType, nextSeq(), (byte) 1, destId);
        socket.sendPacket(new NeonPacket(header, new PacketPayload.GamePacket(payload)), relayAddr);
    }

    private short nextSeq() {
        return (short) nextSequence.getAndIncrement();
    }

    /** Returns the local address of the underlying UDP socket. */
    public InetSocketAddress getLocalAddress() throws IOException {
        return socket.getLocalAddress();
    }

    @Override
    public void close() throws Exception {
        if (isRunning())
            stop();
        else if (!socket.isClosed())
            socket.close();
    }
}
