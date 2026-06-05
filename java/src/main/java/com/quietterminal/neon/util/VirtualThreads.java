package com.quietterminal.neon.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Factory helpers for creating virtual-thread executors and named threads. */
public final class VirtualThreads {

    private VirtualThreads() {
    }

    public static Thread start(Runnable task) {
        return Thread.ofVirtual().start(task);
    }

    public static Thread startNamed(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(task);
    }

    public static ExecutorService newExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
