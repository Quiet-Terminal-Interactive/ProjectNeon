package com.quietterminal.neon.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Configures JUL logging for Neon components with a sensible console format and level. */
public final class LoggerConfig {

    private LoggerConfig() {
    }

    public static void configure(Level level) {
        Logger root = Logger.getLogger("com.quietterminal.neon");
        root.setLevel(level);
        root.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new NeonLogFormatter());
        handler.setLevel(level);
        root.addHandler(handler);
    }

    public static Logger getLogger(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }
}
