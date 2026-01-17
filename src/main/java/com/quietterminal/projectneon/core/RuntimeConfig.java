package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime configuration system with hot-reload support.
 *
 * <p>Provides dynamic configuration that can be updated without restarting.
 * Supports change listeners for reactive configuration updates.
 *
 * <p>Thread-safe and designed for concurrent access.
 *
 * <p>Example usage:
 * <pre>{@code
 * RuntimeConfig config = RuntimeConfig.create();
 *
 * // Set values
 * config.setInt("relay.maxPacketsPerSecond", 200);
 * config.setString("relay.bindAddress", "0.0.0.0:7777");
 *
 * // Listen for changes
 * config.addListener("relay.maxPacketsPerSecond", (key, oldValue, newValue) -> {
 *     System.out.println("Rate limit changed: " + oldValue + " -> " + newValue);
 *     updateRateLimiter((Integer) newValue);
 * });
 *
 * // Load from file (supports hot-reload)
 * config.loadFromFile(Path.of("neon.properties"));
 *
 * // Apply to NeonConfig
 * NeonConfig neonConfig = config.toNeonConfig();
 * }</pre>
 *
 * @since 1.1
 */
@PublicAPI
public final class RuntimeConfig {

    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Object> defaults = new ConcurrentHashMap<>();
    private final List<ConfigChangeListener> globalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<ConfigChangeListener>> keyListeners = new ConcurrentHashMap<>();

    private RuntimeConfig() {
        initializeDefaults();
    }

    /**
     * Creates a new RuntimeConfig with default values.
     */
    public static RuntimeConfig create() {
        return new RuntimeConfig();
    }

    /**
     * Creates a RuntimeConfig initialized from a NeonConfig.
     */
    public static RuntimeConfig fromNeonConfig(NeonConfig config) {
        RuntimeConfig runtime = new RuntimeConfig();
        runtime.applyFromNeonConfig(config);
        return runtime;
    }

    private void initializeDefaults() {
        defaults.put("buffer.size", 65535);
        defaults.put("buffer.poolInitialSize", 16);
        defaults.put("buffer.poolMaxSize", 64);
        defaults.put("buffer.minSize", 1024);
        defaults.put("buffer.enforce", true);

        defaults.put("relay.port", 7777);
        defaults.put("relay.cleanupIntervalMs", 5000);
        defaults.put("relay.clientTimeoutMs", 15000);
        defaults.put("relay.socketTimeoutMs", 100);
        defaults.put("relay.mainLoopSleepMs", 1);
        defaults.put("relay.pendingConnectionTimeoutMs", 30000);

        defaults.put("limits.maxPacketsPerSecond", 100);
        defaults.put("limits.maxClientsPerSession", 32);
        defaults.put("limits.maxTotalConnections", 1000);
        defaults.put("limits.maxPendingConnections", 100);
        defaults.put("limits.maxRateLimiters", 2000);
        defaults.put("limits.maxPayloadSize", 65507);

        defaults.put("flood.threshold", 3);
        defaults.put("flood.windowMs", 10000);
        defaults.put("flood.throttlePenaltyDivisor", 2);
        defaults.put("flood.tokenRefillIntervalMs", 1000);

        defaults.put("host.ackTimeoutMs", 2000);
        defaults.put("host.maxAckRetries", 5);
        defaults.put("host.reliabilityDelayMs", 50);
        defaults.put("host.gracefulShutdownTimeoutMs", 2000);
        defaults.put("host.sessionTokenTimeoutMs", 300000);
        defaults.put("host.socketTimeoutMs", 100);
        defaults.put("host.processingLoopSleepMs", 10);

        defaults.put("client.pingIntervalMs", 5000);
        defaults.put("client.connectionTimeoutMs", 10000);
        defaults.put("client.maxReconnectAttempts", 5);
        defaults.put("client.initialReconnectDelayMs", 1000);
        defaults.put("client.maxReconnectDelayMs", 30000);
        defaults.put("client.socketTimeoutMs", 100);
        defaults.put("client.processingLoopSleepMs", 10);
        defaults.put("client.disconnectNoticeDelayMs", 50);

        defaults.put("reliable.packetTimeoutMs", 2000);
        defaults.put("reliable.packetMaxRetries", 5);

        defaults.put("batch.ackMaxSize", 10);
        defaults.put("batch.ackMaxDelayMs", 50);

        defaults.put("protocol.maxNameLength", 64);
        defaults.put("protocol.maxDescriptionLength", 256);
        defaults.put("protocol.maxPacketCount", 100);

        defaults.put("event.useEventDrivenReceiver", false);
        defaults.put("event.loopSelectTimeoutMs", 100);
    }

    /**
     * Gets an integer value.
     */
    public int getInt(String key) {
        return getInt(key, getDefaultInt(key));
    }

    /**
     * Gets an integer value with a fallback default.
     */
    public int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                /* Fall through to default */
            }
        }
        return defaultValue;
    }

    /**
     * Gets a long value.
     */
    public long getLong(String key) {
        return getLong(key, getDefaultLong(key));
    }

    /**
     * Gets a long value with a fallback default.
     */
    public long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                /* Fall through to default */
            }
        }
        return defaultValue;
    }

    /**
     * Gets a boolean value.
     */
    public boolean getBoolean(String key) {
        return getBoolean(key, getDefaultBoolean(key));
    }

    /**
     * Gets a boolean value with a fallback default.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * Gets a string value.
     */
    public String getString(String key) {
        return getString(key, getDefaultString(key));
    }

    /**
     * Gets a string value with a fallback default.
     */
    public String getString(String key, String defaultValue) {
        Object value = values.get(key);
        if (value != null) {
            return value.toString();
        }
        return defaultValue;
    }

    /**
     * Sets an integer value and notifies listeners.
     */
    public void setInt(String key, int value) {
        setValue(key, value);
    }

    /**
     * Sets a long value and notifies listeners.
     */
    public void setLong(String key, long value) {
        setValue(key, value);
    }

    /**
     * Sets a boolean value and notifies listeners.
     */
    public void setBoolean(String key, boolean value) {
        setValue(key, value);
    }

    /**
     * Sets a string value and notifies listeners.
     */
    public void setString(String key, String value) {
        setValue(key, value);
    }

    private void setValue(String key, Object newValue) {
        Object oldValue = values.put(key, newValue);
        if (!Objects.equals(oldValue, newValue)) {
            notifyListeners(key, oldValue, newValue);
        }
    }

    /**
     * Removes a value, reverting to default.
     */
    public void remove(String key) {
        Object oldValue = values.remove(key);
        if (oldValue != null) {
            notifyListeners(key, oldValue, defaults.get(key));
        }
    }

    /**
     * Checks if a key has an explicit value set (not using default).
     */
    public boolean isSet(String key) {
        return values.containsKey(key);
    }

    /**
     * Gets all keys that have explicit values set.
     */
    public Set<String> getSetKeys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /**
     * Gets all available keys (both set and default).
     */
    public Set<String> getAllKeys() {
        Set<String> all = new HashSet<>(defaults.keySet());
        all.addAll(values.keySet());
        return Collections.unmodifiableSet(all);
    }

    private int getDefaultInt(String key) {
        Object def = defaults.get(key);
        return def instanceof Number ? ((Number) def).intValue() : 0;
    }

    private long getDefaultLong(String key) {
        Object def = defaults.get(key);
        return def instanceof Number ? ((Number) def).longValue() : 0L;
    }

    private boolean getDefaultBoolean(String key) {
        Object def = defaults.get(key);
        return def instanceof Boolean && (Boolean) def;
    }

    private String getDefaultString(String key) {
        Object def = defaults.get(key);
        return def != null ? def.toString() : "";
    }

    /**
     * Adds a global listener notified of all configuration changes.
     */
    public void addListener(ConfigChangeListener listener) {
        globalListeners.add(listener);
    }

    /**
     * Adds a listener for changes to a specific key.
     */
    public void addListener(String key, ConfigChangeListener listener) {
        keyListeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Removes a global listener.
     */
    public void removeListener(ConfigChangeListener listener) {
        globalListeners.remove(listener);
    }

    /**
     * Removes a key-specific listener.
     */
    public void removeListener(String key, ConfigChangeListener listener) {
        List<ConfigChangeListener> listeners = keyListeners.get(key);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(String key, Object oldValue, Object newValue) {
        for (ConfigChangeListener listener : globalListeners) {
            listener.onConfigChange(key, oldValue, newValue);
        }

        List<ConfigChangeListener> listeners = keyListeners.get(key);
        if (listeners != null) {
            for (ConfigChangeListener listener : listeners) {
                listener.onConfigChange(key, oldValue, newValue);
            }
        }
    }

    /**
     * Loads configuration from a properties file.
     */
    public void loadFromFile(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        loadFromProperties(props);
    }

    /**
     * Loads configuration from properties.
     */
    public void loadFromProperties(Properties props) {
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            Object defaultValue = defaults.get(key);

            if (defaultValue instanceof Integer) {
                try {
                    setInt(key, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    /* Skip invalid value */
                }
            } else if (defaultValue instanceof Long) {
                try {
                    setLong(key, Long.parseLong(value));
                } catch (NumberFormatException e) {
                    /* Skip invalid value */
                }
            } else if (defaultValue instanceof Boolean) {
                setBoolean(key, Boolean.parseBoolean(value));
            } else {
                setString(key, value);
            }
        }
    }

    /**
     * Saves configuration to a properties file.
     */
    public void saveToFile(Path path) throws IOException {
        Properties props = toProperties();
        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "Project Neon Runtime Configuration");
        }
    }

    /**
     * Exports configuration to Properties.
     */
    public Properties toProperties() {
        Properties props = new Properties();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue().toString());
        }
        return props;
    }

    /**
     * Applies values from a NeonConfig.
     */
    public void applyFromNeonConfig(NeonConfig config) {
        setInt("buffer.size", config.getBufferSize());
        setInt("buffer.poolInitialSize", config.getBufferPoolInitialSize());
        setInt("buffer.poolMaxSize", config.getBufferPoolMaxSize());
        setInt("buffer.minSize", config.getMinBufferSize());
        setBoolean("buffer.enforce", config.isEnforceBufferSize());

        setInt("relay.port", config.getRelayPort());
        setInt("relay.cleanupIntervalMs", config.getRelayCleanupIntervalMs());
        setInt("relay.clientTimeoutMs", config.getRelayClientTimeoutMs());
        setInt("relay.socketTimeoutMs", config.getRelaySocketTimeoutMs());
        setInt("relay.mainLoopSleepMs", config.getRelayMainLoopSleepMs());
        setInt("relay.pendingConnectionTimeoutMs", config.getRelayPendingConnectionTimeoutMs());

        setInt("limits.maxPacketsPerSecond", config.getMaxPacketsPerSecond());
        setInt("limits.maxClientsPerSession", config.getMaxClientsPerSession());
        setInt("limits.maxTotalConnections", config.getMaxTotalConnections());
        setInt("limits.maxPendingConnections", config.getMaxPendingConnections());
        setInt("limits.maxRateLimiters", config.getMaxRateLimiters());
        setInt("limits.maxPayloadSize", config.getMaxPayloadSize());

        setInt("flood.threshold", config.getFloodThreshold());
        setInt("flood.windowMs", config.getFloodWindowMs());
        setInt("flood.throttlePenaltyDivisor", config.getThrottlePenaltyDivisor());
        setInt("flood.tokenRefillIntervalMs", config.getTokenRefillIntervalMs());

        setInt("host.ackTimeoutMs", config.getHostAckTimeoutMs());
        setInt("host.maxAckRetries", config.getHostMaxAckRetries());
        setInt("host.reliabilityDelayMs", config.getHostReliabilityDelayMs());
        setInt("host.gracefulShutdownTimeoutMs", config.getHostGracefulShutdownTimeoutMs());
        setInt("host.sessionTokenTimeoutMs", config.getHostSessionTokenTimeoutMs());
        setInt("host.socketTimeoutMs", config.getHostSocketTimeoutMs());
        setInt("host.processingLoopSleepMs", config.getHostProcessingLoopSleepMs());

        setInt("client.pingIntervalMs", config.getClientPingIntervalMs());
        setInt("client.connectionTimeoutMs", config.getClientConnectionTimeoutMs());
        setInt("client.maxReconnectAttempts", config.getClientMaxReconnectAttempts());
        setInt("client.initialReconnectDelayMs", config.getClientInitialReconnectDelayMs());
        setInt("client.maxReconnectDelayMs", config.getClientMaxReconnectDelayMs());
        setInt("client.socketTimeoutMs", config.getClientSocketTimeoutMs());
        setInt("client.processingLoopSleepMs", config.getClientProcessingLoopSleepMs());
        setInt("client.disconnectNoticeDelayMs", config.getClientDisconnectNoticeDelayMs());

        setInt("reliable.packetTimeoutMs", config.getReliablePacketTimeoutMs());
        setInt("reliable.packetMaxRetries", config.getReliablePacketMaxRetries());

        setInt("batch.ackMaxSize", config.getBatchAckMaxSize());
        setInt("batch.ackMaxDelayMs", config.getBatchAckMaxDelayMs());

        setInt("protocol.maxNameLength", config.getMaxNameLength());
        setInt("protocol.maxDescriptionLength", config.getMaxDescriptionLength());
        setInt("protocol.maxPacketCount", config.getMaxPacketCount());

        setBoolean("event.useEventDrivenReceiver", config.isUseEventDrivenReceiver());
        setInt("event.loopSelectTimeoutMs", config.getEventLoopSelectTimeoutMs());
    }

    /**
     * Creates a NeonConfig from current runtime values.
     */
    public NeonConfig toNeonConfig() {
        return NeonConfig.builder()
            .bufferSize(getInt("buffer.size"))
            .bufferPoolInitialSize(getInt("buffer.poolInitialSize"))
            .bufferPoolMaxSize(getInt("buffer.poolMaxSize"))
            .minBufferSize(getInt("buffer.minSize"))
            .enforceBufferSize(getBoolean("buffer.enforce"))
            .relayPort(getInt("relay.port"))
            .relayCleanupIntervalMs(getInt("relay.cleanupIntervalMs"))
            .relayClientTimeoutMs(getInt("relay.clientTimeoutMs"))
            .relaySocketTimeoutMs(getInt("relay.socketTimeoutMs"))
            .relayMainLoopSleepMs(getInt("relay.mainLoopSleepMs"))
            .relayPendingConnectionTimeoutMs(getInt("relay.pendingConnectionTimeoutMs"))
            .maxPacketsPerSecond(getInt("limits.maxPacketsPerSecond"))
            .maxClientsPerSession(getInt("limits.maxClientsPerSession"))
            .maxTotalConnections(getInt("limits.maxTotalConnections"))
            .maxPendingConnections(getInt("limits.maxPendingConnections"))
            .maxRateLimiters(getInt("limits.maxRateLimiters"))
            .maxPayloadSize(getInt("limits.maxPayloadSize"))
            .floodThreshold(getInt("flood.threshold"))
            .floodWindowMs(getInt("flood.windowMs"))
            .throttlePenaltyDivisor(getInt("flood.throttlePenaltyDivisor"))
            .tokenRefillIntervalMs(getInt("flood.tokenRefillIntervalMs"))
            .hostAckTimeoutMs(getInt("host.ackTimeoutMs"))
            .hostMaxAckRetries(getInt("host.maxAckRetries"))
            .hostReliabilityDelayMs(getInt("host.reliabilityDelayMs"))
            .hostGracefulShutdownTimeoutMs(getInt("host.gracefulShutdownTimeoutMs"))
            .hostSessionTokenTimeoutMs(getInt("host.sessionTokenTimeoutMs"))
            .hostSocketTimeoutMs(getInt("host.socketTimeoutMs"))
            .hostProcessingLoopSleepMs(getInt("host.processingLoopSleepMs"))
            .clientPingIntervalMs(getInt("client.pingIntervalMs"))
            .clientConnectionTimeoutMs(getInt("client.connectionTimeoutMs"))
            .clientMaxReconnectAttempts(getInt("client.maxReconnectAttempts"))
            .clientInitialReconnectDelayMs(getInt("client.initialReconnectDelayMs"))
            .clientMaxReconnectDelayMs(getInt("client.maxReconnectDelayMs"))
            .clientSocketTimeoutMs(getInt("client.socketTimeoutMs"))
            .clientProcessingLoopSleepMs(getInt("client.processingLoopSleepMs"))
            .clientDisconnectNoticeDelayMs(getInt("client.disconnectNoticeDelayMs"))
            .reliablePacketTimeoutMs(getInt("reliable.packetTimeoutMs"))
            .reliablePacketMaxRetries(getInt("reliable.packetMaxRetries"))
            .batchAckMaxSize(getInt("batch.ackMaxSize"))
            .batchAckMaxDelayMs(getInt("batch.ackMaxDelayMs"))
            .maxNameLength(getInt("protocol.maxNameLength"))
            .maxDescriptionLength(getInt("protocol.maxDescriptionLength"))
            .maxPacketCount(getInt("protocol.maxPacketCount"))
            .useEventDrivenReceiver(getBoolean("event.useEventDrivenReceiver"))
            .eventLoopSelectTimeoutMs(getInt("event.loopSelectTimeoutMs"))
            .build();
    }

    /**
     * Resets all values to defaults.
     */
    public void resetToDefaults() {
        Set<String> keys = new HashSet<>(values.keySet());
        values.clear();
        for (String key : keys) {
            notifyListeners(key, values.get(key), defaults.get(key));
        }
    }

    /**
     * Listener interface for configuration changes.
     */
    @FunctionalInterface
    @PublicAPI
    public interface ConfigChangeListener {
        /**
         * Called when a configuration value changes.
         *
         * @param key the configuration key that changed
         * @param oldValue the previous value (may be null)
         * @param newValue the new value
         */
        void onConfigChange(String key, Object oldValue, Object newValue);
    }
}
