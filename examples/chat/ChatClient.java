package examples.chat;

import com.quietterminal.projectneon.client.NeonClient;
import com.quietterminal.projectneon.core.*;
import com.quietterminal.projectneon.exceptions.NeonException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple chat client example using Project Neon.
 * <p>
 * This demonstrates:
 * - Connecting to a session
 * - Sending chat messages
 * - Receiving and displaying messages from other users
 * - Handling server notifications
 *
 * Usage:
 *   java examples.chat.ChatClient [username] [sessionId] [relayAddress]
 *
 * @version 0.2.0
 */
public class ChatClient {
    private static final byte PACKET_CHAT_MESSAGE = (byte) 0x10;
    private static final byte PACKET_SERVER_MESSAGE = (byte) 0x11;
    private static final byte PACKET_USER_LIST = (byte) 0x12;

    private final NeonClient client;
    private final String username;
    private final List<String> connectedUsers = new ArrayList<>();
    private volatile boolean running = true;

    public ChatClient(String username) {
        this.username = username;
        this.client = new NeonClient(username);

        setupCallbacks();
    }

    private void setupCallbacks() {
        // Handle connection success
        client.setConnectedCallback(() -> {
            System.out.println("\n[SYSTEM] Connected to chat!");
            System.out.println("[SYSTEM] Your ID: " + client.getClientId().orElse((byte) 0));
            System.out.println("[SYSTEM] Type your message and press Enter to send.");
            System.out.println("[SYSTEM] Type '/quit' to exit.\n");
        });

        // Handle disconnection
        client.setDisconnectedCallback(() -> {
            System.out.println("\n[SYSTEM] Disconnected from chat.");
            running = false;
        });

        // Handle pong (latency info)
        client.setPongCallback((responseTime, timestamp) -> {
            // Silently track latency, only show on /ping command
        });

        // Handle incoming packets
        client.setPacketCallback((packet) -> {
            byte packetType = packet.header().packetType();

            if (packetType == PACKET_CHAT_MESSAGE) {
                handleChatMessage(packet);
            } else if (packetType == PACKET_SERVER_MESSAGE) {
                handleServerMessage(packet);
            } else if (packetType == PACKET_USER_LIST) {
                handleUserList(packet);
            }
        });

        // Handle errors
        client.setErrorCallback((exception) -> {
            System.err.println("\n[ERROR] " + exception.getMessage());
        });
    }

    private void handleChatMessage(NeonPacket packet) {
        PacketPayload.GamePacket gamePacket = (PacketPayload.GamePacket) packet.payload();
        byte[] data = gamePacket.data();

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Deserialize: [sender_name_length][sender_name][message]
        short nameLen = buffer.getShort();
        byte[] nameBytes = new byte[nameLen];
        buffer.get(nameBytes);
        String senderName = new String(nameBytes, StandardCharsets.UTF_8);

        byte[] msgBytes = new byte[buffer.remaining()];
        buffer.get(msgBytes);
        String message = new String(msgBytes, StandardCharsets.UTF_8);

        // Display message
        System.out.println(senderName + ": " + message);
    }

    private void handleServerMessage(NeonPacket packet) {
        PacketPayload.GamePacket gamePacket = (PacketPayload.GamePacket) packet.payload();
        byte[] data = gamePacket.data();

        String message = new String(data, StandardCharsets.UTF_8);
        System.out.println("[SERVER] " + message);
    }

    private void handleUserList(NeonPacket packet) {
        PacketPayload.GamePacket gamePacket = (PacketPayload.GamePacket) packet.payload();
        byte[] data = gamePacket.data();

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Deserialize: [user_count][name1_len][name1][name2_len][name2]...
        byte userCount = buffer.get();
        connectedUsers.clear();

        for (int i = 0; i < userCount; i++) {
            byte nameLen = buffer.get();
            byte[] nameBytes = new byte[nameLen];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            connectedUsers.add(name);
        }

        System.out.println("\n[USERS] Online users (" + userCount + "):");
        for (String name : connectedUsers) {
            System.out.println("  - " + name);
        }
        System.out.println();
    }

    public boolean connect(int sessionId, String relayAddress) {
        System.out.println("=== Project Neon Chat Client ===");
        System.out.println("Username: " + username);
        System.out.println("Connecting to session " + sessionId + " via " + relayAddress + "...");

        try {
            return client.connect(sessionId, relayAddress);
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            return false;
        }
    }

    public void sendMessage(String message) {
        if (!client.isConnected()) {
            System.err.println("[ERROR] Not connected to server");
            return;
        }

        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);

        NeonPacket packet = NeonPacket.create(
            PACKET_CHAT_MESSAGE,
            (short) 0,
            client.getClientId().orElse((byte) 0),
            (byte) 0,  // Broadcast
            new PacketPayload.GamePacket(msgBytes)
        );

        try {
            client.sendPacket(packet);
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to send message: " + e.getMessage());
        }
    }

    public void run() {
        // Packet processing thread
        Thread processingThread = new Thread(() -> {
            while (running && client.isConnected()) {
                client.processPackets();
                try {
                    Thread.sleep(10);  // 10ms polling
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        processingThread.setDaemon(true);
        processingThread.start();

        // Input thread (main thread)
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running && client.isConnected()) {
                String input = reader.readLine();

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                // Handle commands
                if (input.startsWith("/")) {
                    handleCommand(input.trim());
                } else {
                    // Send as chat message
                    sendMessage(input);
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Input error: " + e.getMessage());
        }

        // Cleanup
        stop();
    }

    private void handleCommand(String command) {
        if (command.equalsIgnoreCase("/quit") || command.equalsIgnoreCase("/exit")) {
            System.out.println("[SYSTEM] Disconnecting...");
            running = false;
        } else if (command.equalsIgnoreCase("/ping")) {
            client.sendPing();
            System.out.println("[SYSTEM] Ping sent (check server logs for latency)");
        } else if (command.equalsIgnoreCase("/help")) {
            System.out.println("\n=== Chat Commands ===");
            System.out.println("/help  - Show this help message");
            System.out.println("/ping  - Check connection latency");
            System.out.println("/quit  - Exit chat");
            System.out.println();
        } else {
            System.out.println("[SYSTEM] Unknown command: " + command);
            System.out.println("[SYSTEM] Type /help for available commands");
        }
    }

    public void stop() {
        running = false;
        try {
            if (client.isConnected()) {
                client.close();
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Error during shutdown: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        String username = "User" + System.currentTimeMillis() % 1000;
        int sessionId = 12345;
        String relayAddress = "127.0.0.1:7777";

        // Parse command line arguments
        if (args.length > 0) {
            username = args[0];
        }
        if (args.length > 1) {
            try {
                sessionId = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid session ID: " + args[1]);
                System.exit(1);
            }
        }
        if (args.length > 2) {
            relayAddress = args[2];
        }

        ChatClient chatClient = new ChatClient(username);

        // Shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            chatClient.stop();
        }));

        // Connect and run
        if (chatClient.connect(sessionId, relayAddress)) {
            chatClient.run();
        } else {
            System.err.println("Failed to connect to chat server");
            System.exit(1);
        }
    }
}
