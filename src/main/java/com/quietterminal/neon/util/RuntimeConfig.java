package com.quietterminal.neon.util;

/** Reads typed runtime configuration from Java system properties with safe defaults. */
public final class RuntimeConfig {

    private RuntimeConfig() {
    }

    public static int getInt(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null)
            return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null)
            return defaultValue;
        return Boolean.parseBoolean(value);
    }

    public static String getString(String key, String defaultValue) {
        return System.getProperty(key, defaultValue);
    }
}
