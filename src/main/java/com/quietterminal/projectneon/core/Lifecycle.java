package com.quietterminal.projectneon.core;

/**
 * Defines the lifecycle states and transitions for Neon components.
 * Components implementing this interface follow a consistent start/stop pattern.
 *
 * <p>State transitions:
 * <pre>
 * CREATED → STARTING → RUNNING → STOPPING → STOPPED
 *                ↓          ↓
 *             FAILED     FAILED
 * </pre>
 *
 * @since 1.1
 */
public interface Lifecycle {

    /**
     * Lifecycle states for Neon components.
     */
    enum State {
        CREATED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return the current state
     */
    State getState();

    /**
     * Starts the component asynchronously.
     * Transitions from CREATED to STARTING, then to RUNNING on success.
     *
     * @throws IllegalStateException if not in CREATED or STOPPED state
     */
    void start();

    /**
     * Stops the component gracefully.
     * Transitions from RUNNING to STOPPING, then to STOPPED.
     * Waits for pending operations to complete up to the configured timeout.
     */
    void stop();

    /**
     * Checks if the component is currently running.
     *
     * @return true if in RUNNING state
     */
    default boolean isRunning() {
        return getState() == State.RUNNING;
    }

    /**
     * Checks if the component has been started (running or stopping).
     *
     * @return true if started
     */
    default boolean isStarted() {
        State state = getState();
        return state == State.RUNNING || state == State.STOPPING;
    }

    /**
     * Checks if the component has stopped or failed.
     *
     * @return true if stopped or failed
     */
    default boolean isTerminated() {
        State state = getState();
        return state == State.STOPPED || state == State.FAILED;
    }

    /**
     * Listener for lifecycle state changes.
     */
    @FunctionalInterface
    interface StateChangeListener {
        /**
         * Called when the lifecycle state changes.
         *
         * @param oldState the previous state
         * @param newState the new state
         * @param cause optional exception if transitioning to FAILED (may be null)
         */
        void onStateChange(State oldState, State newState, Throwable cause);
    }

    /**
     * Adds a listener for state changes.
     *
     * @param listener the listener to add
     */
    void addStateChangeListener(StateChangeListener listener);

    /**
     * Removes a state change listener.
     *
     * @param listener the listener to remove
     */
    void removeStateChangeListener(StateChangeListener listener);
}
