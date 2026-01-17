package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.util.LoggerConfig;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Event-driven packet receiver using NIO Selector.
 *
 * <p>This class provides an alternative to polling-based receive loops by using
 * Java NIO's Selector mechanism. The selector blocks until data is available,
 * eliminating the need for sleep-based polling and reducing CPU usage.
 *
 * <p>Benefits over polling:
 * <ul>
 *   <li>Near-zero CPU usage when idle</li>
 *   <li>Immediate response when packets arrive</li>
 *   <li>Configurable timeout for periodic tasks</li>
 *   <li>Clean shutdown support</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * EventDrivenReceiver receiver = new EventDrivenReceiver(channel, config);
 * receiver.setPacketHandler((packet, source) -> {
 *     // Handle received packet
 * });
 * receiver.setTimeoutHandler(() -> {
 *     // Periodic tasks (cleanup, keepalive, etc.)
 * });
 * receiver.run(); // Blocking event loop
 * }</pre>
 */
public class EventDrivenReceiver implements AutoCloseable {
    private static final Logger logger;

    static {
        logger = Logger.getLogger(EventDrivenReceiver.class.getName());
        LoggerConfig.configureLogger(logger);
    }

    private final DatagramChannel channel;
    private final Selector selector;
    private final ByteBuffer receiveBuffer;
    private final NeonConfig config;

    private volatile boolean running = true;
    private BiConsumer<NeonPacket, SocketAddress> packetHandler;
    private Runnable timeoutHandler;
    private int selectTimeoutMs;

    /**
     * Creates an event-driven receiver for the given channel.
     *
     * @param channel The datagram channel to receive from (must be non-blocking)
     * @param config The configuration to use
     * @throws IOException if selector creation fails
     */
    public EventDrivenReceiver(DatagramChannel channel, NeonConfig config) throws IOException {
        if (channel.isBlocking()) {
            throw new IllegalArgumentException("Channel must be in non-blocking mode");
        }
        this.channel = channel;
        this.config = config;
        this.selector = Selector.open();
        this.receiveBuffer = ByteBuffer.allocate(config.getBufferSize());
        this.selectTimeoutMs = 100;

        channel.register(selector, SelectionKey.OP_READ);
    }

    /**
     * Sets the handler for received packets.
     *
     * @param handler BiConsumer receiving the parsed packet and source address
     */
    public void setPacketHandler(BiConsumer<NeonPacket, SocketAddress> handler) {
        this.packetHandler = handler;
    }

    /**
     * Sets the handler called on select timeout.
     * Use this for periodic tasks like cleanup or keepalive.
     *
     * @param handler Runnable to execute on timeout
     */
    public void setTimeoutHandler(Runnable handler) {
        this.timeoutHandler = handler;
    }

    /**
     * Sets the select timeout in milliseconds.
     * The timeout handler is called each time select times out.
     *
     * @param timeoutMs Timeout in milliseconds (0 for no timeout)
     */
    public void setSelectTimeout(int timeoutMs) {
        this.selectTimeoutMs = timeoutMs;
    }

    /**
     * Runs the event loop. This method blocks until {@link #stop()} is called.
     *
     * @throws IOException if an I/O error occurs
     */
    public void run() throws IOException {
        logger.log(Level.INFO, "Event-driven receiver started");

        while (running) {
            int readyCount = selector.select(selectTimeoutMs);

            if (!running) {
                break;
            }

            if (readyCount == 0) {
                if (timeoutHandler != null) {
                    timeoutHandler.run();
                }
                continue;
            }

            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();

                if (key.isReadable()) {
                    processReadableKey();
                }
            }
        }

        logger.log(Level.INFO, "Event-driven receiver stopped");
    }

    private void processReadableKey() {
        try {
            while (true) {
                receiveBuffer.clear();
                SocketAddress source = channel.receive(receiveBuffer);

                if (source == null) {
                    break;
                }

                receiveBuffer.flip();
                int length = receiveBuffer.remaining();

                if (config.isEnforceBufferSize() && length == receiveBuffer.capacity()) {
                    logger.log(Level.WARNING,
                        "Packet from {0} filled entire buffer ({1} bytes) - possible truncation",
                        new Object[]{source, length});
                    continue;
                }

                if (length < PacketHeader.HEADER_SIZE) {
                    logger.log(Level.WARNING,
                        "Packet from {0} too small ({1} bytes)",
                        new Object[]{source, length});
                    continue;
                }

                byte[] data = new byte[length];
                receiveBuffer.get(data);

                try {
                    NeonPacket packet = NeonPacket.fromBytes(data);
                    if (packetHandler != null) {
                        packetHandler.accept(packet, source);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to parse packet from {0}: {1}",
                        new Object[]{source, e.getMessage()});
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error receiving packet: {0}", e.getMessage());
        }
    }

    /**
     * Stops the event loop. Safe to call from any thread.
     */
    public void stop() {
        running = false;
        selector.wakeup();
    }

    /**
     * Checks if the receiver is currently running.
     *
     * @return true if the event loop is active
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() throws IOException {
        stop();
        selector.close();
    }
}
