package com.quietterminal.projectneon.core;

import com.quietterminal.projectneon.PublicAPI;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for game packet types with subtype, version, and validation support.
 *
 * <p>This registry extends the basic {@link PayloadRegistry} with additional features:
 * <ul>
 *   <li>Packet descriptors with metadata (name, description, version)</li>
 *   <li>Subtype support for multiplexing within a single packet type</li>
 *   <li>Payload validation with size constraints</li>
 *   <li>Version tracking for schema evolution</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Create and register a game packet type
 * GamePacketDescriptor movementDescriptor = GamePacketDescriptor.builder((byte) 0x20)
 *     .name("PlayerMovement")
 *     .description("Position and velocity updates")
 *     .version(1)
 *     .subtypeRange(0, 2)  // 0=position, 1=velocity, 2=both
 *     .minPayloadSize(12)
 *     .maxPayloadSize(28)
 *     .build();
 *
 * GamePacketRegistry.register(movementDescriptor, PlayerMovement::fromBytes);
 *
 * // Validate before sending
 * Optional<String> error = GamePacketRegistry.validatePayload((byte) 0x20, payload);
 * if (error.isPresent()) {
 *     System.err.println("Invalid payload: " + error.get());
 * }
 * }</pre>
 *
 * @since 1.1
 */
@PublicAPI
public final class GamePacketRegistry {

    private static final Map<Byte, GamePacketDescriptor> descriptors = new ConcurrentHashMap<>();
    private static final Map<Byte, Map<Integer, PayloadDeserializer<?>>> versionedDeserializers = new ConcurrentHashMap<>();

    private GamePacketRegistry() {
        /* No instantiation */
    }

    /**
     * Registers a game packet type with its descriptor and deserializer.
     *
     * @param descriptor the packet descriptor with metadata and validation rules
     * @param deserializer the deserializer for this packet type
     * @throws IllegalArgumentException if the packet type is a reserved core type
     * @throws IllegalStateException if a descriptor is already registered for this type
     */
    public static void register(GamePacketDescriptor descriptor, PayloadDeserializer<?> deserializer) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        Objects.requireNonNull(deserializer, "deserializer cannot be null");

        byte packetType = descriptor.getPacketType();
        if ((packetType & 0xFF) < 0x10) {
            throw new IllegalArgumentException(
                "Cannot register game packet for core type 0x" + Integer.toHexString(packetType & 0xFF));
        }

        if (descriptors.containsKey(packetType)) {
            GamePacketDescriptor existing = descriptors.get(packetType);
            if (existing.getVersion() == descriptor.getVersion()) {
                throw new IllegalStateException(
                    "Descriptor already registered for packet type 0x" + Integer.toHexString(packetType & 0xFF) +
                    " version " + descriptor.getVersion());
            }
        }

        descriptors.put(packetType, descriptor);
        versionedDeserializers
            .computeIfAbsent(packetType, k -> new ConcurrentHashMap<>())
            .put(descriptor.getVersion(), deserializer);
        PayloadRegistry.register(packetType, deserializer);
    }

    /**
     * Registers a versioned deserializer for a packet type without replacing the descriptor.
     * Use this to support multiple versions of the same packet type.
     *
     * @param packetType the packet type byte
     * @param version the version of this deserializer
     * @param deserializer the deserializer for this version
     * @throws IllegalArgumentException if no descriptor is registered for this packet type
     */
    public static void registerVersion(byte packetType, int version, PayloadDeserializer<?> deserializer) {
        Objects.requireNonNull(deserializer, "deserializer cannot be null");

        if (!descriptors.containsKey(packetType)) {
            throw new IllegalArgumentException(
                "No descriptor registered for packet type 0x" + Integer.toHexString(packetType & 0xFF));
        }

        versionedDeserializers
            .computeIfAbsent(packetType, k -> new ConcurrentHashMap<>())
            .put(version, deserializer);
    }

    /**
     * Unregisters a game packet type and all its versions.
     *
     * @param packetType the packet type to unregister
     * @return true if the type was registered and removed
     */
    public static boolean unregister(byte packetType) {
        boolean removed = descriptors.remove(packetType) != null;
        versionedDeserializers.remove(packetType);
        PayloadRegistry.unregister(packetType);
        return removed;
    }

    /**
     * Gets the descriptor for a registered packet type.
     *
     * @param packetType the packet type byte
     * @return the descriptor, or empty if not registered
     */
    public static Optional<GamePacketDescriptor> getDescriptor(byte packetType) {
        return Optional.ofNullable(descriptors.get(packetType));
    }

    /**
     * Gets a deserializer for a specific version of a packet type.
     *
     * @param packetType the packet type byte
     * @param version the version to get
     * @return the deserializer, or empty if not registered
     */
    public static Optional<PayloadDeserializer<?>> getDeserializer(byte packetType, int version) {
        Map<Integer, PayloadDeserializer<?>> versions = versionedDeserializers.get(packetType);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(version));
    }

    /**
     * Gets all registered versions for a packet type.
     *
     * @param packetType the packet type byte
     * @return set of registered versions, or empty set if type not registered
     */
    public static Set<Integer> getRegisteredVersions(byte packetType) {
        Map<Integer, PayloadDeserializer<?>> versions = versionedDeserializers.get(packetType);
        if (versions == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(versions.keySet());
    }

    /**
     * Validates a payload against the registered descriptor for a packet type.
     *
     * @param packetType the packet type byte
     * @param payload the payload bytes to validate
     * @return empty if valid, or an error message if validation failed
     */
    public static Optional<String> validatePayload(byte packetType, byte[] payload) {
        GamePacketDescriptor descriptor = descriptors.get(packetType);
        if (descriptor == null) {
            return Optional.of("No descriptor registered for packet type 0x" +
                Integer.toHexString(packetType & 0xFF));
        }
        return descriptor.validate(payload);
    }

    /**
     * Validates a payload with subtype checking.
     *
     * @param packetType the packet type byte
     * @param subtype the subtype value
     * @param payload the payload bytes to validate
     * @return empty if valid, or an error message if validation failed
     */
    public static Optional<String> validatePayload(byte packetType, int subtype, byte[] payload) {
        GamePacketDescriptor descriptor = descriptors.get(packetType);
        if (descriptor == null) {
            return Optional.of("No descriptor registered for packet type 0x" +
                Integer.toHexString(packetType & 0xFF));
        }
        if (!descriptor.isValidSubtype(subtype)) {
            return Optional.of(String.format(
                "Invalid subtype %d for packet type 0x%02X (valid range: %d-%d)",
                subtype, packetType & 0xFF, descriptor.getMinSubtype(), descriptor.getMaxSubtype()));
        }
        return descriptor.validate(payload);
    }

    /**
     * Checks if a packet type is registered.
     *
     * @param packetType the packet type byte
     * @return true if registered
     */
    public static boolean isRegistered(byte packetType) {
        return descriptors.containsKey(packetType);
    }

    /**
     * Gets all registered packet types.
     *
     * @return unmodifiable set of registered packet type bytes
     */
    public static Set<Byte> getRegisteredTypes() {
        return Collections.unmodifiableSet(descriptors.keySet());
    }

    /**
     * Gets all registered descriptors.
     *
     * @return unmodifiable collection of all descriptors
     */
    public static Collection<GamePacketDescriptor> getAllDescriptors() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    /**
     * Clears all registered game packet types.
     */
    public static void clearAll() {
        descriptors.clear();
        versionedDeserializers.clear();
        PayloadRegistry.clearAll();
    }

    /**
     * Gets the number of registered game packet types.
     *
     * @return count of registered types
     */
    public static int registeredCount() {
        return descriptors.size();
    }
}
