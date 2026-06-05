package com.quietterminal.neon.util;

import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/** JUL log formatter that emits compact {@code [timestamp level logger] message} lines. */
public final class NeonLogFormatter extends Formatter {

    @Override
    public String format(LogRecord record) {
        return String.format("[%s] [%s] [%s] %s%n",
                Instant.ofEpochMilli(record.getMillis()),
                record.getLevel(),
                record.getLoggerName(),
                formatMessage(record));
    }
}
