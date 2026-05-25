package com.quietterminal.neon.relay;

import java.net.SocketAddress;
import java.time.Instant;

record PendingReconnect(int sessionId, byte clientId, SocketAddress newAddress, Instant requestedAt) {
}
