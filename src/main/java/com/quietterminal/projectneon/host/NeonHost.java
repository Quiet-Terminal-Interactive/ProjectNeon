package com.quietterminal.projectneon.host;

import com.quietterminal.projectneon.core.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Neon protocol host implementation.
 * The host manages game sessions and coordinates clients through a relay.
 */
public class NeonHost implements AutoCloseable {
    private static final byte HOST_CLIENT_ID = 1;
    private static final byte FIRST_CLIENT_ID = 2;
    private static final int ACK_TIMEOUT_MS = 2000;
    private static final int MAX_ACK_RETRIES = 5;
    private static final long RELIABILITY_DELAY_MS = 50;

    private final NeonSocket socket;
    private final int sessionId;
    private SocketAddress relayAddr;
    private byte nextClientId = FIRST_CLIENT_ID;
    private short nextSequence = 0;

    private final Map<Byte, String> connectedClients = new ConcurrentHashMap<>();
    private final Map<Byte, PendingAck> pendingAcks = new ConcurrentHashMap<>();

    private TriConsumer<Byte, String, Integer> clientConnectCallback; // (clientId, name, sessionId)
    private BiConsumer<String, String> clientDenyCallback; // (name, reason)
    private Consumer<Byte> pingReceivedCallback; // (fromClientId)
    private BiConsumer<Byte, Byte> unhandledPacketCallback; // (packetType, fromClientId)

    public NeonHost(int sessionId, String relayAddress) throws IOException {
        this.sessionId = sessionId;
        this.socket = new NeonSocket();

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
        PacketPayload.ConnectAccept registration = new PacketPayload.ConnectAccept(HOST_CLIENT_ID, sessionId);
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
            NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
            if (received == null) break;

            handlePacket(received.packet());
            count++;
        }
        return count;
    }

    private void handlePacket(NeonPacket packet) throws IOException {
        PacketHeader header = packet.header();

        switch (packet.payload()) {
            case PacketPayload.ConnectRequest request -> handleConnectRequest(request, header);
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

        PacketPayload.ConnectAccept accept = new PacketPayload.ConnectAccept(assignedId, sessionId);
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
                    System.err.println("Client " + pending.clientId() + " failed to ACK after " + MAX_ACK_RETRIES + " retries");
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

    @Override
    public void close() throws IOException {
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
     * Functional interface for callbacks with three parameters.
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
