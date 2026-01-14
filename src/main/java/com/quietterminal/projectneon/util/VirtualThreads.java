package com.quietterminal.projectneon.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Utility for creating virtual threads (Java 21+) with graceful fallback to platform threads.
 */
public class VirtualThreads {
    private static final boolean VIRTUAL_THREADS_AVAILABLE;
    @SuppressWarnings("unused")
    private static final ThreadFactory THREAD_FACTORY;

    static {
        boolean available = false;
        ThreadFactory factory = null;

        try {
            factory = (ThreadFactory) Executors.class.getMethod("newVirtualThreadPerTaskExecutor")
                .invoke(null);
            available = true;
        } catch (Exception e) {
        }

        VIRTUAL_THREADS_AVAILABLE = available;
        THREAD_FACTORY = factory != null ? factory : Thread.ofPlatform().factory();
    }

    /**
     * Checks if virtual threads are available in the current JVM.
     *
     * @return true if Java 21+ virtual threads are supported
     */
    public static boolean areVirtualThreadsAvailable() {
        return VIRTUAL_THREADS_AVAILABLE;
    }

    /**
     * Creates a new thread using virtual threads if available, otherwise platform threads.
     *
     * @param runnable the task to run
     * @return a new thread
     */
    public static Thread newThread(Runnable runnable) {
        if (VIRTUAL_THREADS_AVAILABLE) {
            try {
                return (Thread) Thread.class.getMethod("ofVirtual")
                    .invoke(null, (Object[]) null)
                    .getClass().getMethod("unstarted", Runnable.class)
                    .invoke(null, runnable);
            } catch (Exception e) {
            }
        }

        return new Thread(runnable);
    }

    /**
     * Starts a new thread using virtual threads if available.
     *
     * @param runnable the task to run
     * @return the started thread
     */
    public static Thread startVirtualThread(Runnable runnable) {
        if (VIRTUAL_THREADS_AVAILABLE) {
            try {
                return (Thread) Thread.class.getMethod("startVirtualThread", Runnable.class)
                    .invoke(null, runnable);
            } catch (Exception e) {
            }
        }

        Thread thread = new Thread(runnable);
        thread.start();
        return thread;
    }
}
