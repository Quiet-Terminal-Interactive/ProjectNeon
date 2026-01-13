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

void on_client_connect(uint8_t client_id, const char* name, uint32_t session_id) {
    printf("[HOST] Client connected:\n");
    printf("  Client ID: %u\n", client_id);
    printf("  Name: %s\n", name);
    printf("  Session ID: %u\n", session_id);
}

void on_client_deny(const char* name, const char* reason) {
    printf("[HOST] Client denied:\n");
    printf("  Name: %s\n", name);
    printf("  Reason: %s\n", reason);
}

void on_ping_received(uint8_t from_client_id) {
    printf("[HOST] Ping received from client %u\n", from_client_id);
}

void on_unhandled_packet(uint8_t packet_type, uint8_t from_client_id) {
    printf("[HOST] Unhandled packet type %u from client %u\n", packet_type, from_client_id);
}

int main(int argc, char** argv) {
    const char* relay_addr = "127.0.0.1:7777";
    uint32_t session_id = 12345;

    if (argc > 1) {
        session_id = (uint32_t)atoi(argv[1]);
    }
    if (argc > 2) {
        relay_addr = argv[2];
    }

    printf("=== Neon Host JNI Example ===\n");
    printf("Session ID: %u\n", session_id);
    printf("Relay address: %s\n\n", relay_addr);

    NeonHostHandle* host = neon_host_new(session_id, relay_addr);
    if (host == NULL) {
        fprintf(stderr, "Failed to create host: %s\n", neon_get_last_error());
        return 1;
    }

    printf("Host created successfully\n");

    neon_host_set_client_connect_callback(host, on_client_connect);
    neon_host_set_client_deny_callback(host, on_client_deny);
    neon_host_set_ping_received_callback(host, on_ping_received);
    neon_host_set_unhandled_packet_callback(host, on_unhandled_packet);

    printf("Callbacks set\n");
    printf("Session ID: %u\n", neon_host_get_session_id(host));
    printf("Initial client count: %zu\n", neon_host_get_client_count(host));

    printf("\nRunning for 60 seconds (processing packets manually)...\n");
    printf("Note: In production, you would typically call neon_host_start() in a separate thread\n");
    printf("      instead of manually processing packets in a loop.\n\n");

    for (int i = 0; i < 60; i++) {
        sleep_ms(1000);

        int packets_processed = neon_host_process_packets(host);
        if (packets_processed > 0) {
            printf("Processed %d packets (iteration %d)\n", packets_processed, i + 1);
            printf("Current client count: %zu\n", neon_host_get_client_count(host));
        }
    }

    printf("\nCleaning up...\n");
    neon_host_free(host);
    printf("Host freed. Exiting.\n");

    return 0;
}
