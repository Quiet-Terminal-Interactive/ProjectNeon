package com.quietterminal.neon.util;

import java.util.concurrent.Semaphore;

/** Semaphore-based backpressure guard for limiting concurrent in-flight operations. */
public final class Backpressure {

    private final Semaphore semaphore;

    public Backpressure(int maxConcurrent) {
        this.semaphore = new Semaphore(maxConcurrent);
    }

    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public boolean isAtCapacity() {
        return semaphore.availablePermits() == 0;
    }
}
