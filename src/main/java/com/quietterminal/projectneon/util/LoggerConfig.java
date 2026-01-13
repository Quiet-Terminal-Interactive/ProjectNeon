package com.quietterminal.projectneon.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class to configure loggers with custom formatting.
 */
public class LoggerConfig {

    /**
     * Configures a logger with the custom Neon log formatter.
     * @param logger The logger to configure
     */
    public static void configureLogger(Logger logger) {
        Logger rootLogger = Logger.getLogger("");
        java.util.logging.Handler[] handlers = rootLogger.getHandlers();
        for (java.util.logging.Handler handler : handlers) {
            rootLogger.removeHandler(handler);
        }

        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new NeonLogFormatter());
        handler.setLevel(Level.ALL);

        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
    }
}
