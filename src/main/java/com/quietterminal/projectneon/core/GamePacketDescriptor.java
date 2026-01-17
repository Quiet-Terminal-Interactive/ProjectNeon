package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Descriptor for a registered game packet type, providing metadata about
 * packet structure, versioning, and validation rules.
 *
 * <p>Each descriptor defines:
 * <ul>
 *   <li>Packet type byte (0x10-0xFF)</li>
 *   <li>Human-readable name and description</li>
 *   <li>Subtype range (for multiplexing within a single packet type)</li>
 *   <li>Version for schema evolution</li>
 *   <li>Optional validator for payload validation</li>
 *   <li>Minimum and maximum payload sizes</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * GamePacketDescriptor descriptor = GamePacketDescriptor.builder((byte) 0x20)
 *     .name("PlayerMovement")
 *     .description("Position and velocity update for a player")
 *     .version(1)
 *     .subtypeRange(0, 3)  // subtypes 0-3 for different movement types
 *     .minPayloadSize(12)
 *     .maxPayloadSize(28)
 *     .validator(bytes -> bytes.length >= 12 && bytes.length <= 28)
 *     .build();
 * }</pre>
 *
 * @since 1.1
 */
@PublicAPI
public final class GamePacketDescriptor {

    private final byte packetType;
    private final String name;
    private final String description;
    private final int version;
    private final int minSubtype;
    private final int maxSubtype;
    private final int minPayloadSize;
    private final int maxPayloadSize;
    private final Predicate<byte[]> validator;

    private GamePacketDescriptor(Builder builder) {
        this.packetType = builder.packetType;
        this.name = builder.name;
        this.description = builder.description;
        this.version = builder.version;
        this.minSubtype = builder.minSubtype;
        this.maxSubtype = builder.maxSubtype;
        this.minPayloadSize = builder.minPayloadSize;
        this.maxPayloadSize = builder.maxPayloadSize;
        this.validator = builder.validator;
    }

    /**
     * Creates a new builder for a game packet descriptor.
     *
     * @param packetType the packet type byte (must be >= 0x10)
     * @return a new builder instance
     * @throws IllegalArgumentException if packetType is in the reserved core range
     */
    public static Builder builder(byte packetType) {
        return new Builder(packetType);
    }

    public byte getPacketType() {
        return packetType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getVersion() {
        return version;
    }

    public int getMinSubtype() {
        return minSubtype;
    }

    public int getMaxSubtype() {
        return maxSubtype;
    }

    public int getMinPayloadSize() {
        return minPayloadSize;
    }

    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    /**
     * Checks if a subtype value is within the valid range for this packet type.
     *
     * @param subtype the subtype to check
     * @return true if the subtype is valid
     */
    public boolean isValidSubtype(int subtype) {
        return subtype >= minSubtype && subtype <= maxSubtype;
    }

    /**
     * Validates a payload against the registered constraints and custom validator.
     *
     * @param payload the payload bytes to validate
     * @return empty if valid, or an error message describing the validation failure
     */
    public Optional<String> validate(byte[] payload) {
        if (payload == null) {
            return Optional.of("Payload cannot be null");
        }
        if (payload.length < minPayloadSize) {
            return Optional.of(String.format(
                "Payload too small: %d bytes (minimum: %d)", payload.length, minPayloadSize));
        }
        if (maxPayloadSize > 0 && payload.length > maxPayloadSize) {
            return Optional.of(String.format(
                "Payload too large: %d bytes (maximum: %d)", payload.length, maxPayloadSize));
        }
        if (validator != null && !validator.test(payload)) {
            return Optional.of("Payload failed custom validation");
        }
        return Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GamePacketDescriptor that = (GamePacketDescriptor) o;
        return packetType == that.packetType && version == that.version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(packetType, version);
    }

    @Override
    public String toString() {
        return String.format(
            "GamePacketDescriptor[type=0x%02X, name=%s, version=%d, subtypes=%d-%d, size=%d-%d]",
            packetType & 0xFF, name, version, minSubtype, maxSubtype, minPayloadSize,
            maxPayloadSize > 0 ? maxPayloadSize : Integer.MAX_VALUE);
    }

    /**
     * Builder for creating GamePacketDescriptor instances.
     */
    @PublicAPI
    public static final class Builder {
        private final byte packetType;
        private String name = "Unknown";
        private String description = "";
        private int version = 1;
        private int minSubtype = 0;
        private int maxSubtype = 0;
        private int minPayloadSize = 0;
        private int maxPayloadSize = 0;
        private Predicate<byte[]> validator;

        private Builder(byte packetType) {
            if ((packetType & 0xFF) < 0x10) {
                throw new IllegalArgumentException(
                    "Game packet type must be >= 0x10, got: 0x" + Integer.toHexString(packetType & 0xFF));
            }
            this.packetType = packetType;
        }

        /**
         * Sets the human-readable name for this packet type.
         */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name cannot be null");
            return this;
        }

        /**
         * Sets the description for this packet type.
         */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description cannot be null");
            return this;
        }

        /**
         * Sets the schema version for this packet type.
         */
        public Builder version(int version) {
            if (version < 1) {
                throw new IllegalArgumentException("Version must be >= 1");
            }
            this.version = version;
            return this;
        }

        /**
         * Sets the valid subtype range for this packet type.
         * Subtypes allow multiplexing multiple message types within a single packet type.
         */
        public Builder subtypeRange(int min, int max) {
            if (min < 0 || max < min || max > 255) {
                throw new IllegalArgumentException(
                    String.format("Invalid subtype range: [%d, %d]", min, max));
            }
            this.minSubtype = min;
            this.maxSubtype = max;
            return this;
        }

        /**
         * Sets the minimum payload size in bytes.
         */
        public Builder minPayloadSize(int size) {
            if (size < 0) {
                throw new IllegalArgumentException("Minimum payload size cannot be negative");
            }
            this.minPayloadSize = size;
            return this;
        }

        /**
         * Sets the maximum payload size in bytes. Use 0 for no limit.
         */
        public Builder maxPayloadSize(int size) {
            if (size < 0) {
                throw new IllegalArgumentException("Maximum payload size cannot be negative");
            }
            this.maxPayloadSize = size;
            return this;
        }

        /**
         * Sets a custom validator for payload content.
         */
        public Builder validator(Predicate<byte[]> validator) {
            this.validator = validator;
            return this;
        }

        /**
         * Builds the GamePacketDescriptor.
         *
         * @throws IllegalStateException if minPayloadSize > maxPayloadSize (when maxPayloadSize > 0)
         */
        public GamePacketDescriptor build() {
            if (maxPayloadSize > 0 && minPayloadSize > maxPayloadSize) {
                throw new IllegalStateException(
                    String.format("minPayloadSize (%d) > maxPayloadSize (%d)", minPayloadSize, maxPayloadSize));
            }
            return new GamePacketDescriptor(this);
        }
    }
}
