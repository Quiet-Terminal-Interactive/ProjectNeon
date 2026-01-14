package com.quietterminal.projectneon.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe object pool for byte arrays to reduce GC pressure.
 * Reuses byte buffers across packet operations instead of allocating new arrays.
 */
public class ByteBufferPool {
    private final Queue<byte[]> pool;
    private final int bufferSize;
    private final int maxPoolSize;

    /**
     * Creates a buffer pool with specified parameters.
     *
     * @param bufferSize size of each buffer in bytes
     * @param initialPoolSize number of buffers to pre-allocate
     * @param maxPoolSize maximum number of buffers to retain in pool
     */
    public ByteBufferPool(int bufferSize, int initialPoolSize, int maxPoolSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        if (initialPoolSize < 0) {
            throw new IllegalArgumentException("initialPoolSize must be non-negative");
        }
        if (maxPoolSize < initialPoolSize) {
            throw new IllegalArgumentException("maxPoolSize must be >= initialPoolSize");
        }

        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
        this.pool = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < initialPoolSize; i++) {
            pool.offer(new byte[bufferSize]);
        }
    }

    /**
     * Acquires a buffer from the pool. If pool is empty, allocates a new buffer.
     *
     * @return a byte array of the configured buffer size
     */
    public byte[] acquire() {
        byte[] buffer = pool.poll();
        if (buffer == null) {
            buffer = new byte[bufferSize];
        }
        return buffer;
    }

    /**
     * Returns a buffer to the pool for reuse. Only buffers of the correct size are accepted.
     *
     * @param buffer the buffer to return
     */
    public void release(byte[] buffer) {
        if (buffer == null || buffer.length != bufferSize) {
            return;
        }

        if (pool.size() < maxPoolSize) {
            pool.offer(buffer);
        }
    }

    /**
     * Gets the current number of available buffers in the pool.
     *
     * @return number of buffers ready for reuse
     */
    public int availableBuffers() {
        return pool.size();
    }

    /**
     * Gets the configured buffer size.
     *
     * @return buffer size in bytes
     */
    public int getBufferSize() {
        return bufferSize;
    }
}
