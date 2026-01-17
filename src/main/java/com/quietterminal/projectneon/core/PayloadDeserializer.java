package com.quietterminal.projectneon.core;

/**
 * Functional interface for deserializing packet payloads from bytes.
 *
 * <p>Implementations should handle malformed input gracefully by throwing
 * {@link IllegalArgumentException} with a descriptive message.
 *
 * @param <T> The payload type this deserializer produces
 */
@FunctionalInterface
public interface PayloadDeserializer<T extends PacketPayload> {

    /**
     * Deserializes a payload from its byte representation.
     *
     * @param bytes The serialized payload bytes
     * @return The deserialized payload
     * @throws IllegalArgumentException if the bytes are malformed or invalid
     */
    T fromBytes(byte[] bytes);
}
