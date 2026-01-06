/*
 * Project Neon - C/C++ Integration Header
 *
 * This header provides C-compatible bindings for integrating Project Neon
 * with game engines like Unreal Engine, Unity, or custom C/C++ engines.
 *
 * The implementation uses JNI to call into the Java-based Neon protocol library.
 */

#ifndef PROJECT_NEON_H
#define PROJECT_NEON_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle types */
typedef struct NeonClientHandle NeonClientHandle;
typedef struct NeonHostHandle NeonHostHandle;

/* Callback function pointer types */
typedef void (*PongCallback)(uint64_t response_time_ms, uint64_t original_timestamp);
typedef void (*SessionConfigCallback)(uint8_t version, uint16_t tick_rate, uint16_t max_packet_size);
typedef void (*PacketTypeRegistryCallback)(size_t count, const uint8_t* ids, const char** names, const char** descriptions);
typedef void (*UnhandledPacketCallback)(uint8_t packet_type, uint8_t from_client_id);
typedef void (*WrongDestinationCallback)(uint8_t my_id, uint8_t packet_destination_id);
typedef void (*ClientConnectCallback)(uint8_t client_id, const char* name, uint32_t session_id);
typedef void (*ClientDenyCallback)(const char* name, const char* reason);
typedef void (*PingReceivedCallback)(uint8_t from_client_id);
typedef void (*HostUnhandledPacketCallback)(uint8_t packet_type, uint8_t from_client_id);

/* ========== Client Functions ========== */

/**
 * Creates a new Neon client.
 *
 * @param name Player/client name
 * @return Pointer to client handle, or NULL on error
 */
NeonClientHandle* neon_client_new(const char* name);

/**
 * Connects the client to a relay server and joins a session.
 *
 * @param client Client handle
 * @param session_id Session ID to join
 * @param relay_addr Relay address in format "host:port" (e.g., "127.0.0.1:7777")
 * @return true if connected successfully, false otherwise
 */
bool neon_client_connect(NeonClientHandle* client, uint32_t session_id, const char* relay_addr);

/**
 * Processes incoming packets. Call this regularly in your game loop.
 *
 * @param client Client handle
 * @return Number of packets processed, or -1 on error
 */
int neon_client_process_packets(NeonClientHandle* client);

/**
 * Gets the assigned client ID.
 *
 * @param client Client handle
 * @return Client ID, or 0 if not connected
 */
uint8_t neon_client_get_id(NeonClientHandle* client);

/**
 * Gets the current session ID.
 *
 * @param client Client handle
 * @return Session ID, or 0 if not connected
 */
uint32_t neon_client_get_session_id(NeonClientHandle* client);

/**
 * Checks if the client is connected.
 *
 * @param client Client handle
 * @return true if connected, false otherwise
 */
bool neon_client_is_connected(NeonClientHandle* client);

/**
 * Sends a ping to the host.
 *
 * @param client Client handle
 * @return true if sent successfully, false otherwise
 */
bool neon_client_send_ping(NeonClientHandle* client);

/**
 * Enables or disables automatic pinging (enabled by default).
 *
 * @param client Client handle
 * @param enabled true to enable auto-ping, false to disable
 */
void neon_client_set_auto_ping(NeonClientHandle* client, bool enabled);

/* Callback setters */
void neon_client_set_pong_callback(NeonClientHandle* client, PongCallback callback);
void neon_client_set_session_config_callback(NeonClientHandle* client, SessionConfigCallback callback);
void neon_client_set_packet_type_registry_callback(NeonClientHandle* client, PacketTypeRegistryCallback callback);
void neon_client_set_unhandled_packet_callback(NeonClientHandle* client, UnhandledPacketCallback callback);
void neon_client_set_wrong_destination_callback(NeonClientHandle* client, WrongDestinationCallback callback);

/**
 * Frees the client and releases resources.
 *
 * @param client Client handle
 */
void neon_client_free(NeonClientHandle* client);

/* ========== Host Functions ========== */

/**
 * Creates a new Neon host.
 *
 * @param session_id Session ID for this host
 * @param relay_addr Relay address in format "host:port"
 * @return Pointer to host handle, or NULL on error
 */
NeonHostHandle* neon_host_new(uint32_t session_id, const char* relay_addr);

/**
 * Starts the host (blocking call - run in a separate thread).
 *
 * @param host Host handle
 * @return true if started successfully, false otherwise
 */
bool neon_host_start(NeonHostHandle* host);

/**
 * Processes incoming packets (alternative to start() for manual control).
 *
 * @param host Host handle
 * @return Number of packets processed, or -1 on error
 */
int neon_host_process_packets(NeonHostHandle* host);

/**
 * Gets the session ID.
 *
 * @param host Host handle
 * @return Session ID
 */
uint32_t neon_host_get_session_id(NeonHostHandle* host);

/**
 * Gets the number of connected clients.
 *
 * @param host Host handle
 * @return Client count
 */
size_t neon_host_get_client_count(NeonHostHandle* host);

/* Callback setters */
void neon_host_set_client_connect_callback(NeonHostHandle* host, ClientConnectCallback callback);
void neon_host_set_client_deny_callback(NeonHostHandle* host, ClientDenyCallback callback);
void neon_host_set_ping_received_callback(NeonHostHandle* host, PingReceivedCallback callback);
void neon_host_set_unhandled_packet_callback(NeonHostHandle* host, HostUnhandledPacketCallback callback);

/**
 * Frees the host and releases resources.
 *
 * @param host Host handle
 */
void neon_host_free(NeonHostHandle* host);

/* ========== Error Handling ========== */

/**
 * Gets the last error message.
 * Valid until next error or thread exit.
 *
 * @return Error message string, or NULL if no error
 */
const char* neon_get_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* PROJECT_NEON_H */
