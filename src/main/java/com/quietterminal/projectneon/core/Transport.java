package com.quietterminal.projectneon.core;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * Abstract transport layer for Neon protocol communication.
 * Allows pluggable transport implementations (UDP, TCP, QUIC, etc.).
 *
 * <p>The transport is responsible for:
 * <ul>
 *   <li>Binding to a local address</li>
 *   <li>Sending packets to remote addresses</li>
 *   <li>Receiving packets from any sender</li>
 *   <li>Managing socket options and timeouts</li>
 * </ul>
 *
 * @since 1.1
 */
public interface Transport extends AutoCloseable {

    /**
     * Transport types supported by the protocol.
     */
    enum Type {
        UDP,
        TCP,
        QUIC
    }

    /**
     * Returns the transport type.
     *
     * @return the transport type
     */
    Type getType();

    /**
     * Binds the transport to the specified port.
     * Use port 0 to bind to any available port.
     *
     * @param port the port to bind to
     * @throws IOException if binding fails
     */
    void bind(int port) throws IOException;

    /**
     * Returns the local address this transport is bound to.
     *
     * @return the local socket address
     */
    SocketAddress getLocalAddress();

    /**
     * Sends raw bytes to the specified address.
     *
     * @param data the data to send
     * @param address the destination address
     * @throws IOException if sending fails
     */
    void send(byte[] data, SocketAddress address) throws IOException;

    /**
     * Sends a Neon packet to the specified address.
     *
     * @param packet the packet to send
     * @param address the destination address
     * @throws IOException if sending fails
     */
    default void sendPacket(NeonPacket packet, SocketAddress address) throws IOException {
        send(packet.toBytes(), address);
    }

    /**
     * Receives a packet from the transport.
     * Returns null if no packet is available (non-blocking) or throws on timeout.
     *
     * @return the received packet or null
     * @throws IOException if receiving fails
     */
    ReceivedData receive() throws IOException;

    /**
     * Sets blocking mode for the transport.
     *
     * @param blocking true for blocking mode
     * @throws IOException if setting fails
     */
    void setBlocking(boolean blocking) throws IOException;

    /**
     * Checks if the transport is in blocking mode.
     *
     * @return true if blocking
     */
    boolean isBlocking();

    /**
     * Sets the receive timeout in milliseconds.
     * Set to 0 for infinite timeout.
     *
     * @param timeoutMs the timeout in milliseconds
     * @throws IOException if setting fails
     */
    void setTimeout(int timeoutMs) throws IOException;

    /**
     * Checks if the transport is closed.
     *
     * @return true if closed
     */
    boolean isClosed();

    /**
     * Closes the transport and releases resources.
     *
     * @throws IOException if closing fails
     */
    @Override
    void close() throws IOException;

    /**
     * Container for received data with source address.
     */
    record ReceivedData(byte[] data, SocketAddress source) {
        /**
         * Returns the length of the received data.
         *
         * @return the data length
         */
        public int length() {
            return data.length;
        }
    }
}
