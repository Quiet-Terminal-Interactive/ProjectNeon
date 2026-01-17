package com.quietterminal.projectneon.core;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for packet payload deserializers.
 *
 * <p>This registry allows custom payload types to be registered and used alongside
 * the built-in core payload types. Custom payloads enable game-specific packet formats
 * without modifying the core protocol implementation.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Define a custom payload
 * public record PlayerPosition(float x, float y, float z) implements PacketPayload {
 *     public byte[] toBytes() {
 *         ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
 *         buf.putFloat(x).putFloat(y).putFloat(z);
 *         return buf.array();
 *     }
 *
 *     public static PlayerPosition fromBytes(byte[] bytes) {
 *         ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
 *         return new PlayerPosition(buf.getFloat(), buf.getFloat(), buf.getFloat());
 *     }
 * }
 *
 * // Register it
 * PayloadRegistry.register((byte) 0x20, PlayerPosition::fromBytes);
 * }</pre>
 */
public final class PayloadRegistry {

    private static final Map<Byte, PayloadDeserializer<?>> customDeserializers = new ConcurrentHashMap<>();

    private PayloadRegistry() {
        /* No instantiation */
    }

    /**
     * Registers a custom payload deserializer for a packet type.
     *
     * @param packetType The packet type byte (should be >= 0x10 for game packets)
     * @param deserializer The deserializer function
     * @throws IllegalArgumentException if the packet type is a reserved core type (< 0x10)
     */
    public static void register(byte packetType, PayloadDeserializer<?> deserializer) {
        if ((packetType & 0xFF) < 0x10) {
            throw new IllegalArgumentException(
                "Cannot register custom deserializer for core packet type 0x" +
                Integer.toHexString(packetType & 0xFF) + " (must be >= 0x10)");
        }
        customDeserializers.put(packetType, deserializer);
    }

    /**
     * Unregisters a custom payload deserializer.
     *
     * @param packetType The packet type to unregister
     * @return true if a deserializer was removed, false if none was registered
     */
    public static boolean unregister(byte packetType) {
        return customDeserializers.remove(packetType) != null;
    }

    /**
     * Gets the deserializer for a packet type if one is registered.
     *
     * @param packetType The packet type byte
     * @return The deserializer, or empty if not registered
     */
    public static Optional<PayloadDeserializer<?>> getDeserializer(byte packetType) {
        return Optional.ofNullable(customDeserializers.get(packetType));
    }

    /**
     * Checks if a custom deserializer is registered for a packet type.
     *
     * @param packetType The packet type byte
     * @return true if a custom deserializer is registered
     */
    public static boolean isRegistered(byte packetType) {
        return customDeserializers.containsKey(packetType);
    }

    /**
     * Clears all registered custom deserializers.
     */
    public static void clearAll() {
        customDeserializers.clear();
    }

    /**
     * Gets the number of registered custom deserializers.
     *
     * @return the count of registered deserializers
     */
    public static int registeredCount() {
        return customDeserializers.size();
    }
}
