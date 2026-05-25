package com.quietterminal.neon.core;

/**
 * Descriptor for an application-defined game packet type.
 *
 * @param packetId    unique type byte ({@code >= 0x10})
 * @param name        short human-readable identifier
 * @param description longer description for debugging and tooling
 */
public record GamePacketDescriptor(byte packetId, String name, String description) {
}
