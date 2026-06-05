package com.quietterminal.neon.core;

@FunctionalInterface
public interface PayloadDeserializer {
    PacketPayload fromBytes(byte[] data);
}
