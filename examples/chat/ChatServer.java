package examples.chat;

import com.quietterminal.projectneon.host.NeonHost;
import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.exceptions.NeonException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple chat server example using Project Neon.
 * <p>
 * This demonstrates:
 * - Hosting a session
 * - Accepting client connections
 * - Broadcasting chat messages to all clients
 * - Handling disconnections
 *
 * Usage:
 *   java examples.chat.ChatServer [sessionId]
 *
 * @version 0.2.0
 */
public class ChatServer {
    private static final byte PACKET_CHAT_MESSAGE = (byte) 0x10;
    private static final byte PACKET_SERVER_MESSAGE = (byte) 0x11;
    private static final byte PACKET_USER_LIST = (byte) 0x12;

    private final NeonHost host;
    private final Map<Byte, String> connectedUsers = new ConcurrentHashMap<>();
    private final int sessionId;

    public ChatServer(int sessionId, String relayAddress) throws NeonException {
        this.sessionId = sessionId;
        this.host = new NeonHost(sessionId, relayAddress);

        setupCallbacks();
    }

    private void setupCallbacks() {
        // Handle new client connections
        host.setClientConnectCallback((clientId, name, session) -> {
            System.out.println("[SERVER] Client connected: " + name + " (ID: " + clientId + ")");
            connectedUsers.put(clientId, name);

            // Send welcome message to new client
            String welcome = "Welcome to the chat, " + name + "!";
            sendServerMessage(clientId, welcome);

            // Broadcast join notification to everyone
            String joinMsg = name + " has joined the chat";
            broadcastServerMessage(joinMsg);

            // Send user list to new client
            sendUserList(clientId);
        });

        // Handle client disconnections
        host.setClientDisconnectCallback((clientId) -> {
            String name = connectedUsers.remove(clientId);
            if (name != null) {
                System.out.println("[SERVER] Client disconnected: " + name + " (ID: " + clientId + ")");

                // Broadcast leave notification
                String leaveMsg = name + " has left the chat";
                broadcastServerMessage(leaveMsg);
            }
        });

        // Handle incoming game packets (chat messages)
        host.setPacketCallback((packet) -> {
            byte packetType = packet.header().packetType();
            byte senderId = packet.header().clientId();

            if (packetType == PACKET_CHAT_MESSAGE) {
                handleChatMessage(packet, senderId);
            }
        });
    }

    private void handleChatMessage(NeonPacket packet, byte senderId) {
        PacketPayload.GamePacket gamePacket = (PacketPayload.GamePacket) packet.payload();
        byte[] data = gamePacket.data();

        // Deserialize chat message
        String message = new String(data, StandardCharsets.UTF_8);
        String senderName = connectedUsers.get(senderId);

        if (senderName == null) {
            System.err.println("[SERVER] Received message from unknown client: " + senderId);
            return;
        }

        System.out.println("[CHAT] " + senderName + ": " + message);

        // Broadcast message to all clients (including sender for echo)
        broadcastChatMessage(senderName, message);
    }

    private void broadcastChatMessage(String senderName, String message) {
        // Format: [sender_name_length][sender_name][message]
        byte[] nameBytes = senderName.getBytes(StandardCharsets.UTF_8);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(2 + nameBytes.length + msgBytes.length);
        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        buffer.put(msgBytes);

        byte[] payload = buffer.array();

        // Broadcast to all clients (destination 0)
        NeonPacket packet = NeonPacket.create(
            PACKET_CHAT_MESSAGE,
            (short) 0,
            (byte) 1,  // From host
            (byte) 0,  // Broadcast
            new PacketPayload.GamePacket(payload)
        );

        try {
            host.sendPacket(packet);
        } catch (Exception e) {
            System.err.println("[SERVER] Failed to broadcast chat message: " + e.getMessage());
        }
    }

    private void sendServerMessage(byte clientId, String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);

        NeonPacket packet = NeonPacket.create(
            PACKET_SERVER_MESSAGE,
            (short) 0,
            (byte) 1,  // From host
            clientId,  // To specific client
            new PacketPayload.GamePacket(msgBytes)
        );

        try {
            host.sendPacket(packet);
        } catch (Exception e) {
            System.err.println("[SERVER] Failed to send server message: " + e.getMessage());
        }
    }

    private void broadcastServerMessage(String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);

        NeonPacket packet = NeonPacket.create(
            PACKET_SERVER_MESSAGE,
            (short) 0,
            (byte) 1,  // From host
            (byte) 0,  // Broadcast
            new PacketPayload.GamePacket(msgBytes)
        );

        try {
            host.sendPacket(packet);
        } catch (Exception e) {
            System.err.println("[SERVER] Failed to broadcast server message: " + e.getMessage());
        }
    }

    private void sendUserList(byte clientId) {
        // Format: [user_count][name1_len][name1][name2_len][name2]...
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.put((byte) connectedUsers.size());

        for (String name : connectedUsers.values()) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            buffer.put((byte) nameBytes.length);
            buffer.put(nameBytes);
        }

        buffer.flip();
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        NeonPacket packet = NeonPacket.create(
            PACKET_USER_LIST,
            (short) 0,
            (byte) 1,  // From host
            clientId,  // To specific client
            new PacketPayload.GamePacket(payload)
        );

        try {
            host.sendPacket(packet);
        } catch (Exception e) {
            System.err.println("[SERVER] Failed to send user list: " + e.getMessage());
        }
    }

    public void start() {
        System.out.println("=== Project Neon Chat Server ===");
        System.out.println("Session ID: " + sessionId);
        System.out.println("Starting server...");
        System.out.println();

        try {
            // Start host (blocking - runs until interrupted)
            host.start();
        } catch (Exception e) {
            System.err.println("[ERROR] Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        System.out.println("\n[SERVER] Shutting down...");
        try {
            host.close();
        } catch (Exception e) {
            System.err.println("[ERROR] Error during shutdown: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        int sessionId = 12345;  // Default session ID

        if (args.length > 0) {
            try {
                sessionId = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid session ID: " + args[0]);
                System.err.println("Usage: java examples.chat.ChatServer [sessionId]");
                System.exit(1);
            }
        }

        String relayAddress = "127.0.0.1:7777";

        ChatServer server = null;
        try {
            server = new ChatServer(sessionId, relayAddress);

            // Shutdown hook for graceful cleanup
            final ChatServer finalServer = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                finalServer.stop();
            }));

            server.start();
        } catch (Exception e) {
            System.err.println("Failed to start chat server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
