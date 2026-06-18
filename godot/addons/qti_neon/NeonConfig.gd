## Shared configuration for [NeonClient], [NeonHost], and [NeonRelay].
##
## Create an instance and pass it to the constructor of whichever class you are configuring.
## All timing values are in milliseconds. Properties not relevant to a given class are ignored.
class_name NeonConfig
extends RefCounted

## UDP receive buffer size in bytes.
var buffer_size: int = 65535
## Initial number of buffer objects to allocate in the pool.
var buffer_pool_init_size: int = 16
## Maximum number of buffer objects to keep in the pool.
var buffer_pool_max_size: int = 64
## If [code]true[/code], packets exactly equal to [member buffer_size] are dropped as likely truncated.
var enforce_buffer_size: bool = true

## Port the relay binds on.
var relay_port: int = 7777
## Interval between relay cleanup passes (ms).
var relay_cleanup_interval_ms: int = 5000
## Time after which an idle relay peer is considered stale and evicted (ms).
var relay_client_timeout_ms: int = 15000
## Sleep duration between relay processing iterations (ms).
var relay_main_loop_sleep_ms: int = 1
## Maximum number of client connections waiting for host acceptance.
var max_pending_connections: int = 64
## Maximum number of per-peer rate limiter entries before the table is cleared.
var max_rate_limiters: int = 1024
## Maximum packets per second accepted from a single peer before rate limiting kicks in.
var max_packets_per_second: int = 100
## Maximum number of connected clients per session.
var max_clients_per_session: int = 32
## Maximum total connected clients across all sessions.
var max_total_connections: int = 1024

## How long the host waits for a [code]SESSION_CONFIG[/code] ACK before retrying (ms).
var host_ack_timeout_ms: int = 2000
## Maximum number of [code]SESSION_CONFIG[/code] retransmission attempts per client.
var host_max_ack_retries: int = 5
## How long a reconnect token remains valid after a client disconnects (ms).
var host_session_token_timeout_ms: int = 300000
## How long [method NeonHost.stop] waits for pending ACKs before force-closing (ms).
var host_graceful_shutdown_timeout_ms: int = 3000
## Sleep duration between host processing iterations (ms).
var host_processing_loop_sleep_ms: int = 10
## Tick rate sent to clients in the session config packet (ticks per second).
var host_session_tick_rate: int = 60
## Maximum game packet payload size sent to clients in the session config (bytes).
var host_session_max_packet_size: int = 1200

## Timeout for the initial DTLS handshake and connect/reconnect handshake (ms).
var client_connection_timeout_ms: int = 5000
## Interval between automatic ping packets sent by the client (ms).
var client_ping_interval_ms: int = 5000
## Initial delay before the first reconnect attempt (ms).
var client_initial_reconnect_delay_ms: int = 1000
## Maximum delay between reconnect attempts after exponential backoff (ms).
var client_max_reconnect_delay_ms: int = 30000
## Maximum number of reconnect attempts before giving up.
var client_max_reconnect_attempts: int = 6
## Sleep duration between client processing iterations (ms).
var client_processing_loop_sleep_ms: int = 10
## Delay between calling [method NeonClient.stop] and sending the disconnect notice (ms).
var client_disconnect_notice_delay_ms: int = 50

## Optional DTLS configuration. If [code]null[/code], plain UDP is used.
var dtls_config: DtlsConfig = null

## Timeout before retransmitting a reliable packet (ms). Used by [ReliablePacketManager].
var reliable_packet_timeout_ms: int = 2000
## Maximum retransmission attempts for a reliable packet before delivery failure is reported.
var reliable_packet_max_retries: int = 5

## Creates a config, optionally overriding specific keys.
##
## [param overrides] A [code]Dictionary[/code] mapping property names to values.
## Unknown keys are silently ignored.
func _init(overrides: Dictionary = {}) -> void:
	for key in overrides:
		if key in self:
			set(key, overrides[key])
