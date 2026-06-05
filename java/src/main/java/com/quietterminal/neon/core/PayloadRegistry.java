package com.quietterminal.neon.core;

import java.util.concurrent.ConcurrentHashMap;

final class PayloadRegistry {

    private static final ConcurrentHashMap<Byte, PayloadDeserializer> registry = new ConcurrentHashMap<>();

    private PayloadRegistry() {
    }

    public static void register(byte packetType, PayloadDeserializer deserializer) {
        registry.put(packetType, deserializer);
    }

    public static PayloadDeserializer getDeserializer(byte packetType) {
        return registry.get(packetType);
    }
}
