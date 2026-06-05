package com.quietterminal.neon.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Thin wrapper around JUL {@link java.util.logging.Logger} that adds key=value structured fields to log messages. */
public final class StructuredLogger {

    private final Logger logger;

    public StructuredLogger(Class<?> clazz) {
        this.logger = Logger.getLogger(clazz.getName());
    }

    public void info(String event, Object... keyValues) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info(format(event, keyValues));
        }
    }

    public void warn(String event, Object... keyValues) {
        if (logger.isLoggable(Level.WARNING)) {
            logger.warning(format(event, keyValues));
        }
    }

    public void debug(String event, Object... keyValues) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(format(event, keyValues));
        }
    }

    public void error(String event, Throwable cause, Object... keyValues) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.log(Level.SEVERE, format(event, keyValues), cause);
        }
    }

    private static String format(String event, Object[] keyValues) {
        if (keyValues.length == 0)
            return event;
        StringBuilder sb = new StringBuilder(event);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            sb.append(' ').append(keyValues[i]).append('=').append(keyValues[i + 1]);
        }
        return sb.toString();
    }
}
