package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.util.LoggerConfig;

import java.io.IOException;
import java.net.*;
import java.nio.BufferUnderflowException;
import java.nio.channels.DatagramChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UDP socket wrapper for Neon protocol operations.
 * Supports both blocking and non-blocking modes.
 */
public class NeonSocket implements AutoCloseable {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(NeonSocket.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    private final DatagramChannel channel;
    private final DatagramSocket socket;
    private final ByteBufferPool bufferPool;

    @SuppressWarnings("unused")
    private final NeonConfig config;

    /**
     * Creates a new UDP socket bound to any available port with default configuration.
     */
    public NeonSocket() throws IOException {
        this(0, new NeonConfig());
    }

    /**
     * Creates a new UDP socket bound to any available port with custom configuration.
     */
    public NeonSocket(NeonConfig config) throws IOException {
        this(0, config);
    }

    /**
     * Creates a new UDP socket bound to the specified port with default configuration.
     */
    public NeonSocket(int port) throws IOException {
        this(port, new NeonConfig());
    }

    /**
     * Creates a new UDP socket bound to the specified port with custom configuration.
     */
    public NeonSocket(int port, NeonConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        this.config = config;
        this.channel = DatagramChannel.open();
        this.socket = channel.socket();
        this.socket.bind(new InetSocketAddress(port));
        this.bufferPool = new ByteBufferPool(
            config.getBufferSize(),
            config.getBufferPoolInitialSize(),
            config.getBufferPoolMaxSize()
        );
        setBlocking(false);
    }

    /**
     * Sets the socket to blocking or non-blocking mode.
     */
    public void setBlocking(boolean blocking) throws IOException {
        channel.configureBlocking(blocking);
    }

    /**
     * Checks if the socket is in blocking mode.
     */
    public boolean isBlocking() {
        return channel.isBlocking();
    }

    /**
     * Gets the local address this socket is bound to.
     */
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    /**
     * Sends a packet to the specified address.
     */
    public void sendTo(byte[] data, SocketAddress address) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, address);
        socket.send(packet);
    }

    /**
     * Sends a Neon packet to the specified address.
     */
    public void sendPacket(NeonPacket packet, SocketAddress address) throws IOException {
        byte[] data = packet.toBytes();
        sendTo(data, address);
    }

    /**
     * Receives a packet from the socket.
     * Returns null if no packet is available (non-blocking mode).
     * Throws SocketTimeoutException in blocking mode with timeout.
     */
    public ReceivedPacket receive() throws IOException {
        byte[] receiveBuffer = bufferPool.acquire();
        DatagramPacket datagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        try {
            socket.receive(datagram);
            byte[] data = new byte[datagram.getLength()];
            System.arraycopy(receiveBuffer, 0, data, 0, datagram.getLength());
            bufferPool.release(receiveBuffer);
            return new ReceivedPacket(data, datagram.getSocketAddress());
        } catch (SocketTimeoutException e) {
            bufferPool.release(receiveBuffer);
            throw e;
        } catch (IOException e) {
            bufferPool.release(receiveBuffer);
            if (!channel.isBlocking()) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Receives and parses a Neon packet.
     * Returns null if no packet is available or if parsing fails.
     */
    public ReceivedNeonPacket receivePacket() throws IOException {
        ReceivedPacket received = receive();
        if (received == null) {
            return null;
        }

        try {
            NeonPacket packet = NeonPacket.fromBytes(received.data());
            return new ReceivedNeonPacket(packet, received.source());
        } catch (BufferUnderflowException e) {
            logger.log(Level.WARNING, "Buffer underflow parsing packet from {0}: packet too short or malformed", received.source());
            return null;
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Invalid packet from {0}: {1}", new Object[]{received.source(), e.getMessage()});
            return null;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected error parsing packet from " + received.source(), e);
            return null;
        }
    }

    /**
     * Sets a receive timeout for blocking mode (in milliseconds).
     * Set to 0 for infinite timeout.
     */
    public void setSoTimeout(int timeoutMs) throws SocketException {
        socket.setSoTimeout(timeoutMs);
    }

    @Override
    public void close() throws IOException {
        channel.close();
        socket.close();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    /**
     * Helper record for received raw packets.
     */
    public record ReceivedPacket(byte[] data, SocketAddress source) {}

    /**
     * Helper record for received Neon packets.
     */
    public record ReceivedNeonPacket(NeonPacket packet, SocketAddress source) {}
}
