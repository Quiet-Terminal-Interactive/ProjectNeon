package com.quietterminal.projectneon.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.DatagramChannel;

/**
 * UDP implementation of the Transport interface.
 * Uses DatagramChannel for non-blocking I/O support.
 *
 * @since 1.1
 */
public class UdpTransport implements Transport {

    private final DatagramChannel channel;
    private final DatagramSocket socket;
    private final ByteBufferPool bufferPool;
    private final NeonConfig config;

    /**
     * Creates a UDP transport with default configuration.
     *
     * @throws IOException if creation fails
     */
    public UdpTransport() throws IOException {
        this(new NeonConfig());
    }

    /**
     * Creates a UDP transport with custom configuration.
     *
     * @param config the configuration
     * @throws IOException if creation fails
     */
    public UdpTransport(NeonConfig config) throws IOException {
        this.config = config;
        this.channel = DatagramChannel.open();
        this.socket = channel.socket();
        this.bufferPool = new ByteBufferPool(
            config.getBufferSize(),
            config.getBufferPoolInitialSize(),
            config.getBufferPoolMaxSize()
        );
    }

    @Override
    public Type getType() {
        return Type.UDP;
    }

    @Override
    public void bind(int port) throws IOException {
        socket.bind(new InetSocketAddress(port));
        channel.configureBlocking(false);
    }

    @Override
    public SocketAddress getLocalAddress() {
        return socket.getLocalSocketAddress();
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, address);
        socket.send(packet);
    }

    @Override
    public ReceivedData receive() throws IOException {
        byte[] buffer = bufferPool.acquire();
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

        try {
            socket.receive(datagram);
            int length = datagram.getLength();

            if (config.isEnforceBufferSize() && length == buffer.length) {
                bufferPool.release(buffer);
                return null;
            }

            byte[] data = new byte[length];
            System.arraycopy(buffer, 0, data, 0, length);
            bufferPool.release(buffer);
            return new ReceivedData(data, datagram.getSocketAddress());
        } catch (SocketTimeoutException e) {
            bufferPool.release(buffer);
            throw e;
        } catch (IOException e) {
            bufferPool.release(buffer);
            if (!channel.isBlocking()) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public void setBlocking(boolean blocking) throws IOException {
        channel.configureBlocking(blocking);
    }

    @Override
    public boolean isBlocking() {
        return channel.isBlocking();
    }

    @Override
    public void setTimeout(int timeoutMs) throws SocketException {
        socket.setSoTimeout(timeoutMs);
    }

    @Override
    public boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        channel.close();
        socket.close();
    }
}
