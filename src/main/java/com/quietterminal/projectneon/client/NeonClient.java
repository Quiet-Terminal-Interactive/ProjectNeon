package com.quietterminal.projectneon.client;

import com.quietterminal.projectneon.core.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Neon protocol client implementation.
 */
public class NeonClient implements AutoCloseable {
    private static final long DEFAULT_PING_INTERVAL_MS = 5000;
    private static final int CONNECTION_TIMEOUT_MS = 10000;

    private final NeonSocket socket;
    private final String name;
    private SocketAddress relayAddr;
    private Byte clientId;
    private Integer sessionId;
    private short nextSequence = 0;

    private boolean autoPing = true;
    private long pingIntervalMs = DEFAULT_PING_INTERVAL_MS;
    private long lastPingTime = 0;

    private BiConsumer<Long, Long> pongCallback; // (responseTimeMs, originalTimestamp)
    private TriConsumer<Byte, Short, Short> sessionConfigCallback; // (version, tickRate, maxPacketSize)
    private Consumer<PacketPayload.PacketTypeRegistry> packetTypeRegistryCallback;
    private BiConsumer<Byte, Byte> unhandledPacketCallback; // (packetType, fromClientId)
    private BiConsumer<Byte, Byte> wrongDestinationCallback; // (myId, packetDestinationId)

    public NeonClient(String name) throws IOException {
        this.name = name;
        this.socket = new NeonSocket();
    }

    /**
     * Connects to a relay server and joins a session.
     */
    public boolean connect(int sessionId, String relayAddress) throws IOException {
        String[] parts = relayAddress.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid relay address format. Expected host:port");
        }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        this.relayAddr = new InetSocketAddress(host, port);
        this.sessionId = sessionId;

        socket.setBlocking(true);
        socket.setSoTimeout(CONNECTION_TIMEOUT_MS);

        PacketPayload.ConnectRequest request = new PacketPayload.ConnectRequest(
            PacketHeader.VERSION, name, sessionId, 0 // game_identifier unused for now
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

                    NeonPacket confirmation = NeonPacket.create(
                        PacketType.CONNECT_ACCEPT, nextSequence++, clientId, (byte) 0, accept
                    );
                    socket.sendPacket(confirmation, relayAddr);

                    socket.setBlocking(false);
                    return true;
                }

                if (received.packet().payload() instanceof PacketPayload.ConnectDeny deny) {
                    System.err.println("Connection denied: " + deny.reason());
                    return false;
                }
            }
        } catch (IOException e) {
            socket.setBlocking(false);
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
            NeonSocket.ReceivedNeonPacket received = socket.receivePacket();
            if (received == null) break;

            handlePacket(received.packet());
            count++;
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
            Thread.sleep(10);
        }
    }

    public Optional<Byte> getClientId() {
        return Optional.ofNullable(clientId);
    }

    public Optional<Integer> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public boolean isConnected() {
        return clientId != null;
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

    @Override
    public void close() throws IOException {
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
