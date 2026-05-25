package com.quietterminal.neon.core;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

public final class ByteBufferPool {

    private final int bufferSize;
    private final ArrayBlockingQueue<ByteBuffer> pool;

    public ByteBufferPool(int bufferSize, int initSize, int maxSize) {
        this.bufferSize = bufferSize;
        this.pool = new ArrayBlockingQueue<>(maxSize);
        for (int i = 0; i < initSize; i++) {
            pool.offer(ByteBuffer.allocate(bufferSize));
        }
    }

    public ByteBuffer acquire() {
        ByteBuffer buf = pool.poll();
        if (buf == null)
            return ByteBuffer.allocate(bufferSize);
        buf.clear();
        return buf;
    }

    public void release(ByteBuffer buf) {
        if (buf != null)
            pool.offer(buf);
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int poolSize() {
        return pool.size();
    }
}
