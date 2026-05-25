package com.quietterminal.neon.relay;

import java.time.Instant;

record PendingConnection(int sessionId, String desiredName, Instant requestedAt) {
}
