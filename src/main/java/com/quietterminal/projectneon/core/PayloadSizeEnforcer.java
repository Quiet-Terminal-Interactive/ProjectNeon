package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

import java.util.Optional;

/**
 * Enforces payload size constraints for packet validation.
 *
 * <p>Provides configurable enforcement of:
 * <ul>
 *   <li>Maximum payload size (default: 65507 bytes, UDP limit)</li>
 *   <li>Minimum payload size (default: 0 bytes)</li>
 *   <li>Per-packet-type size limits via {@link GamePacketRegistry}</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * PayloadSizeEnforcer enforcer = PayloadSizeEnforcer.create()
 *     .maxPayloadSize(1200)  // MTU-safe limit
 *     .strictMode(true);
 *
 * // Validate before sending
 * Optional<String> error = enforcer.validate(packetType, payload);
 * if (error.isPresent()) {
 *     throw new PayloadTooLargeException(error.get());
 * }
 *
 * // Or use with automatic truncation warning
 * PayloadSizeEnforcer.Result result = enforcer.enforce(packetType, payload);
 * if (result.wasTruncated()) {
 *     logger.warn("Payload truncated: " + result.truncationMessage());
 * }
 * }</pre>
 *
 * @since 1.1
 */
@PublicAPI
public final class PayloadSizeEnforcer {

    /**
     * Maximum UDP payload size (65535 - 20 IP header - 8 UDP header).
     */
    public static final int UDP_MAX_PAYLOAD = 65507;

    /**
     * Recommended MTU-safe payload size to avoid fragmentation.
     */
    public static final int MTU_SAFE_PAYLOAD = 1200;

    private int maxPayloadSize;
    private int minPayloadSize;
    private boolean strictMode;
    private boolean useRegistryLimits;

    private PayloadSizeEnforcer() {
        this.maxPayloadSize = UDP_MAX_PAYLOAD;
        this.minPayloadSize = 0;
        this.strictMode = false;
        this.useRegistryLimits = true;
    }

    /**
     * Creates a new PayloadSizeEnforcer with default settings.
     */
    public static PayloadSizeEnforcer create() {
        return new PayloadSizeEnforcer();
    }

    /**
     * Creates a PayloadSizeEnforcer from NeonConfig settings.
     */
    public static PayloadSizeEnforcer fromConfig(NeonConfig config) {
        return new PayloadSizeEnforcer()
            .maxPayloadSize(config.getMaxPayloadSize())
            .minPayloadSize(0);
    }

    /**
     * Sets the maximum allowed payload size.
     *
     * @param size maximum size in bytes (1 to 65507)
     * @return this enforcer for chaining
     */
    public PayloadSizeEnforcer maxPayloadSize(int size) {
        if (size < 1 || size > UDP_MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "maxPayloadSize must be between 1 and " + UDP_MAX_PAYLOAD + ", got: " + size);
        }
        this.maxPayloadSize = size;
        return this;
    }

    /**
     * Sets the minimum required payload size.
     *
     * @param size minimum size in bytes (0 or greater)
     * @return this enforcer for chaining
     */
    public PayloadSizeEnforcer minPayloadSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("minPayloadSize cannot be negative");
        }
        this.minPayloadSize = size;
        return this;
    }

    /**
     * Enables or disables strict mode.
     * In strict mode, any size violation throws an exception rather than returning an error.
     *
     * @param strict true to enable strict mode
     * @return this enforcer for chaining
     */
    public PayloadSizeEnforcer strictMode(boolean strict) {
        this.strictMode = strict;
        return this;
    }

    /**
     * Enables or disables checking per-type limits from GamePacketRegistry.
     *
     * @param use true to check registry limits
     * @return this enforcer for chaining
     */
    public PayloadSizeEnforcer useRegistryLimits(boolean use) {
        this.useRegistryLimits = use;
        return this;
    }

    /**
     * Validates a payload against size constraints.
     *
     * @param payload the payload bytes to validate
     * @return empty if valid, or an error message if size constraints are violated
     */
    public Optional<String> validate(byte[] payload) {
        return validate((byte) 0x10, payload);
    }

    /**
     * Validates a payload against size constraints for a specific packet type.
     *
     * @param packetType the packet type byte
     * @param payload the payload bytes to validate
     * @return empty if valid, or an error message if size constraints are violated
     * @throws PayloadSizeException in strict mode if validation fails
     */
    public Optional<String> validate(byte packetType, byte[] payload) {
        if (payload == null) {
            String msg = "Payload cannot be null";
            if (strictMode) {
                throw new PayloadSizeException(msg);
            }
            return Optional.of(msg);
        }

        int size = payload.length;

        if (size < minPayloadSize) {
            String msg = String.format(
                "Payload too small: %d bytes (minimum: %d)", size, minPayloadSize);
            if (strictMode) {
                throw new PayloadSizeException(msg);
            }
            return Optional.of(msg);
        }

        if (size > maxPayloadSize) {
            String msg = String.format(
                "Payload too large: %d bytes (maximum: %d)", size, maxPayloadSize);
            if (strictMode) {
                throw new PayloadSizeException(msg);
            }
            return Optional.of(msg);
        }

        if (useRegistryLimits && (packetType & 0xFF) >= 0x10) {
            Optional<String> registryError = GamePacketRegistry.validatePayload(packetType, payload);
            if (registryError.isPresent()) {
                if (strictMode) {
                    throw new PayloadSizeException(registryError.get());
                }
                return registryError;
            }
        }

        return Optional.empty();
    }

    /**
     * Enforces size constraints, potentially truncating oversized payloads.
     *
     * @param packetType the packet type byte
     * @param payload the payload bytes
     * @return enforcement result with potentially truncated payload
     */
    public Result enforce(byte packetType, byte[] payload) {
        if (payload == null) {
            return new Result(new byte[0], true, "Payload was null, replaced with empty array");
        }

        Optional<String> error = validate(packetType, payload);
        if (error.isEmpty()) {
            return new Result(payload, false, null);
        }

        if (payload.length > maxPayloadSize) {
            byte[] truncated = new byte[maxPayloadSize];
            System.arraycopy(payload, 0, truncated, 0, maxPayloadSize);
            return new Result(truncated, true, String.format(
                "Payload truncated from %d to %d bytes", payload.length, maxPayloadSize));
        }

        return new Result(payload, false, error.get());
    }

    /**
     * Checks if a payload size would be valid without creating a full error message.
     *
     * @param size the payload size to check
     * @return true if the size is within allowed limits
     */
    public boolean isValidSize(int size) {
        return size >= minPayloadSize && size <= maxPayloadSize;
    }

    /**
     * Gets the maximum allowed payload size.
     */
    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    /**
     * Gets the minimum required payload size.
     */
    public int getMinPayloadSize() {
        return minPayloadSize;
    }

    /**
     * Checks if strict mode is enabled.
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Result of payload size enforcement.
     */
    @PublicAPI
    public record Result(
        byte[] payload,
        boolean wasTruncated,
        String message
    ) {
        /**
         * Gets the payload length after enforcement.
         */
        public int size() {
            return payload.length;
        }

        /**
         * Checks if there was any issue (truncation or validation error).
         */
        public boolean hasIssue() {
            return message != null;
        }
    }

    /**
     * Exception thrown in strict mode when payload size validation fails.
     */
    @PublicAPI
    public static class PayloadSizeException extends RuntimeException {
        public PayloadSizeException(String message) {
            super(message);
        }
    }
}
