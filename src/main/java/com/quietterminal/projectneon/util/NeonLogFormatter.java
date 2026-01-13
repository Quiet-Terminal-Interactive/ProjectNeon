package com.quietterminal.projectneon.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Custom log formatter for Project Neon that formats log messages as:
 * [HH-MM-ss LEVEL]: Message
 */
public class NeonLogFormatter extends Formatter {
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH-mm-ss");

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();

        sb.append('[');
        sb.append(dateFormat.format(new Date(record.getMillis())));
        sb.append(' ');
        sb.append(record.getLevel().getName());
        sb.append("]: ");
        sb.append(formatMessage(record));
        sb.append('\n');

        if (record.getThrown() != null) {
            sb.append("Exception: ");
            sb.append(record.getThrown().toString());
            sb.append('\n');
            for (StackTraceElement element : record.getThrown().getStackTrace()) {
                sb.append("    at ");
                sb.append(element.toString());
                sb.append('\n');
            }
        }

        return sb.toString();
    }
}
