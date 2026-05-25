package com.quietterminal.neon.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * UDP socket wrapper built on a non-blocking NIO
 * {@link java.nio.channels.DatagramChannel}.
 *
 * <p>
 * Always backed by a non-blocking channel; blocking semantics are emulated via
 * a
 * {@link java.nio.channels.Selector} with a configurable timeout so that
 * virtual threads
 * are never pinned to a carrier thread during network waits.
 *
 * <p>
 * When DTLS is active, encryption and decryption are transparent: {@link #send}
 * encrypts
 * outbound data for known peers and {@link #receive} decrypts inbound DTLS
 * records. Call
 * {@link #performClientHandshake} on host/client sockets before sending any
 * Neon packets.
 * Call {@link #enableServerDtls} on relay sockets to accept inbound DTLS
 * connections.
 */
public final class NeonSocket implements Transport, AutoCloseable {

    private static final Logger logger = Logger.getLogger(NeonSocket.class.getName());

    private final DatagramChannel channel;
    private final ByteBufferPool bufferPool;
    private final int bufferSize;
    private final boolean enforceBufferSize;
    private volatile boolean blocking = false;
    private volatile int timeoutMs = 0;

    private final ConcurrentHashMap<SocketAddress, DtlsSession> dtlsSessions = new ConcurrentHashMap<>();
    private volatile SSLContext serverDtlsContext;

    public NeonSocket(InetSocketAddress bindAddress, NeonConfig config) throws IOException {
        this.bufferSize = config.getBufferSize();
        this.enforceBufferSize = config.isEnforceBufferSize();
        channel = DatagramChannel.open();
        channel.bind(bindAddress);
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_RCVBUF, bufferSize);
        bufferPool = new ByteBufferPool(bufferSize, config.getBufferPoolInitSize(), config.getBufferPoolMaxSize());
    }

    public NeonSocket(int port, NeonConfig config) throws IOException {
        this(new InetSocketAddress(port), config);
    }

    public NeonSocket(NeonConfig config) throws IOException {
        this(0, config);
    }

    /**
     * Performs the DTLS client-side handshake with {@code peer}. Must be called
     * before sending
     * any Neon packets when DTLS is enabled. Blocks until the handshake completes
     * or times out.
     *
     * @param ctx       DTLS SSLContext initialised with a TrustManager (or
     *                  KeyManager for mutual auth)
     * @param peer      the relay address to handshake with
     * @param timeoutMs maximum time to wait for the handshake to complete
     * @throws IOException if the handshake fails or times out
     */
    public void performClientHandshake(SSLContext ctx, SocketAddress peer, int timeoutMs) throws IOException {
        DtlsSession session = DtlsSession.forClient(ctx, peer);
        dtlsSessions.put(peer, session);

        List<byte[]> initial = session.initiateHandshake();
        for (byte[] msg : initial)
            channel.send(ByteBuffer.wrap(msg), peer);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!session.isReady()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
                throw new SocketTimeoutException("DTLS handshake timed out");

            try (Selector sel = Selector.open()) {
                channel.register(sel, SelectionKey.OP_READ);
                if (sel.select(remaining) == 0)
                    throw new SocketTimeoutException("DTLS handshake timed out");

                ByteBuffer buf = ByteBuffer.allocate(bufferSize);
                SocketAddress src = channel.receive(buf);
                if (src == null || !src.equals(peer))
                    continue;
                buf.flip();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);

                List<byte[]> responses = new ArrayList<>();
                session.receive(bytes, responses);
                for (byte[] resp : responses)
                    channel.send(ByteBuffer.wrap(resp), peer);
            }
        }
    }

    /**
     * Enables server-side DTLS on this socket. The relay calls this so that inbound
     * DTLS
     * ClientHello records automatically trigger handshakes for new peers.
     *
     * @param ctx DTLS SSLContext initialised with a KeyManager (relay certificate +
     *            key)
     */
    public void enableServerDtls(SSLContext ctx) {
        this.serverDtlsContext = ctx;
    }

    /**
     * Removes the DTLS session for {@code address}. Call when a peer disconnects.
     */
    public void removeDtlsSession(SocketAddress address) {
        dtlsSessions.remove(address);
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws IOException {
        DtlsSession session = dtlsSessions.get(address);
        if (session != null && session.isReady()) {
            channel.send(ByteBuffer.wrap(session.wrap(data)), address);
            return;
        }
        channel.send(ByteBuffer.wrap(data), address);
    }

    @Override
    public Transport.ReceivedData receive() throws IOException {
        long deadline = (blocking && timeoutMs > 0) ? System.currentTimeMillis() + timeoutMs : 0;

        while (true) {
            if (blocking) {
                long remaining = (deadline > 0) ? deadline - System.currentTimeMillis() : 0;
                if (deadline > 0 && remaining <= 0)
                    throw new SocketTimeoutException("Receive timed out");
                try (Selector selector = Selector.open()) {
                    channel.register(selector, SelectionKey.OP_READ);
                    int ready = (deadline > 0) ? selector.select(remaining) : selector.select();
                    if (ready == 0)
                        throw new SocketTimeoutException("Receive timed out");
                }
            }

            ByteBuffer buf = bufferPool.acquire();
            try {
                SocketAddress source = channel.receive(buf);
                if (source == null)
                    return null;
                buf.flip();
                int received = buf.remaining();
                if (enforceBufferSize && received == bufferSize)
                    return null;
                if (received < 1)
                    return null;

                byte[] bytes = new byte[received];
                buf.get(bytes);

                bytes = applyDtls(bytes, source);
                if (bytes == null) {
                    if (blocking)
                        continue;
                    return null;
                }

                if (bytes.length < PacketHeader.HEADER_SIZE)
                    return null;
                return new Transport.ReceivedData(bytes, source);
            } finally {
                buf.clear();
                bufferPool.release(buf);
            }
        }
    }

    private byte[] applyDtls(byte[] bytes, SocketAddress source) throws IOException {
        int first = bytes[0] & 0xFF;
        boolean isDtls12 = first >= 0x14 && first <= 0x17;
        boolean isDtlsRecord = isDtls12 || (first >= 0x20 && first <= 0x3F);
        DtlsSession session = dtlsSessions.get(source);

        if (session != null) {
            if (!isDtlsRecord)
                return null;
            try {
                List<byte[]> outbound = new ArrayList<>();
                byte[] plaintext = session.receive(bytes, outbound);
                for (byte[] resp : outbound)
                    channel.send(ByteBuffer.wrap(resp), source);
                if (!session.isReady() || plaintext == null)
                    return null;
                return plaintext;
            } catch (SSLException e) {
                logger.warning(() -> "DTLS session error from " + source + ": " + e.getMessage());
                dtlsSessions.remove(source);
                return null;
            }
        }

        if (isDtls12 && serverDtlsContext != null) {
            DtlsSession newSession;
            try {
                newSession = DtlsSession.forServer(serverDtlsContext);
            } catch (SSLException e) {
                logger.warning(() -> "Failed to init DTLS server session for " + source + ": " + e.getMessage());
                return null;
            }
            dtlsSessions.put(source, newSession);
            try {
                List<byte[]> outbound = new ArrayList<>();
                newSession.receive(bytes, outbound);
                for (byte[] resp : outbound)
                    channel.send(ByteBuffer.wrap(resp), source);
            } catch (SSLException e) {
                logger.warning(() -> "DTLS handshake error from " + source + ": " + e.getMessage());
                dtlsSessions.remove(source);
            }
            return null;
        }

        if (isDtlsRecord)
            return null;

        return bytes;
    }

    public ReceivedNeonPacket receivePacket() throws IOException {
        Transport.ReceivedData data = receive();
        if (data == null)
            return null;
        try {
            return new ReceivedNeonPacket(NeonPacket.fromBytes(data.data()), data.source());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void sendPacket(NeonPacket packet, SocketAddress address) throws IOException {
        send(packet.toBytes(), address);
    }

    SelectionKey registerWithSelector(Selector selector) throws IOException {
        return channel.register(selector, SelectionKey.OP_READ);
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }

    public void setTimeout(int ms) {
        this.timeoutMs = ms;
    }

    @Override
    public InetSocketAddress getLocalAddress() throws IOException {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    public boolean isClosed() {
        return !channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public record ReceivedNeonPacket(NeonPacket packet, SocketAddress source) {
    }
}
