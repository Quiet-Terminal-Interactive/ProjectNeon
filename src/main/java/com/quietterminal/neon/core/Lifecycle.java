package com.quietterminal.neon.core;

/**
 * Lifecycle contract for Neon components that have a distinct startup and shutdown sequence.
 *
 * <p>State transitions: {@code CREATED → STARTING → RUNNING} on successful start;
 * {@code RUNNING → STOPPING → STOPPED} on successful stop. Any exception during a transition
 * moves the component to {@code FAILED}.
 */
public interface Lifecycle {

    /**
     * Lifecycle states for a Neon component.
     */
    enum State {
        /** Initial state after construction. */
        CREATED,
        /** Transitioning from {@code CREATED} to {@code RUNNING}. */
        STARTING,
        /** Fully started and processing. */
        RUNNING,
        /** Transitioning from {@code RUNNING} to {@code STOPPED}. */
        STOPPING,
        /** Cleanly shut down. */
        STOPPED,
        /** An unrecoverable error occurred during a state transition. */
        FAILED
    }

    /** Starts the component. Only valid from {@link State#CREATED}. */
    void start() throws Exception;

    /** Stops the component. Only valid from {@link State#RUNNING}. */
    void stop() throws Exception;

    /** Returns the current lifecycle state. */
    State getState();

    /** Returns {@code true} if the component is in the {@link State#RUNNING} state. */
    boolean isRunning();
}
