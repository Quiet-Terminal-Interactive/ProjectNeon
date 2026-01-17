package com.quietterminal.projectneon.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializable session state for persistence and recovery.
 * Captures all necessary information to restore a session after restart.
 *
 * <p>Binary format (little-endian):
 * <pre>
 * [4 bytes] Magic: 0x4E53 5354 ("NSST")
 * [2 bytes] Format version
 * [8 bytes] Timestamp (epoch millis)
 * [4 bytes] Session ID
 * [8 bytes] Host token
 * [2 bytes] Client count
 * For each client:
 *   [1 byte]  Client ID
 *   [8 bytes] Client token
 *   [2 bytes] Name length
 *   [N bytes] Name (UTF-8)
 *   [8 bytes] Connected timestamp
 *   [1 byte]  Flags (bit 0 = isHost)
 * [4 bytes] Custom data length
 * [N bytes] Custom data
 * </pre>
 *
 * @since 1.1
 */
public final class SessionState {

    /**
     * Magic bytes for session state files.
     */
    public static final int MAGIC = 0x4E535354;

    /**
     * Current format version.
     */
    public static final short FORMAT_VERSION = 1;

    private final int sessionId;
    private final long hostToken;
    private final Instant savedAt;
    private final List<ClientState> clients;
    private final byte[] customData;

    private SessionState(Builder builder) {
        this.sessionId = builder.sessionId;
        this.hostToken = builder.hostToken;
        this.savedAt = builder.savedAt != null ? builder.savedAt : Instant.now();
        this.clients = new ArrayList<>(builder.clients);
        this.customData = builder.customData != null ? builder.customData : new byte[0];
    }

    /**
     * Creates a new session state builder.
     *
     * @param sessionId the session ID
     * @return the builder
     */
    public static Builder builder(int sessionId) {
        return new Builder(sessionId);
    }

    /**
     * Deserializes session state from bytes.
     *
     * @param data the serialized data
     * @return the session state
     * @throws IllegalArgumentException if data is invalid
     */
    public static SessionState fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return deserialize(buffer);
    }

    /**
     * Deserializes session state from an input stream.
     *
     * @param in the input stream
     * @return the session state
     * @throws IOException if reading fails
     */
    public static SessionState fromStream(InputStream in) throws IOException {
        byte[] header = new byte[18];
        int read = in.read(header);
        if (read != 18) {
            throw new IOException("Incomplete header");
        }

        ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int magic = headerBuf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic: " + Integer.toHexString(magic));
        }

        short version = headerBuf.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        byte[] remaining = in.readAllBytes();
        byte[] full = new byte[header.length + remaining.length];
        System.arraycopy(header, 0, full, 0, header.length);
        System.arraycopy(remaining, 0, full, header.length, remaining.length);

        return fromBytes(full);
    }

    private static SessionState deserialize(ByteBuffer buffer) {
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic: " + Integer.toHexString(magic));
        }

        short version = buffer.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        long timestamp = buffer.getLong();
        int sessionId = buffer.getInt();
        long hostToken = buffer.getLong();
        short clientCount = buffer.getShort();

        Builder builder = builder(sessionId)
            .hostToken(hostToken)
            .savedAt(Instant.ofEpochMilli(timestamp));

        for (int i = 0; i < clientCount; i++) {
            byte clientId = buffer.get();
            long clientToken = buffer.getLong();
            short nameLen = buffer.getShort();
            byte[] nameBytes = new byte[nameLen];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            long connectedAt = buffer.getLong();
            byte flags = buffer.get();
            boolean isHost = (flags & 0x01) != 0;

            builder.addClient(new ClientState(clientId, clientToken, name,
                Instant.ofEpochMilli(connectedAt), isHost));
        }

        int customLen = buffer.getInt();
        if (customLen > 0) {
            byte[] custom = new byte[customLen];
            buffer.get(custom);
            builder.customData(custom);
        }

        return builder.build();
    }

    /**
     * Serializes the session state to bytes.
     *
     * @return the serialized bytes
     */
    public byte[] toBytes() {
        int size = 4 + 2 + 8 + 4 + 8 + 2;
        for (ClientState client : clients) {
            size += 1 + 8 + 2 + client.name().getBytes(StandardCharsets.UTF_8).length + 8 + 1;
        }
        size += 4 + customData.length;

        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putLong(savedAt.toEpochMilli());
        buffer.putInt(sessionId);
        buffer.putLong(hostToken);
        buffer.putShort((short) clients.size());

        for (ClientState client : clients) {
            buffer.put(client.clientId());
            buffer.putLong(client.token());
            byte[] nameBytes = client.name().getBytes(StandardCharsets.UTF_8);
            buffer.putShort((short) nameBytes.length);
            buffer.put(nameBytes);
            buffer.putLong(client.connectedAt().toEpochMilli());
            byte flags = (byte) (client.isHost() ? 0x01 : 0x00);
            buffer.put(flags);
        }

        buffer.putInt(customData.length);
        buffer.put(customData);

        return buffer.array();
    }

    /**
     * Writes the session state to an output stream.
     *
     * @param out the output stream
     * @throws IOException if writing fails
     */
    public void toStream(OutputStream out) throws IOException {
        out.write(toBytes());
    }

    /**
     * Returns the session ID.
     *
     * @return the session ID
     */
    public int getSessionId() {
        return sessionId;
    }

    /**
     * Returns the host token.
     *
     * @return the host token
     */
    public long getHostToken() {
        return hostToken;
    }

    /**
     * Returns when this state was saved.
     *
     * @return the save timestamp
     */
    public Instant getSavedAt() {
        return savedAt;
    }

    /**
     * Returns the list of clients.
     *
     * @return unmodifiable list of clients
     */
    public List<ClientState> getClients() {
        return List.copyOf(clients);
    }

    /**
     * Returns the custom application data.
     *
     * @return the custom data bytes
     */
    public byte[] getCustomData() {
        return customData.clone();
    }

    /**
     * Looks up a client by ID.
     *
     * @param clientId the client ID
     * @return the client state or null
     */
    public ClientState getClient(byte clientId) {
        for (ClientState client : clients) {
            if (client.clientId() == clientId) {
                return client;
            }
        }
        return null;
    }

    /**
     * Converts clients to a map by ID.
     *
     * @return map of client ID to client state
     */
    public Map<Byte, ClientState> getClientsAsMap() {
        Map<Byte, ClientState> map = new HashMap<>();
        for (ClientState client : clients) {
            map.put(client.clientId(), client);
        }
        return map;
    }

    /**
     * State of a single client in the session.
     */
    public record ClientState(
        byte clientId,
        long token,
        String name,
        Instant connectedAt,
        boolean isHost
    ) {
        /**
         * Returns the age of this client connection.
         *
         * @return connection age
         */
        public java.time.Duration connectionAge() {
            return java.time.Duration.between(connectedAt, Instant.now());
        }
    }

    /**
     * Builder for session state.
     */
    public static final class Builder {
        private final int sessionId;
        private long hostToken;
        private Instant savedAt;
        private final List<ClientState> clients = new ArrayList<>();
        private byte[] customData;

        private Builder(int sessionId) {
            this.sessionId = sessionId;
        }

        /**
         * Sets the host token.
         *
         * @param token the host token
         * @return this builder
         */
        public Builder hostToken(long token) {
            this.hostToken = token;
            return this;
        }

        /**
         * Sets when the state was saved.
         *
         * @param instant the save time
         * @return this builder
         */
        public Builder savedAt(Instant instant) {
            this.savedAt = instant;
            return this;
        }

        /**
         * Adds a client to the state.
         *
         * @param client the client state
         * @return this builder
         */
        public Builder addClient(ClientState client) {
            if (client != null) {
                clients.add(client);
            }
            return this;
        }

        /**
         * Adds a client using individual parameters.
         *
         * @param clientId the client ID
         * @param token the client token
         * @param name the client name
         * @param connectedAt when the client connected
         * @param isHost whether this is the host
         * @return this builder
         */
        public Builder addClient(byte clientId, long token, String name,
                                 Instant connectedAt, boolean isHost) {
            return addClient(new ClientState(clientId, token, name, connectedAt, isHost));
        }

        /**
         * Sets custom application data.
         *
         * @param data the custom data
         * @return this builder
         */
        public Builder customData(byte[] data) {
            this.customData = data != null ? data.clone() : null;
            return this;
        }

        /**
         * Builds the session state.
         *
         * @return the session state
         */
        public SessionState build() {
            return new SessionState(this);
        }
    }
}
