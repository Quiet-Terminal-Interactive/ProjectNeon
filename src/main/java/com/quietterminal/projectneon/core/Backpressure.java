package com.quietterminal.projectneon.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backpressure signaling for flow control between Neon components.
 * Tracks queue depths, processing rates, and notifies listeners when thresholds are exceeded.
 *
 * <p>Usage:
 * <pre>
 * Backpressure bp = Backpressure.create()
 *     .setHighWaterMark(1000)
 *     .setLowWaterMark(100);
 *
 * bp.addListener(signal -&gt; {
 *     if (signal.shouldPause()) {
 *         pauseSending();
 *     } else {
 *         resumeSending();
 *     }
 * });
 *
 * bp.recordEnqueue();
 * bp.recordDequeue();
 * </pre>
 *
 * @since 1.1
 */
public final class Backpressure {

    /**
     * Backpressure signal indicating current pressure state.
     */
    public enum Signal {
        /**
         * Normal operation, no pressure.
         */
        NORMAL,

        /**
         * Approaching capacity, slow down if possible.
         */
        WARNING,

        /**
         * At or exceeding capacity, pause sending.
         */
        CRITICAL,

        /**
         * Recovered from critical state, resume sending.
         */
        RECOVERED
    }

    /**
     * Snapshot of backpressure state at a point in time.
     */
    public record State(
        int currentDepth,
        int highWaterMark,
        int lowWaterMark,
        long totalEnqueued,
        long totalDequeued,
        long totalDropped,
        Signal currentSignal,
        double enqueueRate,
        double dequeueRate
    ) {
        /**
         * Checks if senders should pause.
         *
         * @return true if senders should pause
         */
        public boolean shouldPause() {
            return currentSignal == Signal.CRITICAL;
        }

        /**
         * Checks if the queue is draining faster than filling.
         *
         * @return true if draining
         */
        public boolean isDraining() {
            return dequeueRate > enqueueRate;
        }

        /**
         * Returns the fill percentage (0.0 to 1.0+).
         *
         * @return the fill ratio relative to high water mark
         */
        public double fillRatio() {
            if (highWaterMark == 0) return 0.0;
            return (double) currentDepth / highWaterMark;
        }
    }

    /**
     * Listener for backpressure signals.
     */
    @FunctionalInterface
    public interface Listener {
        /**
         * Called when the backpressure signal changes.
         *
         * @param signal the new signal
         * @param state the current state
         */
        void onSignalChange(Signal signal, State state);
    }

    private final AtomicInteger depth = new AtomicInteger(0);
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalDequeued = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);

    private final AtomicLong enqueueCountWindow = new AtomicLong(0);
    private final AtomicLong dequeueCountWindow = new AtomicLong(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());

    private volatile int highWaterMark = 1000;
    private volatile int lowWaterMark = 100;
    private volatile int warningThreshold = 800;
    private volatile Signal currentSignal = Signal.NORMAL;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Backpressure() {
    }

    /**
     * Creates a new backpressure instance.
     *
     * @return the backpressure instance
     */
    public static Backpressure create() {
        return new Backpressure();
    }

    /**
     * Sets the high water mark (capacity limit).
     *
     * @param mark the high water mark
     * @return this instance for chaining
     */
    public Backpressure setHighWaterMark(int mark) {
        this.highWaterMark = mark;
        this.warningThreshold = (int) (mark * 0.8);
        return this;
    }

    /**
     * Sets the low water mark (recovery threshold).
     *
     * @param mark the low water mark
     * @return this instance for chaining
     */
    public Backpressure setLowWaterMark(int mark) {
        this.lowWaterMark = mark;
        return this;
    }

    /**
     * Sets the warning threshold.
     *
     * @param threshold the warning threshold
     * @return this instance for chaining
     */
    public Backpressure setWarningThreshold(int threshold) {
        this.warningThreshold = threshold;
        return this;
    }

    /**
     * Adds a backpressure listener.
     *
     * @param listener the listener
     * @return this instance for chaining
     */
    public Backpressure addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Records an enqueue operation.
     *
     * @return the new queue depth
     */
    public int recordEnqueue() {
        totalEnqueued.incrementAndGet();
        enqueueCountWindow.incrementAndGet();
        int newDepth = depth.incrementAndGet();
        checkSignal(newDepth);
        return newDepth;
    }

    /**
     * Records a dequeue operation.
     *
     * @return the new queue depth
     */
    public int recordDequeue() {
        totalDequeued.incrementAndGet();
        dequeueCountWindow.incrementAndGet();
        int newDepth = depth.decrementAndGet();
        if (newDepth < 0) {
            depth.set(0);
            newDepth = 0;
        }
        checkSignal(newDepth);
        return newDepth;
    }

    /**
     * Records a dropped item (rejected due to backpressure).
     */
    public void recordDrop() {
        totalDropped.incrementAndGet();
    }

    /**
     * Checks if senders should currently pause.
     *
     * @return true if senders should pause
     */
    public boolean shouldPause() {
        return currentSignal == Signal.CRITICAL;
    }

    /**
     * Returns the current signal.
     *
     * @return the current signal
     */
    public Signal getCurrentSignal() {
        return currentSignal;
    }

    /**
     * Returns the current queue depth.
     *
     * @return the current depth
     */
    public int getCurrentDepth() {
        return depth.get();
    }

    /**
     * Returns a snapshot of the current state.
     *
     * @return the state snapshot
     */
    public State getState() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        long elapsed = now - windowStart;
        double seconds = elapsed / 1000.0;

        double enqueueRate = 0;
        double dequeueRate = 0;

        if (seconds > 0) {
            enqueueRate = enqueueCountWindow.get() / seconds;
            dequeueRate = dequeueCountWindow.get() / seconds;
        }

        if (elapsed > 1000) {
            windowStartTime.set(now);
            enqueueCountWindow.set(0);
            dequeueCountWindow.set(0);
        }

        return new State(
            depth.get(),
            highWaterMark,
            lowWaterMark,
            totalEnqueued.get(),
            totalDequeued.get(),
            totalDropped.get(),
            currentSignal,
            enqueueRate,
            dequeueRate
        );
    }

    /**
     * Resets all counters.
     */
    public void reset() {
        depth.set(0);
        totalEnqueued.set(0);
        totalDequeued.set(0);
        totalDropped.set(0);
        enqueueCountWindow.set(0);
        dequeueCountWindow.set(0);
        windowStartTime.set(System.currentTimeMillis());
        currentSignal = Signal.NORMAL;
    }

    private void checkSignal(int currentDepth) {
        Signal oldSignal = currentSignal;
        Signal newSignal = calculateSignal(currentDepth, oldSignal);

        if (newSignal != oldSignal) {
            currentSignal = newSignal;
            notifyListeners(newSignal);
        }
    }

    private Signal calculateSignal(int currentDepth, Signal previous) {
        if (currentDepth >= highWaterMark) {
            return Signal.CRITICAL;
        }

        if (previous == Signal.CRITICAL) {
            if (currentDepth <= lowWaterMark) {
                return Signal.RECOVERED;
            }
            return Signal.CRITICAL;
        }

        if (currentDepth >= warningThreshold) {
            return Signal.WARNING;
        }

        if (previous == Signal.RECOVERED || previous == Signal.WARNING) {
            if (currentDepth <= lowWaterMark) {
                return Signal.NORMAL;
            }
            return previous;
        }

        return Signal.NORMAL;
    }

    private void notifyListeners(Signal signal) {
        State state = getState();
        for (Listener listener : listeners) {
            try {
                listener.onSignalChange(signal, state);
            } catch (Exception e) {
                /* Ignore listener exceptions */
            }
        }
    }
}
