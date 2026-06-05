package com.quietterminal.neon.core;

/**
 * A functional interface for consuming three arguments, analogous to {@link java.util.function.BiConsumer}.
 *
 * @param <A> type of the first argument
 * @param <B> type of the second argument
 * @param <C> type of the third argument
 */
@FunctionalInterface
public interface TriConsumer<A, B, C> {
    /**
     * Performs this operation on the given arguments.
     *
     * @param a first argument
     * @param b second argument
     * @param c third argument
     */
    void accept(A a, B b, C c);
}
