package com.quietterminal.projectneon.util;

import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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

        String message = formatMessageWithoutCommas(record);
        sb.append(message);
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

    /**
     * Formats the log message without locale-specific number formatting (no commas).
     */
    private String formatMessageWithoutCommas(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();

        if (params == null || params.length == 0) {
            return message;
        }

        MessageFormat formatter = new MessageFormat(message, Locale.ROOT);

        java.text.Format[] formats = formatter.getFormatsByArgumentIndex();
        for (int i = 0; i < formats.length; i++) {
            if (formats[i] instanceof NumberFormat) {
                NumberFormat nf = (NumberFormat) formats[i];
                nf.setGroupingUsed(false);
            }
        }

        return formatter.format(params);
    }
}
