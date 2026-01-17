package com.quietterminal.projectneon.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class providing lifecycle management for Neon components.
 * Handles state transitions, listener notification, and thread safety.
 *
 * @since 1.1
 */
public abstract class AbstractLifecycle implements Lifecycle {

    private static final Logger logger = Logger.getLogger(AbstractLifecycle.class.getName());

    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private final List<StateChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final String componentName;

    /**
     * Creates a new lifecycle manager for the given component.
     *
     * @param componentName the name of the component for logging
     */
    protected AbstractLifecycle(String componentName) {
        this.componentName = componentName;
    }

    @Override
    public State getState() {
        return state.get();
    }

    @Override
    public void start() {
        State current = state.get();
        if (current != State.CREATED && current != State.STOPPED) {
            throw new IllegalStateException(
                componentName + " cannot start from state " + current +
                " (expected CREATED or STOPPED)");
        }

        if (!state.compareAndSet(current, State.STARTING)) {
            throw new IllegalStateException(componentName + " state changed during start");
        }
        notifyListeners(current, State.STARTING, null);

        try {
            doStart();
            State prev = state.getAndSet(State.RUNNING);
            notifyListeners(prev, State.RUNNING, null);
            logger.log(Level.INFO, "{0} started successfully", componentName);
        } catch (Exception e) {
            State prev = state.getAndSet(State.FAILED);
            notifyListeners(prev, State.FAILED, e);
            logger.log(Level.SEVERE, componentName + " failed to start", e);
            throw new RuntimeException(componentName + " failed to start", e);
        }
    }

    @Override
    public void stop() {
        State current = state.get();
        if (current != State.RUNNING && current != State.STARTING) {
            logger.log(Level.FINE, "{0} stop called but state is {1}",
                new Object[]{componentName, current});
            return;
        }

        if (!state.compareAndSet(current, State.STOPPING)) {
            return;
        }
        notifyListeners(current, State.STOPPING, null);

        try {
            doStop();
            State prev = state.getAndSet(State.STOPPED);
            notifyListeners(prev, State.STOPPED, null);
            logger.log(Level.INFO, "{0} stopped successfully", componentName);
        } catch (Exception e) {
            State prev = state.getAndSet(State.FAILED);
            notifyListeners(prev, State.FAILED, e);
            logger.log(Level.WARNING, componentName + " error during stop", e);
        }
    }

    @Override
    public void addStateChangeListener(StateChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeStateChangeListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Performs the actual start logic. Called after state transitions to STARTING.
     *
     * @throws Exception if start fails
     */
    protected abstract void doStart() throws Exception;

    /**
     * Performs the actual stop logic. Called after state transitions to STOPPING.
     *
     * @throws Exception if stop encounters errors
     */
    protected abstract void doStop() throws Exception;

    /**
     * Transitions to FAILED state with the given cause.
     *
     * @param cause the failure cause
     */
    protected void fail(Throwable cause) {
        State prev = state.getAndSet(State.FAILED);
        notifyListeners(prev, State.FAILED, cause);
        logger.log(Level.SEVERE, componentName + " failed", cause);
    }

    /**
     * Checks if the component is in RUNNING state.
     *
     * @throws IllegalStateException if not running
     */
    protected void checkRunning() {
        if (state.get() != State.RUNNING) {
            throw new IllegalStateException(componentName + " is not running");
        }
    }

    private void notifyListeners(State oldState, State newState, Throwable cause) {
        for (StateChangeListener listener : listeners) {
            try {
                listener.onStateChange(oldState, newState, cause);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                    "Listener threw exception on state change " + oldState + " -> " + newState, e);
            }
        }
    }

    /**
     * Returns the component name for logging.
     *
     * @return the component name
     */
    protected String getComponentName() {
        return componentName;
    }
}
