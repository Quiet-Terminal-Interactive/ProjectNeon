package com.quietterminal.neon.core;

final class PayloadSizeEnforcer {

    private PayloadSizeEnforcer() {
    }

    public static void enforce(PacketPayload payload, NeonConfig config) {
        int size = payload.toBytes().length;
        int max = config.getHostSessionMaxPacketSize();
        if (size > max) {
            throw new IllegalArgumentException(
                    "Payload size " + size + " exceeds configured limit " + max);
        }
    }
}
