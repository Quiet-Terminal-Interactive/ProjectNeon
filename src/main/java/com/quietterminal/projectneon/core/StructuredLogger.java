package com.quietterminal.projectneon.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Structured logging with JSON output for Neon components.
 * Provides context-aware logging with key-value pairs.
 *
 * <p>Example output:
 * <pre>
 * {"timestamp":"2026-01-17T12:34:56.789Z","level":"INFO","logger":"NeonRelay",
 *  "message":"Client connected","sessionId":12345,"clientId":2,"name":"Player1"}
 * </pre>
 *
 * @since 1.1
 */
public final class StructuredLogger {

    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    private static final Map<String, StructuredLogger> LOGGERS = new ConcurrentHashMap<>();
    private static volatile Consumer<String> globalOutput = System.out::println;
    private static volatile boolean jsonEnabled = false;

    private final String name;
    private final Logger delegate;
    private final Map<String, Object> context = new ConcurrentHashMap<>();

    private StructuredLogger(String name) {
        this.name = name;
        this.delegate = Logger.getLogger(name);
    }

    /**
     * Gets or creates a structured logger for the given name.
     *
     * @param name the logger name
     * @return the structured logger
     */
    public static StructuredLogger getLogger(String name) {
        return LOGGERS.computeIfAbsent(name, StructuredLogger::new);
    }

    /**
     * Gets or creates a structured logger for the given class.
     *
     * @param clazz the class
     * @return the structured logger
     */
    public static StructuredLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    /**
     * Enables or disables JSON output globally.
     *
     * @param enabled true to output JSON, false for plain text
     */
    public static void setJsonEnabled(boolean enabled) {
        jsonEnabled = enabled;
    }

    /**
     * Checks if JSON output is enabled.
     *
     * @return true if JSON output is enabled
     */
    public static boolean isJsonEnabled() {
        return jsonEnabled;
    }

    /**
     * Sets the global output consumer for all structured loggers.
     *
     * @param output the output consumer
     */
    public static void setOutput(Consumer<String> output) {
        globalOutput = output != null ? output : System.out::println;
    }

    /**
     * Installs a JUL handler that routes logs through the structured logger.
     *
     * @param logger the JUL logger to wrap
     */
    public static void installHandler(Logger logger) {
        logger.addHandler(new StructuredHandler());
    }

    /**
     * Adds persistent context that will be included in all log messages.
     *
     * @param key the context key
     * @param value the context value
     * @return this logger for chaining
     */
    public StructuredLogger withContext(String key, Object value) {
        if (key != null && value != null) {
            context.put(key, value);
        }
        return this;
    }

    /**
     * Removes a context key.
     *
     * @param key the context key to remove
     * @return this logger for chaining
     */
    public StructuredLogger removeContext(String key) {
        context.remove(key);
        return this;
    }

    /**
     * Clears all context.
     *
     * @return this logger for chaining
     */
    public StructuredLogger clearContext() {
        context.clear();
        return this;
    }

    /**
     * Creates a log entry builder for INFO level.
     *
     * @param message the log message
     * @return the log entry builder
     */
    public LogEntry info(String message) {
        return new LogEntry(Level.INFO, message);
    }

    /**
     * Creates a log entry builder for WARNING level.
     *
     * @param message the log message
     * @return the log entry builder
     */
    public LogEntry warn(String message) {
        return new LogEntry(Level.WARNING, message);
    }

    /**
     * Creates a log entry builder for SEVERE level.
     *
     * @param message the log message
     * @return the log entry builder
     */
    public LogEntry error(String message) {
        return new LogEntry(Level.SEVERE, message);
    }

    /**
     * Creates a log entry builder for FINE level.
     *
     * @param message the log message
     * @return the log entry builder
     */
    public LogEntry debug(String message) {
        return new LogEntry(Level.FINE, message);
    }

    /**
     * Creates a log entry builder for FINER level.
     *
     * @param message the log message
     * @return the log entry builder
     */
    public LogEntry trace(String message) {
        return new LogEntry(Level.FINER, message);
    }

    /**
     * Builder for structured log entries.
     */
    public class LogEntry {
        private final Level level;
        private final String message;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private Throwable throwable;

        LogEntry(Level level, String message) {
            this.level = level;
            this.message = message;
        }

        /**
         * Adds a field to the log entry.
         *
         * @param key the field key
         * @param value the field value
         * @return this entry for chaining
         */
        public LogEntry with(String key, Object value) {
            if (key != null && value != null) {
                fields.put(key, value);
            }
            return this;
        }

        /**
         * Adds an exception to the log entry.
         *
         * @param t the exception
         * @return this entry for chaining
         */
        public LogEntry withException(Throwable t) {
            this.throwable = t;
            return this;
        }

        /**
         * Logs the entry.
         */
        public void log() {
            if (!delegate.isLoggable(level)) {
                return;
            }

            if (jsonEnabled) {
                globalOutput.accept(toJson());
            } else {
                globalOutput.accept(toPlainText());
            }
        }

        private String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            appendJsonField(sb, "timestamp", ISO_FORMATTER.format(Instant.now()), true);
            appendJsonField(sb, "level", level.getName(), true);
            appendJsonField(sb, "logger", name, true);
            appendJsonField(sb, "message", message, true);

            for (Map.Entry<String, Object> entry : context.entrySet()) {
                appendJsonField(sb, entry.getKey(), entry.getValue(), false);
            }

            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                appendJsonField(sb, entry.getKey(), entry.getValue(), false);
            }

            if (throwable != null) {
                appendJsonField(sb, "error", throwable.getMessage(), true);
                appendJsonField(sb, "errorType", throwable.getClass().getName(), true);
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                appendJsonField(sb, "stackTrace", sw.toString(), true);
            }

            sb.append("}");
            return sb.toString();
        }

        private void appendJsonField(StringBuilder sb, String key, Object value, boolean isString) {
            if (sb.charAt(sb.length() - 1) != '{') {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(key)).append("\":");
            if (isString || value instanceof String) {
                sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
            }
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < ' ') {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.toString();
        }

        private String toPlainText() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(level.getName()).append("] ");
            sb.append(name).append(": ");
            sb.append(message);

            if (!context.isEmpty() || !fields.isEmpty()) {
                sb.append(" {");
                boolean first = true;
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
                sb.append("}");
            }

            if (throwable != null) {
                sb.append(" - ").append(throwable.getClass().getSimpleName());
                sb.append(": ").append(throwable.getMessage());
            }

            return sb.toString();
        }
    }

    /**
     * JUL handler that routes to structured logging.
     */
    private static class StructuredHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            if (record == null) return;
            StructuredLogger logger = getLogger(record.getLoggerName());
            LogEntry entry = logger.new LogEntry(record.getLevel(), record.getMessage());
            if (record.getThrown() != null) {
                entry.withException(record.getThrown());
            }
            entry.log();
        }

        @Override
        public void flush() {
            /* No buffering */
        }

        @Override
        public void close() throws SecurityException {
            /* No resources to close */
        }
    }
}
