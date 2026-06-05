package com.quietterminal.neon.core;

import javax.net.ssl.SSLContext;

/**
 * Immutable configuration for all Neon components.
 *
 * <p>
 * Obtain a default instance via {@link #defaults()}, or customise with
 * {@link #builder()}:
 * 
 * <pre>{@code
 * NeonConfig cfg = NeonConfig.builder()
 *         .relayPort(9001)
 *         .hostSessionTickRate((short) 30)
 *         .clientPingIntervalMs(2000)
 *         .build();
 * }</pre>
 *
 * <p>
 * One {@code NeonConfig} instance can be shared across {@link NeonSocket},
 * host, client,
 * and relay components.
 */
public final class NeonConfig {

    private final int bufferSize;
    private final int bufferPoolInitSize;
    private final int bufferPoolMaxSize;
    private final boolean enforceBufferSize;

    private final int relayPort;
    private final int relaySocketTimeoutMs;
    private final int relayCleanupIntervalMs;
    private final int relayClientTimeoutMs;
    private final int relayMainLoopSleepMs;
    private final int maxPendingConnections;
    private final int maxRateLimiters;

    private final int maxPacketsPerSecond;
    private final int maxClientsPerSession;
    private final int maxTotalConnections;

    private final int clientSocketTimeoutMs;
    private final int clientConnectionTimeoutMs;
    private final int clientPingIntervalMs;
    private final int clientInitialReconnectDelayMs;
    private final int clientMaxReconnectDelayMs;
    private final int clientMaxReconnectAttempts;
    private final int clientDisconnectNoticeDelayMs;
    private final int clientProcessingLoopSleepMs;

    private final int hostSocketTimeoutMs;
    private final int hostAckTimeoutMs;
    private final int hostMaxAckRetries;
    private final int hostSessionTokenTimeoutMs;
    private final int hostGracefulShutdownTimeoutMs;
    private final int hostProcessingLoopSleepMs;
    private final short hostSessionTickRate;
    private final short hostSessionMaxPacketSize;

    private final int reliablePacketTimeoutMs;
    private final int reliablePacketMaxRetries;

    private final SSLContext sslContext;

    private NeonConfig(Builder b) {
        this.bufferSize = b.bufferSize;
        this.bufferPoolInitSize = b.bufferPoolInitSize;
        this.bufferPoolMaxSize = b.bufferPoolMaxSize;
        this.enforceBufferSize = b.enforceBufferSize;
        this.relayPort = b.relayPort;
        this.relaySocketTimeoutMs = b.relaySocketTimeoutMs;
        this.relayCleanupIntervalMs = b.relayCleanupIntervalMs;
        this.relayClientTimeoutMs = b.relayClientTimeoutMs;
        this.relayMainLoopSleepMs = b.relayMainLoopSleepMs;
        this.maxPendingConnections = b.maxPendingConnections;
        this.maxRateLimiters = b.maxRateLimiters;
        this.maxPacketsPerSecond = b.maxPacketsPerSecond;
        this.maxClientsPerSession = b.maxClientsPerSession;
        this.maxTotalConnections = b.maxTotalConnections;
        this.clientSocketTimeoutMs = b.clientSocketTimeoutMs;
        this.clientConnectionTimeoutMs = b.clientConnectionTimeoutMs;
        this.clientPingIntervalMs = b.clientPingIntervalMs;
        this.clientInitialReconnectDelayMs = b.clientInitialReconnectDelayMs;
        this.clientMaxReconnectDelayMs = b.clientMaxReconnectDelayMs;
        this.clientMaxReconnectAttempts = b.clientMaxReconnectAttempts;
        this.clientDisconnectNoticeDelayMs = b.clientDisconnectNoticeDelayMs;
        this.clientProcessingLoopSleepMs = b.clientProcessingLoopSleepMs;
        this.hostSocketTimeoutMs = b.hostSocketTimeoutMs;
        this.hostAckTimeoutMs = b.hostAckTimeoutMs;
        this.hostMaxAckRetries = b.hostMaxAckRetries;
        this.hostSessionTokenTimeoutMs = b.hostSessionTokenTimeoutMs;
        this.hostGracefulShutdownTimeoutMs = b.hostGracefulShutdownTimeoutMs;
        this.hostProcessingLoopSleepMs = b.hostProcessingLoopSleepMs;
        this.hostSessionTickRate = b.hostSessionTickRate;
        this.hostSessionMaxPacketSize = b.hostSessionMaxPacketSize;
        this.reliablePacketTimeoutMs = b.reliablePacketTimeoutMs;
        this.reliablePacketMaxRetries = b.reliablePacketMaxRetries;
        this.sslContext = b.sslContext;
    }

    public static NeonConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferPoolInitSize() {
        return bufferPoolInitSize;
    }

    public int getBufferPoolMaxSize() {
        return bufferPoolMaxSize;
    }

    public boolean isEnforceBufferSize() {
        return enforceBufferSize;
    }

    public int getRelayPort() {
        return relayPort;
    }

    public int getRelaySocketTimeoutMs() {
        return relaySocketTimeoutMs;
    }

    public int getRelayCleanupIntervalMs() {
        return relayCleanupIntervalMs;
    }

    public int getRelayClientTimeoutMs() {
        return relayClientTimeoutMs;
    }

    public int getRelayMainLoopSleepMs() {
        return relayMainLoopSleepMs;
    }

    public int getMaxPendingConnections() {
        return maxPendingConnections;
    }

    public int getMaxRateLimiters() {
        return maxRateLimiters;
    }

    public int getMaxPacketsPerSecond() {
        return maxPacketsPerSecond;
    }

    public int getMaxClientsPerSession() {
        return maxClientsPerSession;
    }

    public int getMaxTotalConnections() {
        return maxTotalConnections;
    }

    public int getClientSocketTimeoutMs() {
        return clientSocketTimeoutMs;
    }

    public int getClientConnectionTimeoutMs() {
        return clientConnectionTimeoutMs;
    }

    public int getClientPingIntervalMs() {
        return clientPingIntervalMs;
    }

    public int getClientInitialReconnectDelayMs() {
        return clientInitialReconnectDelayMs;
    }

    public int getClientMaxReconnectDelayMs() {
        return clientMaxReconnectDelayMs;
    }

    public int getClientMaxReconnectAttempts() {
        return clientMaxReconnectAttempts;
    }

    public int getClientDisconnectNoticeDelayMs() {
        return clientDisconnectNoticeDelayMs;
    }

    public int getClientProcessingLoopSleepMs() {
        return clientProcessingLoopSleepMs;
    }

    public int getHostSocketTimeoutMs() {
        return hostSocketTimeoutMs;
    }

    public int getHostAckTimeoutMs() {
        return hostAckTimeoutMs;
    }

    public int getHostMaxAckRetries() {
        return hostMaxAckRetries;
    }

    public int getHostSessionTokenTimeoutMs() {
        return hostSessionTokenTimeoutMs;
    }

    public int getHostGracefulShutdownTimeoutMs() {
        return hostGracefulShutdownTimeoutMs;
    }

    public int getHostProcessingLoopSleepMs() {
        return hostProcessingLoopSleepMs;
    }

    public short getHostSessionTickRate() {
        return hostSessionTickRate;
    }

    public short getHostSessionMaxPacketSize() {
        return hostSessionMaxPacketSize;
    }

    public int getReliablePacketTimeoutMs() {
        return reliablePacketTimeoutMs;
    }

    public int getReliablePacketMaxRetries() {
        return reliablePacketMaxRetries;
    }

    /**
     * Returns the {@link SSLContext} configured for DTLS, or {@code null} if DTLS
     * is disabled.
     */
    public SSLContext getSslContext() {
        return sslContext;
    }

    /**
     * Returns {@code true} if a {@link SSLContext} has been configured, enabling
     * DTLS.
     */
    public boolean isDtlsEnabled() {
        return sslContext != null;
    }

    public static final class Builder {

        private int bufferSize = 65535;
        private int bufferPoolInitSize = 16;
        private int bufferPoolMaxSize = 64;
        private boolean enforceBufferSize = true;

        private int relayPort = 7777;
        private int relaySocketTimeoutMs = 100;
        private int relayCleanupIntervalMs = 5000;
        private int relayClientTimeoutMs = 15000;
        private int relayMainLoopSleepMs = 1;
        private int maxPendingConnections = 64;
        private int maxRateLimiters = 1024;

        private int maxPacketsPerSecond = 100;
        private int maxClientsPerSession = 32;
        private int maxTotalConnections = 1024;

        private int clientSocketTimeoutMs = 100;
        private int clientConnectionTimeoutMs = 5000;
        private int clientPingIntervalMs = 5000;
        private int clientInitialReconnectDelayMs = 1000;
        private int clientMaxReconnectDelayMs = 30000;
        private int clientMaxReconnectAttempts = 6;
        private int clientDisconnectNoticeDelayMs = 50;
        private int clientProcessingLoopSleepMs = 10;

        private int hostSocketTimeoutMs = 100;
        private int hostAckTimeoutMs = 2000;
        private int hostMaxAckRetries = 5;
        private int hostSessionTokenTimeoutMs = 300000;
        private int hostGracefulShutdownTimeoutMs = 3000;
        private int hostProcessingLoopSleepMs = 10;
        private short hostSessionTickRate = 60;
        private short hostSessionMaxPacketSize = 1200;

        private int reliablePacketTimeoutMs = 2000;
        private int reliablePacketMaxRetries = 5;

        private SSLContext sslContext = null;

        private Builder() {
        }

        public Builder bufferSize(int v) {
            this.bufferSize = v;
            return this;
        }

        public Builder bufferPoolInitSize(int v) {
            this.bufferPoolInitSize = v;
            return this;
        }

        public Builder bufferPoolMaxSize(int v) {
            this.bufferPoolMaxSize = v;
            return this;
        }

        public Builder enforceBufferSize(boolean v) {
            this.enforceBufferSize = v;
            return this;
        }

        public Builder relayPort(int v) {
            this.relayPort = v;
            return this;
        }

        public Builder relaySocketTimeoutMs(int v) {
            this.relaySocketTimeoutMs = v;
            return this;
        }

        public Builder relayCleanupIntervalMs(int v) {
            this.relayCleanupIntervalMs = v;
            return this;
        }

        public Builder relayClientTimeoutMs(int v) {
            this.relayClientTimeoutMs = v;
            return this;
        }

        public Builder relayMainLoopSleepMs(int v) {
            this.relayMainLoopSleepMs = v;
            return this;
        }

        public Builder maxPendingConnections(int v) {
            this.maxPendingConnections = v;
            return this;
        }

        public Builder maxRateLimiters(int v) {
            this.maxRateLimiters = v;
            return this;
        }

        public Builder maxPacketsPerSecond(int v) {
            this.maxPacketsPerSecond = v;
            return this;
        }

        public Builder maxClientsPerSession(int v) {
            this.maxClientsPerSession = v;
            return this;
        }

        public Builder maxTotalConnections(int v) {
            this.maxTotalConnections = v;
            return this;
        }

        public Builder clientSocketTimeoutMs(int v) {
            this.clientSocketTimeoutMs = v;
            return this;
        }

        public Builder clientConnectionTimeoutMs(int v) {
            this.clientConnectionTimeoutMs = v;
            return this;
        }

        public Builder clientPingIntervalMs(int v) {
            this.clientPingIntervalMs = v;
            return this;
        }

        public Builder clientInitialReconnectDelayMs(int v) {
            this.clientInitialReconnectDelayMs = v;
            return this;
        }

        public Builder clientMaxReconnectDelayMs(int v) {
            this.clientMaxReconnectDelayMs = v;
            return this;
        }

        public Builder clientMaxReconnectAttempts(int v) {
            this.clientMaxReconnectAttempts = v;
            return this;
        }

        public Builder clientDisconnectNoticeDelayMs(int v) {
            this.clientDisconnectNoticeDelayMs = v;
            return this;
        }

        public Builder clientProcessingLoopSleepMs(int v) {
            this.clientProcessingLoopSleepMs = v;
            return this;
        }

        public Builder hostSocketTimeoutMs(int v) {
            this.hostSocketTimeoutMs = v;
            return this;
        }

        public Builder hostAckTimeoutMs(int v) {
            this.hostAckTimeoutMs = v;
            return this;
        }

        public Builder hostMaxAckRetries(int v) {
            this.hostMaxAckRetries = v;
            return this;
        }

        public Builder hostSessionTokenTimeoutMs(int v) {
            this.hostSessionTokenTimeoutMs = v;
            return this;
        }

        public Builder hostGracefulShutdownTimeoutMs(int v) {
            this.hostGracefulShutdownTimeoutMs = v;
            return this;
        }

        public Builder hostProcessingLoopSleepMs(int v) {
            this.hostProcessingLoopSleepMs = v;
            return this;
        }

        public Builder hostSessionTickRate(short v) {
            this.hostSessionTickRate = v;
            return this;
        }

        public Builder hostSessionMaxPacketSize(short v) {
            this.hostSessionMaxPacketSize = v;
            return this;
        }

        public Builder reliablePacketTimeoutMs(int v) {
            this.reliablePacketTimeoutMs = v;
            return this;
        }

        public Builder reliablePacketMaxRetries(int v) {
            this.reliablePacketMaxRetries = v;
            return this;
        }

        /**
         * Enables DTLS encryption. The relay must use an {@link SSLContext} initialised
         * with a
         * {@link javax.net.ssl.KeyManager}; hosts and clients must use one initialised
         * with a
         * {@link javax.net.ssl.TrustManager} that trusts the relay certificate.
         * Use {@link DtlsConfig} to construct the context. Pass {@code null} to disable
         * DTLS.
         */
        public Builder sslContext(SSLContext ctx) {
            this.sslContext = ctx;
            return this;
        }

        public NeonConfig build() {
            validate();
            return new NeonConfig(this);
        }

        private void validate() {
            if (bufferSize < 8)
                throw new IllegalArgumentException("bufferSize must be >= 8");
            if (bufferPoolInitSize < 1)
                throw new IllegalArgumentException("bufferPoolInitSize must be >= 1");
            if (bufferPoolMaxSize < bufferPoolInitSize)
                throw new IllegalArgumentException("bufferPoolMaxSize must be >= bufferPoolInitSize");

            requireNonNegative(relaySocketTimeoutMs, "relaySocketTimeoutMs");
            requireNonNegative(relayCleanupIntervalMs, "relayCleanupIntervalMs");
            requireNonNegative(relayClientTimeoutMs, "relayClientTimeoutMs");
            requireNonNegative(relayMainLoopSleepMs, "relayMainLoopSleepMs");
            requirePositive(maxPendingConnections, "maxPendingConnections");
            requirePositive(maxRateLimiters, "maxRateLimiters");

            requirePositive(maxPacketsPerSecond, "maxPacketsPerSecond");
            requirePositive(maxClientsPerSession, "maxClientsPerSession");
            requirePositive(maxTotalConnections, "maxTotalConnections");

            requireNonNegative(clientSocketTimeoutMs, "clientSocketTimeoutMs");
            requireNonNegative(clientConnectionTimeoutMs, "clientConnectionTimeoutMs");
            requireNonNegative(clientPingIntervalMs, "clientPingIntervalMs");
            requireNonNegative(clientInitialReconnectDelayMs, "clientInitialReconnectDelayMs");
            requireNonNegative(clientMaxReconnectDelayMs, "clientMaxReconnectDelayMs");
            requireNonNegative(clientMaxReconnectAttempts, "clientMaxReconnectAttempts");
            requireNonNegative(clientDisconnectNoticeDelayMs, "clientDisconnectNoticeDelayMs");
            requireNonNegative(clientProcessingLoopSleepMs, "clientProcessingLoopSleepMs");

            requireNonNegative(hostSocketTimeoutMs, "hostSocketTimeoutMs");
            requireNonNegative(hostAckTimeoutMs, "hostAckTimeoutMs");
            requireNonNegative(hostMaxAckRetries, "hostMaxAckRetries");
            requireNonNegative(hostSessionTokenTimeoutMs, "hostSessionTokenTimeoutMs");
            requireNonNegative(hostGracefulShutdownTimeoutMs, "hostGracefulShutdownTimeoutMs");
            requireNonNegative(hostProcessingLoopSleepMs, "hostProcessingLoopSleepMs");
            if (hostSessionTickRate <= 0)
                throw new IllegalArgumentException("hostSessionTickRate must be > 0");
            if (hostSessionMaxPacketSize <= 0)
                throw new IllegalArgumentException("hostSessionMaxPacketSize must be > 0");

            requireNonNegative(reliablePacketTimeoutMs, "reliablePacketTimeoutMs");
            requireNonNegative(reliablePacketMaxRetries, "reliablePacketMaxRetries");
        }

        private static void requireNonNegative(int value, String name) {
            if (value < 0)
                throw new IllegalArgumentException(name + " must be >= 0");
        }

        private static void requirePositive(int value, String name) {
            if (value <= 0)
                throw new IllegalArgumentException(name + " must be > 0");
        }
    }
}
