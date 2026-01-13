package com.quietterminal.projectneon.host;

import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.util.LoggerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Neon protocol host implementation.
 * The host manages game sessions and coordinates clients through a relay.
 */
public class NeonHost implements AutoCloseable {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(NeonHost.class.getName());
        LoggerConfig.configureLogger(logger);
    }
    private static final byte HOST_CLIENT_ID = 1;
    private static final byte FIRST_CLIENT_ID = 2;
    private static final int ACK_TIMEOUT_MS = 2000;
    private static final int MAX_ACK_RETRIES = 5;
    private static final long RELIABILITY_DELAY_MS = 50;
    private static final int GRACEFUL_SHUTDOWN_TIMEOUT_MS = 2000;
    private static final long SESSION_TOKEN_TIMEOUT_MS = 300000;

    private final NeonSocket socket;
    private final int sessionId;
    private SocketAddress relayAddr;
    private byte nextClientId = FIRST_CLIENT_ID;
    private short nextSequence = 0;

    private final Map<Byte, String> connectedClients = new ConcurrentHashMap<>();
    private final Map<Byte, PendingAck> pendingAcks = new ConcurrentHashMap<>();
    private final Map<Byte, Long> clientTokens = new ConcurrentHashMap<>();
    private final Map<Byte, DisconnectedClient> disconnectedClients = new ConcurrentHashMap<>();
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    private TriConsumer<Byte, String, Integer> clientConnectCallback; // (clientId, name, sessionId)
    private BiConsumer<String, String> clientDenyCallback; // (name, reason)
    private Consumer<Byte> pingReceivedCallback; // (fromClientId)
    private BiConsumer<Byte, Byte> unhandledPacketCallback; // (packetType, fromClientId)
    private Consumer<Byte> clientDisconnectCallback; // (clientId)

    public NeonHost(int sessionId, String relayAddress) throws IOException {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("Session ID must be a positive integer, got: " + sessionId);
        }
        this.sessionId = sessionId;
        this.socket = new NeonSocket();
        this.socket.setBlocking(true);
        this.socket.setSoTimeout(100);

        String[] parts = relayAddress.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid relay address format. Expected host:port");
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        this.relayAddr = new InetSocketAddress(host, port);
    }

    /**
     * Starts the host and registers with the relay.
     * This is a blocking call that runs the host processing loop.
     */
    public void start() throws IOException, InterruptedException {
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

        while (true) {
            processPackets();
            checkPendingAcks();
            Thread.sleep(10);
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
                    pendingAcks.values().removeIf(pending -> pending.sequence() == seq);
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

                pendingAcks.remove(disconnectedClientId);
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
            Thread.sleep(RELIABILITY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        short seq = nextSequence++;
        PacketPayload.SessionConfig config = new PacketPayload.SessionConfig(
            PacketHeader.VERSION, (short) 60, (short) 1024
        );
        NeonPacket configPacket = NeonPacket.create(
            PacketType.SESSION_CONFIG, seq, HOST_CLIENT_ID, assignedId, config
        );
        socket.sendPacket(configPacket, relayAddr);

        pendingAcks.put(assignedId, new PendingAck(
            seq, assignedId, configPacket, System.currentTimeMillis(), 0
        ));

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
        if (now - disconnected.disconnectTime() > SESSION_TOKEN_TIMEOUT_MS) {
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
        long now = System.currentTimeMillis();
        List<Byte> toRemove = new ArrayList<>();

        for (Map.Entry<Byte, PendingAck> entry : pendingAcks.entrySet()) {
            PendingAck pending = entry.getValue();

            if (now - pending.lastSentTime() >= ACK_TIMEOUT_MS) {
                if (pending.retryCount() >= MAX_ACK_RETRIES) {
                    logger.log(Level.WARNING, "Client {0} failed to ACK after {1} retries [SessionID={2}, Sequence={3}]",
                        new Object[]{pending.clientId(), MAX_ACK_RETRIES, sessionId, pending.sequence()});
                    toRemove.add(entry.getKey());
                } else {
                    socket.sendPacket(pending.packet(), relayAddr);
                    pendingAcks.put(entry.getKey(), new PendingAck(
                        pending.sequence(), pending.clientId(), pending.packet(),
                        now, pending.retryCount() + 1
                    ));
                }
            }
        }

        toRemove.forEach(pendingAcks::remove);
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

            while (!pendingAcks.isEmpty() &&
                   System.currentTimeMillis() - shutdownStart < GRACEFUL_SHUTDOWN_TIMEOUT_MS) {
                try {
                    processPackets();
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Error during graceful shutdown [SessionID={0}]", sessionId);
                    break;
                }
            }

            if (!pendingAcks.isEmpty()) {
                logger.log(Level.WARNING, "Graceful shutdown timeout: {0} pending ACKs remaining [SessionID={1}]",
                    new Object[]{pendingAcks.size(), sessionId});
            }
        }
        socket.close();
    }

    /**
     * Tracks packets awaiting acknowledgment.
     */
    private record PendingAck(
        short sequence,
        byte clientId,
        NeonPacket packet,
        long lastSentTime,
        int retryCount
    ) {}

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
