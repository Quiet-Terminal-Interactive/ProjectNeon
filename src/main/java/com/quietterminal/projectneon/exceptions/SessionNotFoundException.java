package com.quietterminal.projectneon.exceptions;

/**
 * Exception thrown when a session cannot be found in the relay,
 * typically when routing packets to non-existent sessions.
 */
public class SessionNotFoundException extends NeonException {
    private final Integer sessionId;

    public SessionNotFoundException(String message) {
        super(message);
        this.sessionId = null;
    }

    public SessionNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.sessionId = null;
    }

    public SessionNotFoundException(String message, Integer sessionId) {
        super(buildDetailedMessage(message, sessionId));
        this.sessionId = sessionId;
    }

    private static String buildDetailedMessage(String message, Integer sessionId) {
        if (sessionId != null) {
            return message + " [SessionID=" + sessionId + "]";
        }
        return message;
    }

    public Integer getSessionId() {
        return sessionId;
    }
}
