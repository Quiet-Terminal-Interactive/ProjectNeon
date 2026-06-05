package com.quietterminal.neon.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of application-defined game packet types.
 *
 * <p>Registered types are advertised to connecting clients via a {@link PacketPayload.PacketTypeRegistry}
 * and their payloads are deserialized using the supplied {@link PayloadDeserializer}.
 */
public final class GamePacketRegistry {

    private final List<GamePacketDescriptor> descriptors = new ArrayList<>();

    /**
     * Registers a game packet type.
     *
     * @param packetId     unique type byte ({@code >= 0x10})
     * @param name         short human-readable name
     * @param description  longer description for debugging and tooling
     * @param deserializer factory that reconstructs the payload from raw bytes
     */
    public void register(byte packetId, String name, String description, PayloadDeserializer deserializer) {
        descriptors.add(new GamePacketDescriptor(packetId, name, description));
        PayloadRegistry.register(packetId, deserializer);
    }

    public PacketPayload.PacketTypeRegistry buildRegistry() {
        List<PacketPayload.PacketTypeEntry> entries = new ArrayList<>();
        for (GamePacketDescriptor d : descriptors) {
            entries.add(new PacketPayload.PacketTypeEntry(d.packetId(), d.name(), d.description()));
        }
        return new PacketPayload.PacketTypeRegistry(entries);
    }

    public boolean isEmpty() {
        return descriptors.isEmpty();
    }
}
