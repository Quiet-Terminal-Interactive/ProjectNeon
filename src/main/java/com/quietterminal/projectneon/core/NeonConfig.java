package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

/**
 * Configuration class for Project Neon networking parameters.
 * All timing values are in milliseconds, all size values are in bytes.
 *
 * <p>Default values are tuned for typical game networking scenarios.
 * Adjust based on your specific requirements:
 * <ul>
 *   <li>Lower timeouts for fast-paced games on reliable networks</li>
 *   <li>Higher timeouts for turn-based games or unreliable networks</li>
 *   <li>Larger buffers for high-bandwidth data transfer</li>
 *   <li>Stricter rate limits for public servers</li>
 * </ul>
 *
 * <p>Configuration can be created using:
 * <ul>
 *   <li>Default constructor with fluent setters: {@code new NeonConfig().setBufferSize(2048)...}</li>
 *   <li>Builder pattern: {@code NeonConfig.builder().bufferSize(2048)...build()}</li>
 * </ul>
 *
 * <p>Thread safety: This class is not thread-safe. Create and configure instances before
 * passing to Neon components. Once passed to a component, configuration should not be modified.
 *
 * @since 1.0
 */
@PublicAPI
public class NeonConfig {

    private int bufferSize = 1024;

    private int relayPort = 7777;
    private int relayCleanupIntervalMs = 5000;
    private int relayClientTimeoutMs = 15000;
    private int relaySocketTimeoutMs = 100;
    private int relayMainLoopSleepMs = 1;
    private int relayPendingConnectionTimeoutMs = 30000;

    private int maxPacketsPerSecond = 100;
    private int maxClientsPerSession = 32;
    private int maxTotalConnections = 1000;
    private int maxPendingConnections = 100;
    private int maxRateLimiters = 2000;

    private int floodThreshold = 3;
    private int floodWindowMs = 10000;
    private int throttlePenaltyDivisor = 2;
    private int tokenRefillIntervalMs = 1000;

    private int hostAckTimeoutMs = 2000;
    private int hostMaxAckRetries = 5;
    private int hostReliabilityDelayMs = 50;
    private int hostGracefulShutdownTimeoutMs = 2000;
    private int hostSessionTokenTimeoutMs = 300000;
    private int hostSocketTimeoutMs = 100;
    private int hostProcessingLoopSleepMs = 10;

    private int clientPingIntervalMs = 5000;
    private int clientConnectionTimeoutMs = 10000;
    private int clientMaxReconnectAttempts = 5;
    private int clientInitialReconnectDelayMs = 1000;
    private int clientMaxReconnectDelayMs = 30000;
    private int clientSocketTimeoutMs = 100;
    private int clientProcessingLoopSleepMs = 10;
    private int clientDisconnectNoticeDelayMs = 50;

    private int reliablePacketTimeoutMs = 2000;
    private int reliablePacketMaxRetries = 5;

    private int maxNameLength = 64;
    private int maxDescriptionLength = 256;
    private int maxPacketCount = 100;
    private int maxPayloadSize = 65507;

    /**
     * Creates a NeonConfig with default values suitable for typical game networking.
     */
    public NeonConfig() {
    }

    /**
     * Validates all configuration values and throws IllegalArgumentException if any are invalid.
     * Called automatically when config is applied to components.
     *
     * @throws IllegalArgumentException if any configuration value is invalid
     */
    public void validate() {
        if (bufferSize <= 0 || bufferSize > 65535) {
            throw new IllegalArgumentException("bufferSize must be between 1 and 65535, got: " + bufferSize);
        }

        if (relayPort < 1 || relayPort > 65535) {
            throw new IllegalArgumentException("relayPort must be between 1 and 65535, got: " + relayPort);
        }
        if (relayCleanupIntervalMs <= 0) {
            throw new IllegalArgumentException("relayCleanupIntervalMs must be positive, got: " + relayCleanupIntervalMs);
        }
        if (relayClientTimeoutMs <= 0) {
            throw new IllegalArgumentException("relayClientTimeoutMs must be positive, got: " + relayClientTimeoutMs);
        }
        if (relaySocketTimeoutMs < 0) {
            throw new IllegalArgumentException("relaySocketTimeoutMs must be non-negative, got: " + relaySocketTimeoutMs);
        }
        if (relayMainLoopSleepMs < 0) {
            throw new IllegalArgumentException("relayMainLoopSleepMs must be non-negative, got: " + relayMainLoopSleepMs);
        }
        if (relayPendingConnectionTimeoutMs <= 0) {
            throw new IllegalArgumentException("relayPendingConnectionTimeoutMs must be positive, got: " + relayPendingConnectionTimeoutMs);
        }

        if (maxPacketsPerSecond <= 0) {
            throw new IllegalArgumentException("maxPacketsPerSecond must be positive, got: " + maxPacketsPerSecond);
        }
        if (maxClientsPerSession <= 0) {
            throw new IllegalArgumentException("maxClientsPerSession must be positive, got: " + maxClientsPerSession);
        }
        if (maxTotalConnections <= 0) {
            throw new IllegalArgumentException("maxTotalConnections must be positive, got: " + maxTotalConnections);
        }
        if (maxPendingConnections <= 0) {
            throw new IllegalArgumentException("maxPendingConnections must be positive, got: " + maxPendingConnections);
        }
        if (maxRateLimiters <= 0) {
            throw new IllegalArgumentException("maxRateLimiters must be positive, got: " + maxRateLimiters);
        }

        if (floodThreshold <= 0) {
            throw new IllegalArgumentException("floodThreshold must be positive, got: " + floodThreshold);
        }
        if (floodWindowMs <= 0) {
            throw new IllegalArgumentException("floodWindowMs must be positive, got: " + floodWindowMs);
        }
        if (throttlePenaltyDivisor <= 0) {
            throw new IllegalArgumentException("throttlePenaltyDivisor must be positive, got: " + throttlePenaltyDivisor);
        }
        if (tokenRefillIntervalMs <= 0) {
            throw new IllegalArgumentException("tokenRefillIntervalMs must be positive, got: " + tokenRefillIntervalMs);
        }

        if (hostAckTimeoutMs <= 0) {
            throw new IllegalArgumentException("hostAckTimeoutMs must be positive, got: " + hostAckTimeoutMs);
        }
        if (hostMaxAckRetries < 0) {
            throw new IllegalArgumentException("hostMaxAckRetries must be non-negative, got: " + hostMaxAckRetries);
        }
        if (hostReliabilityDelayMs < 0) {
            throw new IllegalArgumentException("hostReliabilityDelayMs must be non-negative, got: " + hostReliabilityDelayMs);
        }
        if (hostGracefulShutdownTimeoutMs < 0) {
            throw new IllegalArgumentException("hostGracefulShutdownTimeoutMs must be non-negative, got: " + hostGracefulShutdownTimeoutMs);
        }
        if (hostSessionTokenTimeoutMs <= 0) {
            throw new IllegalArgumentException("hostSessionTokenTimeoutMs must be positive, got: " + hostSessionTokenTimeoutMs);
        }
        if (hostSocketTimeoutMs < 0) {
            throw new IllegalArgumentException("hostSocketTimeoutMs must be non-negative, got: " + hostSocketTimeoutMs);
        }
        if (hostProcessingLoopSleepMs < 0) {
            throw new IllegalArgumentException("hostProcessingLoopSleepMs must be non-negative, got: " + hostProcessingLoopSleepMs);
        }

        if (clientPingIntervalMs <= 0) {
            throw new IllegalArgumentException("clientPingIntervalMs must be positive, got: " + clientPingIntervalMs);
        }
        if (clientConnectionTimeoutMs <= 0) {
            throw new IllegalArgumentException("clientConnectionTimeoutMs must be positive, got: " + clientConnectionTimeoutMs);
        }
        if (clientMaxReconnectAttempts < 0) {
            throw new IllegalArgumentException("clientMaxReconnectAttempts must be non-negative, got: " + clientMaxReconnectAttempts);
        }
        if (clientInitialReconnectDelayMs <= 0) {
            throw new IllegalArgumentException("clientInitialReconnectDelayMs must be positive, got: " + clientInitialReconnectDelayMs);
        }
        if (clientMaxReconnectDelayMs <= 0) {
            throw new IllegalArgumentException("clientMaxReconnectDelayMs must be positive, got: " + clientMaxReconnectDelayMs);
        }
        if (clientSocketTimeoutMs < 0) {
            throw new IllegalArgumentException("clientSocketTimeoutMs must be non-negative, got: " + clientSocketTimeoutMs);
        }
        if (clientProcessingLoopSleepMs < 0) {
            throw new IllegalArgumentException("clientProcessingLoopSleepMs must be non-negative, got: " + clientProcessingLoopSleepMs);
        }
        if (clientDisconnectNoticeDelayMs < 0) {
            throw new IllegalArgumentException("clientDisconnectNoticeDelayMs must be non-negative, got: " + clientDisconnectNoticeDelayMs);
        }

        if (reliablePacketTimeoutMs <= 0) {
            throw new IllegalArgumentException("reliablePacketTimeoutMs must be positive, got: " + reliablePacketTimeoutMs);
        }
        if (reliablePacketMaxRetries < 0) {
            throw new IllegalArgumentException("reliablePacketMaxRetries must be non-negative, got: " + reliablePacketMaxRetries);
        }

        if (maxNameLength <= 0 || maxNameLength > 1024) {
            throw new IllegalArgumentException("maxNameLength must be between 1 and 1024, got: " + maxNameLength);
        }
        if (maxDescriptionLength <= 0 || maxDescriptionLength > 4096) {
            throw new IllegalArgumentException("maxDescriptionLength must be between 1 and 4096, got: " + maxDescriptionLength);
        }
        if (maxPacketCount <= 0 || maxPacketCount > 10000) {
            throw new IllegalArgumentException("maxPacketCount must be between 1 and 10000, got: " + maxPacketCount);
        }
        if (maxPayloadSize <= 0 || maxPayloadSize > 65507) {
            throw new IllegalArgumentException("maxPayloadSize must be between 1 and 65507 (UDP limit), got: " + maxPayloadSize);
        }
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public NeonConfig setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        return this;
    }

    public int getRelayPort() {
        return relayPort;
    }

    public NeonConfig setRelayPort(int relayPort) {
        this.relayPort = relayPort;
        return this;
    }

    public int getRelayCleanupIntervalMs() {
        return relayCleanupIntervalMs;
    }

    public NeonConfig setRelayCleanupIntervalMs(int relayCleanupIntervalMs) {
        this.relayCleanupIntervalMs = relayCleanupIntervalMs;
        return this;
    }

    public int getRelayClientTimeoutMs() {
        return relayClientTimeoutMs;
    }

    public NeonConfig setRelayClientTimeoutMs(int relayClientTimeoutMs) {
        this.relayClientTimeoutMs = relayClientTimeoutMs;
        return this;
    }

    public int getRelaySocketTimeoutMs() {
        return relaySocketTimeoutMs;
    }

    public NeonConfig setRelaySocketTimeoutMs(int relaySocketTimeoutMs) {
        this.relaySocketTimeoutMs = relaySocketTimeoutMs;
        return this;
    }

    public int getRelayMainLoopSleepMs() {
        return relayMainLoopSleepMs;
    }

    public NeonConfig setRelayMainLoopSleepMs(int relayMainLoopSleepMs) {
        this.relayMainLoopSleepMs = relayMainLoopSleepMs;
        return this;
    }

    public int getRelayPendingConnectionTimeoutMs() {
        return relayPendingConnectionTimeoutMs;
    }

    public NeonConfig setRelayPendingConnectionTimeoutMs(int relayPendingConnectionTimeoutMs) {
        this.relayPendingConnectionTimeoutMs = relayPendingConnectionTimeoutMs;
        return this;
    }

    public int getMaxPacketsPerSecond() {
        return maxPacketsPerSecond;
    }

    public NeonConfig setMaxPacketsPerSecond(int maxPacketsPerSecond) {
        this.maxPacketsPerSecond = maxPacketsPerSecond;
        return this;
    }

    public int getMaxClientsPerSession() {
        return maxClientsPerSession;
    }

    public NeonConfig setMaxClientsPerSession(int maxClientsPerSession) {
        this.maxClientsPerSession = maxClientsPerSession;
        return this;
    }

    public int getMaxTotalConnections() {
        return maxTotalConnections;
    }

    public NeonConfig setMaxTotalConnections(int maxTotalConnections) {
        this.maxTotalConnections = maxTotalConnections;
        return this;
    }

    public int getMaxPendingConnections() {
        return maxPendingConnections;
    }

    public NeonConfig setMaxPendingConnections(int maxPendingConnections) {
        this.maxPendingConnections = maxPendingConnections;
        return this;
    }

    public int getMaxRateLimiters() {
        return maxRateLimiters;
    }

    public NeonConfig setMaxRateLimiters(int maxRateLimiters) {
        this.maxRateLimiters = maxRateLimiters;
        return this;
    }

    public int getFloodThreshold() {
        return floodThreshold;
    }

    public NeonConfig setFloodThreshold(int floodThreshold) {
        this.floodThreshold = floodThreshold;
        return this;
    }

    public int getFloodWindowMs() {
        return floodWindowMs;
    }

    public NeonConfig setFloodWindowMs(int floodWindowMs) {
        this.floodWindowMs = floodWindowMs;
        return this;
    }

    public int getThrottlePenaltyDivisor() {
        return throttlePenaltyDivisor;
    }

    public NeonConfig setThrottlePenaltyDivisor(int throttlePenaltyDivisor) {
        this.throttlePenaltyDivisor = throttlePenaltyDivisor;
        return this;
    }

    public int getTokenRefillIntervalMs() {
        return tokenRefillIntervalMs;
    }

    public NeonConfig setTokenRefillIntervalMs(int tokenRefillIntervalMs) {
        this.tokenRefillIntervalMs = tokenRefillIntervalMs;
        return this;
    }

    public int getHostAckTimeoutMs() {
        return hostAckTimeoutMs;
    }

    public NeonConfig setHostAckTimeoutMs(int hostAckTimeoutMs) {
        this.hostAckTimeoutMs = hostAckTimeoutMs;
        return this;
    }

    public int getHostMaxAckRetries() {
        return hostMaxAckRetries;
    }

    public NeonConfig setHostMaxAckRetries(int hostMaxAckRetries) {
        this.hostMaxAckRetries = hostMaxAckRetries;
        return this;
    }

    public int getHostReliabilityDelayMs() {
        return hostReliabilityDelayMs;
    }

    public NeonConfig setHostReliabilityDelayMs(int hostReliabilityDelayMs) {
        this.hostReliabilityDelayMs = hostReliabilityDelayMs;
        return this;
    }

    public int getHostGracefulShutdownTimeoutMs() {
        return hostGracefulShutdownTimeoutMs;
    }

    public NeonConfig setHostGracefulShutdownTimeoutMs(int hostGracefulShutdownTimeoutMs) {
        this.hostGracefulShutdownTimeoutMs = hostGracefulShutdownTimeoutMs;
        return this;
    }

    public int getHostSessionTokenTimeoutMs() {
        return hostSessionTokenTimeoutMs;
    }

    public NeonConfig setHostSessionTokenTimeoutMs(int hostSessionTokenTimeoutMs) {
        this.hostSessionTokenTimeoutMs = hostSessionTokenTimeoutMs;
        return this;
    }

    public int getHostSocketTimeoutMs() {
        return hostSocketTimeoutMs;
    }

    public NeonConfig setHostSocketTimeoutMs(int hostSocketTimeoutMs) {
        this.hostSocketTimeoutMs = hostSocketTimeoutMs;
        return this;
    }

    public int getHostProcessingLoopSleepMs() {
        return hostProcessingLoopSleepMs;
    }

    public NeonConfig setHostProcessingLoopSleepMs(int hostProcessingLoopSleepMs) {
        this.hostProcessingLoopSleepMs = hostProcessingLoopSleepMs;
        return this;
    }

    public int getClientPingIntervalMs() {
        return clientPingIntervalMs;
    }

    public NeonConfig setClientPingIntervalMs(int clientPingIntervalMs) {
        this.clientPingIntervalMs = clientPingIntervalMs;
        return this;
    }

    public int getClientConnectionTimeoutMs() {
        return clientConnectionTimeoutMs;
    }

    public NeonConfig setClientConnectionTimeoutMs(int clientConnectionTimeoutMs) {
        this.clientConnectionTimeoutMs = clientConnectionTimeoutMs;
        return this;
    }

    public int getClientMaxReconnectAttempts() {
        return clientMaxReconnectAttempts;
    }

    public NeonConfig setClientMaxReconnectAttempts(int clientMaxReconnectAttempts) {
        this.clientMaxReconnectAttempts = clientMaxReconnectAttempts;
        return this;
    }

    public int getClientInitialReconnectDelayMs() {
        return clientInitialReconnectDelayMs;
    }

    public NeonConfig setClientInitialReconnectDelayMs(int clientInitialReconnectDelayMs) {
        this.clientInitialReconnectDelayMs = clientInitialReconnectDelayMs;
        return this;
    }

    public int getClientMaxReconnectDelayMs() {
        return clientMaxReconnectDelayMs;
    }

    public NeonConfig setClientMaxReconnectDelayMs(int clientMaxReconnectDelayMs) {
        this.clientMaxReconnectDelayMs = clientMaxReconnectDelayMs;
        return this;
    }

    public int getClientSocketTimeoutMs() {
        return clientSocketTimeoutMs;
    }

    public NeonConfig setClientSocketTimeoutMs(int clientSocketTimeoutMs) {
        this.clientSocketTimeoutMs = clientSocketTimeoutMs;
        return this;
    }

    public int getClientProcessingLoopSleepMs() {
        return clientProcessingLoopSleepMs;
    }

    public NeonConfig setClientProcessingLoopSleepMs(int clientProcessingLoopSleepMs) {
        this.clientProcessingLoopSleepMs = clientProcessingLoopSleepMs;
        return this;
    }

    public int getClientDisconnectNoticeDelayMs() {
        return clientDisconnectNoticeDelayMs;
    }

    public NeonConfig setClientDisconnectNoticeDelayMs(int clientDisconnectNoticeDelayMs) {
        this.clientDisconnectNoticeDelayMs = clientDisconnectNoticeDelayMs;
        return this;
    }

    public int getReliablePacketTimeoutMs() {
        return reliablePacketTimeoutMs;
    }

    public NeonConfig setReliablePacketTimeoutMs(int reliablePacketTimeoutMs) {
        this.reliablePacketTimeoutMs = reliablePacketTimeoutMs;
        return this;
    }

    public int getReliablePacketMaxRetries() {
        return reliablePacketMaxRetries;
    }

    public NeonConfig setReliablePacketMaxRetries(int reliablePacketMaxRetries) {
        this.reliablePacketMaxRetries = reliablePacketMaxRetries;
        return this;
    }

    public int getMaxNameLength() {
        return maxNameLength;
    }

    public NeonConfig setMaxNameLength(int maxNameLength) {
        this.maxNameLength = maxNameLength;
        return this;
    }

    public int getMaxDescriptionLength() {
        return maxDescriptionLength;
    }

    public NeonConfig setMaxDescriptionLength(int maxDescriptionLength) {
        this.maxDescriptionLength = maxDescriptionLength;
        return this;
    }

    public int getMaxPacketCount() {
        return maxPacketCount;
    }

    public NeonConfig setMaxPacketCount(int maxPacketCount) {
        this.maxPacketCount = maxPacketCount;
        return this;
    }

    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public NeonConfig setMaxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
        return this;
    }

    /**
     * Creates a new builder for constructing NeonConfig instances.
     *
     * @return a new Builder instance
     * @since 1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for creating NeonConfig instances with a fluent API.
     *
     * @since 1.0
     */
    @PublicAPI
    public static class Builder {
        private final NeonConfig config = new NeonConfig();

        private Builder() {
        }

        public Builder bufferSize(int bufferSize) {
            config.setBufferSize(bufferSize);
            return this;
        }

        public Builder relayPort(int relayPort) {
            config.setRelayPort(relayPort);
            return this;
        }

        public Builder relayCleanupIntervalMs(int relayCleanupIntervalMs) {
            config.setRelayCleanupIntervalMs(relayCleanupIntervalMs);
            return this;
        }

        public Builder relayClientTimeoutMs(int relayClientTimeoutMs) {
            config.setRelayClientTimeoutMs(relayClientTimeoutMs);
            return this;
        }

        public Builder relaySocketTimeoutMs(int relaySocketTimeoutMs) {
            config.setRelaySocketTimeoutMs(relaySocketTimeoutMs);
            return this;
        }

        public Builder relayMainLoopSleepMs(int relayMainLoopSleepMs) {
            config.setRelayMainLoopSleepMs(relayMainLoopSleepMs);
            return this;
        }

        public Builder relayPendingConnectionTimeoutMs(int relayPendingConnectionTimeoutMs) {
            config.setRelayPendingConnectionTimeoutMs(relayPendingConnectionTimeoutMs);
            return this;
        }

        public Builder maxPacketsPerSecond(int maxPacketsPerSecond) {
            config.setMaxPacketsPerSecond(maxPacketsPerSecond);
            return this;
        }

        public Builder maxClientsPerSession(int maxClientsPerSession) {
            config.setMaxClientsPerSession(maxClientsPerSession);
            return this;
        }

        public Builder maxTotalConnections(int maxTotalConnections) {
            config.setMaxTotalConnections(maxTotalConnections);
            return this;
        }

        public Builder maxPendingConnections(int maxPendingConnections) {
            config.setMaxPendingConnections(maxPendingConnections);
            return this;
        }

        public Builder maxRateLimiters(int maxRateLimiters) {
            config.setMaxRateLimiters(maxRateLimiters);
            return this;
        }

        public Builder floodThreshold(int floodThreshold) {
            config.setFloodThreshold(floodThreshold);
            return this;
        }

        public Builder floodWindowMs(int floodWindowMs) {
            config.setFloodWindowMs(floodWindowMs);
            return this;
        }

        public Builder throttlePenaltyDivisor(int throttlePenaltyDivisor) {
            config.setThrottlePenaltyDivisor(throttlePenaltyDivisor);
            return this;
        }

        public Builder tokenRefillIntervalMs(int tokenRefillIntervalMs) {
            config.setTokenRefillIntervalMs(tokenRefillIntervalMs);
            return this;
        }

        public Builder hostAckTimeoutMs(int hostAckTimeoutMs) {
            config.setHostAckTimeoutMs(hostAckTimeoutMs);
            return this;
        }

        public Builder hostMaxAckRetries(int hostMaxAckRetries) {
            config.setHostMaxAckRetries(hostMaxAckRetries);
            return this;
        }

        public Builder hostReliabilityDelayMs(int hostReliabilityDelayMs) {
            config.setHostReliabilityDelayMs(hostReliabilityDelayMs);
            return this;
        }

        public Builder hostGracefulShutdownTimeoutMs(int hostGracefulShutdownTimeoutMs) {
            config.setHostGracefulShutdownTimeoutMs(hostGracefulShutdownTimeoutMs);
            return this;
        }

        public Builder hostSessionTokenTimeoutMs(int hostSessionTokenTimeoutMs) {
            config.setHostSessionTokenTimeoutMs(hostSessionTokenTimeoutMs);
            return this;
        }

        public Builder hostSocketTimeoutMs(int hostSocketTimeoutMs) {
            config.setHostSocketTimeoutMs(hostSocketTimeoutMs);
            return this;
        }

        public Builder hostProcessingLoopSleepMs(int hostProcessingLoopSleepMs) {
            config.setHostProcessingLoopSleepMs(hostProcessingLoopSleepMs);
            return this;
        }

        public Builder clientPingIntervalMs(int clientPingIntervalMs) {
            config.setClientPingIntervalMs(clientPingIntervalMs);
            return this;
        }

        public Builder clientConnectionTimeoutMs(int clientConnectionTimeoutMs) {
            config.setClientConnectionTimeoutMs(clientConnectionTimeoutMs);
            return this;
        }

        public Builder clientMaxReconnectAttempts(int clientMaxReconnectAttempts) {
            config.setClientMaxReconnectAttempts(clientMaxReconnectAttempts);
            return this;
        }

        public Builder clientInitialReconnectDelayMs(int clientInitialReconnectDelayMs) {
            config.setClientInitialReconnectDelayMs(clientInitialReconnectDelayMs);
            return this;
        }

        public Builder clientMaxReconnectDelayMs(int clientMaxReconnectDelayMs) {
            config.setClientMaxReconnectDelayMs(clientMaxReconnectDelayMs);
            return this;
        }

        public Builder clientSocketTimeoutMs(int clientSocketTimeoutMs) {
            config.setClientSocketTimeoutMs(clientSocketTimeoutMs);
            return this;
        }

        public Builder clientProcessingLoopSleepMs(int clientProcessingLoopSleepMs) {
            config.setClientProcessingLoopSleepMs(clientProcessingLoopSleepMs);
            return this;
        }

        public Builder clientDisconnectNoticeDelayMs(int clientDisconnectNoticeDelayMs) {
            config.setClientDisconnectNoticeDelayMs(clientDisconnectNoticeDelayMs);
            return this;
        }

        public Builder reliablePacketTimeoutMs(int reliablePacketTimeoutMs) {
            config.setReliablePacketTimeoutMs(reliablePacketTimeoutMs);
            return this;
        }

        public Builder reliablePacketMaxRetries(int reliablePacketMaxRetries) {
            config.setReliablePacketMaxRetries(reliablePacketMaxRetries);
            return this;
        }

        public Builder maxNameLength(int maxNameLength) {
            config.setMaxNameLength(maxNameLength);
            return this;
        }

        public Builder maxDescriptionLength(int maxDescriptionLength) {
            config.setMaxDescriptionLength(maxDescriptionLength);
            return this;
        }

        public Builder maxPacketCount(int maxPacketCount) {
            config.setMaxPacketCount(maxPacketCount);
            return this;
        }

        public Builder maxPayloadSize(int maxPayloadSize) {
            config.setMaxPayloadSize(maxPayloadSize);
            return this;
        }

        /**
         * Builds and validates the NeonConfig instance.
         *
         * @return the configured NeonConfig instance
         * @throws IllegalArgumentException if any configuration value is invalid
         * @since 1.0
         */
        public NeonConfig build() {
            config.validate();
            return config;
        }
    }
}
