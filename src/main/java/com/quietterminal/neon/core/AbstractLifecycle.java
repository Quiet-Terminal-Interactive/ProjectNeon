package com.quietterminal.neon.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe base implementation of {@link Lifecycle} using compare-and-set state transitions.
 * Subclasses implement {@link #doStart()} and {@link #doStop()}; this class handles the
 * state machine and exception handling.
 */
public abstract class AbstractLifecycle implements Lifecycle {

    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    protected abstract void doStart() throws Exception;

    protected abstract void doStop() throws Exception;

    @Override
    public final void start() throws Exception {
        if (!state.compareAndSet(State.CREATED, State.STARTING)) {
            throw new IllegalStateException("Cannot start from state: " + state.get());
        }
        try {
            doStart();
            state.set(State.RUNNING);
        } catch (Exception e) {
            state.set(State.FAILED);
            throw e;
        }
    }

    @Override
    public final void stop() throws Exception {
        if (!state.compareAndSet(State.RUNNING, State.STOPPING)) {
            throw new IllegalStateException("Cannot stop from state: " + state.get());
        }
        try {
            doStop();
            state.set(State.STOPPED);
        } catch (Exception e) {
            state.set(State.FAILED);
            throw e;
        }
    }

    @Override
    public final State getState() {
        return state.get();
    }

    @Override
    public final boolean isRunning() {
        return state.get() == State.RUNNING;
    }
}
