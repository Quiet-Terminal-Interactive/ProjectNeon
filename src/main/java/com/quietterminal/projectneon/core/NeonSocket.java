package com.quietterminal.projectneon.core;

import java.io.IOException;
import java.net.*;
import java.nio.channels.DatagramChannel;

/**
 * UDP socket wrapper for Neon protocol operations.
 * Supports both blocking and non-blocking modes.
 */
public class NeonSocket implements AutoCloseable {
    private static final int BUFFER_SIZE = 1024;
    private final DatagramChannel channel;
    private final DatagramSocket socket;
    private final byte[] receiveBuffer;

    /**
     * Creates a new UDP socket bound to any available port.
     */
    public NeonSocket() throws IOException {
        this(0); // Bind to any available port
    }

    /**
     * Creates a new UDP socket bound to the specified port.
     */
    public NeonSocket(int port) throws IOException {
        this.channel = DatagramChannel.open();
        this.socket = channel.socket();
        this.socket.bind(new InetSocketAddress(port));
        this.receiveBuffer = new byte[BUFFER_SIZE];
        setBlocking(false); // Default to non-blocking
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
        DatagramPacket datagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        try {
            socket.receive(datagram);
            byte[] data = new byte[datagram.getLength()];
            System.arraycopy(receiveBuffer, 0, data, 0, datagram.getLength());
            return new ReceivedPacket(data, datagram.getSocketAddress());
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
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
        } catch (Exception e) {
            System.err.println("Failed to parse packet from " + received.source() + ": " + e.getMessage());
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

    /**
     * Helper record for received raw packets.
     */
    public record ReceivedPacket(byte[] data, SocketAddress source) {}

    /**
     * Helper record for received Neon packets.
     */
    public record ReceivedNeonPacket(NeonPacket packet, SocketAddress source) {}
}
