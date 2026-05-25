package com.quietterminal.neon.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * DTLS session wrapping a single {@link SSLEngine} peer connection.
 *
 * <p>
 * Thread-safe for concurrent {@link #receive} / {@link #wrap} calls after the
 * handshake
 * completes, but the handshake itself must be driven single-threaded.
 */
final class DtlsSession {

    private final SSLEngine engine;
    private volatile boolean ready = false;

    static DtlsSession forClient(SSLContext ctx, SocketAddress peer) {
        InetSocketAddress addr = (InetSocketAddress) peer;
        SSLEngine e = ctx.createSSLEngine(addr.getHostString(), addr.getPort());
        e.setUseClientMode(true);
        return new DtlsSession(e);
    }

    static DtlsSession forServer(SSLContext ctx) throws SSLException {
        SSLEngine e = ctx.createSSLEngine();
        e.setUseClientMode(false);
        e.beginHandshake();
        return new DtlsSession(e);
    }

    private DtlsSession(SSLEngine engine) {
        this.engine = engine;
    }

    boolean isReady() {
        return ready;
    }

    /**
     * Client-side: generate the ClientHello and any subsequent initial handshake
     * messages.
     *
     * @return list of DTLS records to send to the server
     */
    List<byte[]> initiateHandshake() throws SSLException {
        engine.beginHandshake();
        List<byte[]> outbound = new ArrayList<>();
        drainWrap(engine.getHandshakeStatus(), outbound);
        return outbound;
    }

    /**
     * Process an incoming DTLS record.
     *
     * @param record   raw DTLS record bytes from the network
     * @param outbound receives any DTLS records that must be sent back to the peer
     *                 (handshake responses)
     * @return decrypted application plaintext, or {@code null} if this record was
     *         handshake traffic
     */
    byte[] receive(byte[] record, List<byte[]> outbound) throws SSLException {
        ByteBuffer src = ByteBuffer.wrap(record);
        ByteBuffer dst = ByteBuffer.allocate(maxAppBufferSize());

        SSLEngineResult result = engine.unwrap(src, dst);
        drainWrap(result.getHandshakeStatus(), outbound);

        if (result.bytesProduced() > 0) {
            dst.flip();
            byte[] plaintext = new byte[dst.remaining()];
            dst.get(plaintext);
            return plaintext;
        }
        return null;
    }

    /**
     * Encrypt application data for sending to the peer.
     *
     * @param plaintext application bytes to protect
     * @return DTLS application-data record ready to send
     */
    byte[] wrap(byte[] plaintext) throws SSLException {
        ByteBuffer src = ByteBuffer.wrap(plaintext);
        ByteBuffer dst = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        SSLEngineResult result = engine.wrap(src, dst);
        if (result.getStatus() != SSLEngineResult.Status.OK)
            throw new SSLException("DTLS wrap failed: " + result.getStatus());
        dst.flip();
        byte[] out = new byte[dst.remaining()];
        dst.get(out);
        return out;
    }

    private void drainWrap(HandshakeStatus status, List<byte[]> outbound) throws SSLException {
        while (true) {
            switch (status) {
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null)
                        task.run();
                    status = engine.getHandshakeStatus();
                }
                case NEED_WRAP -> {
                    ByteBuffer out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                    SSLEngineResult wr = engine.wrap(ByteBuffer.allocate(0), out);
                    out.flip();
                    if (out.hasRemaining()) {
                        byte[] msg = new byte[out.remaining()];
                        out.get(msg);
                        outbound.add(msg);
                    }
                    status = wr.getHandshakeStatus();
                }
                case NEED_UNWRAP_AGAIN -> {
                    ByteBuffer tmp = ByteBuffer.allocate(maxAppBufferSize());
                    SSLEngineResult r = engine.unwrap(ByteBuffer.allocate(0), tmp);
                    status = r.getHandshakeStatus();
                }
                case FINISHED, NOT_HANDSHAKING -> {
                    ready = true;
                    return;
                }
                default -> {
                    return;
                }
            }
        }
    }

    private int maxAppBufferSize() {
        int size = engine.getSession().getApplicationBufferSize();
        return Math.max(size, 65535);
    }
}
