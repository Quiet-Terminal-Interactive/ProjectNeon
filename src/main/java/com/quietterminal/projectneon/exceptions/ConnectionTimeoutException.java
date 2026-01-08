package com.quietterminal.projectneon.exceptions;

/**
 * Exception thrown when a connection times out, such as when a client
 * fails to acknowledge packets or respond to ping messages.
 */
public class ConnectionTimeoutException extends NeonException {
    private final Integer sessionId;
    private final Integer clientId;
    private final long timeoutMillis;

    public ConnectionTimeoutException(String message) {
        super(message);
        this.sessionId = null;
        this.clientId = null;
        this.timeoutMillis = 0;
    }

    public ConnectionTimeoutException(String message, Throwable cause) {
        super(message, cause);
        this.sessionId = null;
        this.clientId = null;
        this.timeoutMillis = 0;
    }

    public ConnectionTimeoutException(String message, Integer sessionId, Integer clientId, long timeoutMillis) {
        super(buildDetailedMessage(message, sessionId, clientId, timeoutMillis));
        this.sessionId = sessionId;
        this.clientId = clientId;
        this.timeoutMillis = timeoutMillis;
    }

    private static String buildDetailedMessage(String message, Integer sessionId, Integer clientId, long timeoutMillis) {
        StringBuilder sb = new StringBuilder(message);
        if (sessionId != null) {
            sb.append(" [SessionID=").append(sessionId).append("]");
        }
        if (clientId != null) {
            sb.append(" [ClientID=").append(clientId).append("]");
        }
        if (timeoutMillis > 0) {
            sb.append(" [Timeout=").append(timeoutMillis).append("ms]");
        }
        return sb.toString();
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public Integer getClientId() {
        return clientId;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}
