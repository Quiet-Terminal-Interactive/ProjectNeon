package com.quietterminal.neon.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ByteBufferPoolTest {

    @Test
    void acquireReturnsBufferOfCorrectCapacity() {
        var pool = new ByteBufferPool(1024, 4, 8);
        assertEquals(1024, pool.acquire().capacity());
    }

    @Test
    void releaseAndReacquireReturnsSameInstance() {
        var pool = new ByteBufferPool(512, 1, 4);
        ByteBuffer buf = pool.acquire();
        pool.release(buf);
        assertSame(buf, pool.acquire());
    }

    @Test
    void acquireWhenEmptyAllocatesNewBuffer() {
        var pool = new ByteBufferPool(256, 0, 4);
        ByteBuffer buf = pool.acquire();
        assertNotNull(buf);
        assertEquals(256, buf.capacity());
    }

    @Test
    void acquiredBufferIsAlwaysCleared() {
        var pool = new ByteBufferPool(64, 1, 4);
        ByteBuffer buf = pool.acquire();
        buf.put((byte) 0xFF); // dirty the buffer
        pool.release(buf);
        ByteBuffer reacquired = pool.acquire();
        assertEquals(0, reacquired.position());
        assertEquals(64, reacquired.limit());
    }

    @Test
    void poolDoesNotExceedMaxSize() {
        var pool = new ByteBufferPool(128, 0, 2);
        ByteBuffer b1 = pool.acquire();
        ByteBuffer b2 = pool.acquire();
        ByteBuffer b3 = pool.acquire();
        pool.release(b1);
        pool.release(b2);
        pool.release(b3); // should be silently discarded
        assertEquals(2, pool.poolSize());
    }

    @Test
    void initSizePrePopulatesPool() {
        var pool = new ByteBufferPool(128, 4, 8);
        assertEquals(4, pool.poolSize());
    }

    @Test
    void getBufferSizeReturnsCorrectValue() {
        var pool = new ByteBufferPool(2048, 0, 4);
        assertEquals(2048, pool.getBufferSize());
    }

    @Test
    void releaseNullIsNoop() {
        var pool = new ByteBufferPool(128, 2, 4);
        assertDoesNotThrow(() -> pool.release(null));
        assertEquals(2, pool.poolSize());
    }
}
