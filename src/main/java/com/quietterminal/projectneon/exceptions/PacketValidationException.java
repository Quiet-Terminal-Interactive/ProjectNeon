package com.quietterminal.projectneon.exceptions;

/**
 * Exception thrown when packet validation fails due to invalid data,
 * malformed packets, or protocol violations.
 */
public class PacketValidationException extends NeonException {
    private final String packetType;
    private final Integer sessionId;
    private final Integer clientId;

    public PacketValidationException(String message) {
        super(message);
        this.packetType = null;
        this.sessionId = null;
        this.clientId = null;
    }

    public PacketValidationException(String message, Throwable cause) {
        super(message, cause);
        this.packetType = null;
        this.sessionId = null;
        this.clientId = null;
    }

    public PacketValidationException(String message, String packetType, Integer sessionId, Integer clientId) {
        super(buildDetailedMessage(message, packetType, sessionId, clientId));
        this.packetType = packetType;
        this.sessionId = sessionId;
        this.clientId = clientId;
    }

    public PacketValidationException(String message, String packetType, Integer sessionId, Integer clientId, Throwable cause) {
        super(buildDetailedMessage(message, packetType, sessionId, clientId), cause);
        this.packetType = packetType;
        this.sessionId = sessionId;
        this.clientId = clientId;
    }

    private static String buildDetailedMessage(String message, String packetType, Integer sessionId, Integer clientId) {
        StringBuilder sb = new StringBuilder(message);
        if (packetType != null) {
            sb.append(" [PacketType=").append(packetType).append("]");
        }
        if (sessionId != null) {
            sb.append(" [SessionID=").append(sessionId).append("]");
        }
        if (clientId != null) {
            sb.append(" [ClientID=").append(clientId).append("]");
        }
        return sb.toString();
    }

    public String getPacketType() {
        return packetType;
    }

    public Integer getSessionId() {
        return sessionId;
    }

    public Integer getClientId() {
        return clientId;
    }
}
