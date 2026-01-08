package com.quietterminal.projectneon.exceptions;

/**
 * Base exception class for all Project Neon exceptions.
 * Provides a common ancestor for exception handling.
 */
public class NeonException extends Exception {
    public NeonException(String message) {
        super(message);
    }

    public NeonException(String message, Throwable cause) {
        super(message, cause);
    }
}
