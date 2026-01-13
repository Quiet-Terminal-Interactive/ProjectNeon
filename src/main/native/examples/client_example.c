#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include "../project_neon.h"

#ifdef _WIN32
#include <windows.h>
#define sleep_ms(ms) Sleep(ms)
#else
#include <unistd.h>
#define sleep_ms(ms) usleep((ms) * 1000)
#endif

void on_pong(uint64_t response_time_ms, uint64_t original_timestamp) {
    printf("[CLIENT] Received pong! Response time: %llu ms (timestamp: %llu)\n",
           (unsigned long long)response_time_ms,
           (unsigned long long)original_timestamp);
}

void on_session_config(uint8_t version, uint16_t tick_rate, uint16_t max_packet_size) {
    printf("[CLIENT] Session config received:\n");
    printf("  Version: %u\n", version);
    printf("  Tick rate: %u\n", tick_rate);
    printf("  Max packet size: %u\n", max_packet_size);
}

void on_packet_type_registry(size_t count, const uint8_t* ids, const char** names, const char** descriptions) {
    printf("[CLIENT] Packet type registry received (%zu types):\n", count);
    for (size_t i = 0; i < count; i++) {
        printf("  [%u] %s - %s\n", ids[i], names[i], descriptions[i]);
    }
}

void on_unhandled_packet(uint8_t packet_type, uint8_t from_client_id) {
    printf("[CLIENT] Unhandled packet type %u from client %u\n", packet_type, from_client_id);
}

void on_wrong_destination(uint8_t my_id, uint8_t packet_destination_id) {
    printf("[CLIENT] Received packet for wrong destination (my ID: %u, packet for: %u)\n",
           my_id, packet_destination_id);
}

int main(int argc, char** argv) {
    const char* relay_addr = "127.0.0.1:7777";
    uint32_t session_id = 12345;
    const char* client_name = "TestClient";

    if (argc > 1) {
        client_name = argv[1];
    }
    if (argc > 2) {
        session_id = (uint32_t)atoi(argv[2]);
    }
    if (argc > 3) {
        relay_addr = argv[3];
    }

    printf("=== Neon Client JNI Example ===\n");
    printf("Client name: %s\n", client_name);
    printf("Session ID: %u\n", session_id);
    printf("Relay address: %s\n\n", relay_addr);

    NeonClientHandle* client = neon_client_new(client_name);
    if (client == NULL) {
        fprintf(stderr, "Failed to create client: %s\n", neon_get_last_error());
        return 1;
    }

    printf("Client created successfully\n");

    neon_client_set_pong_callback(client, on_pong);
    neon_client_set_session_config_callback(client, on_session_config);
    neon_client_set_packet_type_registry_callback(client, on_packet_type_registry);
    neon_client_set_unhandled_packet_callback(client, on_unhandled_packet);
    neon_client_set_wrong_destination_callback(client, on_wrong_destination);

    printf("Connecting to relay...\n");
    if (!neon_client_connect(client, session_id, relay_addr)) {
        fprintf(stderr, "Failed to connect: %s\n", neon_get_last_error());
        neon_client_free(client);
        return 1;
    }

    printf("Connected! Waiting for connection confirmation...\n");
    sleep_ms(2000);

    int packets_processed = neon_client_process_packets(client);
    printf("Processed %d packets\n", packets_processed);

    if (neon_client_is_connected(client)) {
        printf("Client is connected!\n");
        printf("Client ID: %u\n", neon_client_get_id(client));
        printf("Session ID: %u\n", neon_client_get_session_id(client));
    } else {
        printf("Client is not connected\n");
    }

    printf("\nRunning for 30 seconds (processing packets)...\n");
    for (int i = 0; i < 30; i++) {
        sleep_ms(1000);

        packets_processed = neon_client_process_packets(client);
        if (packets_processed > 0) {
            printf("Processed %d packets (iteration %d)\n", packets_processed, i + 1);
        }

        if (i % 5 == 0 && i > 0) {
            printf("Sending manual ping...\n");
            neon_client_send_ping(client);
        }

        if (!neon_client_is_connected(client)) {
            printf("Client disconnected!\n");
            break;
        }
    }

    printf("\nCleaning up...\n");
    neon_client_free(client);
    printf("Client freed. Exiting.\n");

    return 0;
}
