## Connects to a game session through a [NeonRelay] as a client.
##
## Create a [NeonClient], register callbacks, then call [method connect] to join a session.
## Call [method run] on a background thread to process incoming packets and maintain pings.
## Use [method send_packet] to send game-specific packets to other peers.
class_name NeonClient
extends RefCounted

const _Proto = preload("res://addons/qti_neon/_protocol.gd")
const _SockMod = preload("res://addons/qti_neon/_socket.gd")

var _player_name: String
var _config: NeonConfig

var _socket: _SockMod = null
var _running: bool = false
var _sequence: int = 0
var _mutex: Mutex = Mutex.new()

var _client_id: int = 0
var _session_id: int = 0
var _token: int = 0
var _relay_host: String = ""
var _relay_port: int = 0

var _last_ping: int = 0

## Called when the host sends a [code]SESSION_CONFIG[/code] packet.
## Receives a [code]Dictionary[/code] with [code]version[/code], [code]tick_rate[/code], and [code]max_packet_size[/code] keys.
var session_config_callback: Callable = Callable()

## Called when the host broadcasts the packet type registry.
## Receives an [code]Array[/code] of entry dictionaries, each with [code]packet_id[/code], [code]name[/code], and [code]description[/code].
var packet_registry_callback: Callable = Callable()

## Called for any received game packet not consumed by the client internally.
## [param type_byte] The packet type byte.
## [param sender] The client ID of the sender.
var unhandled_packet_callback: Callable = Callable()

## Called when a [code]DISCONNECT_NOTICE[/code] is received from the host.
var disconnect_callback: Callable = Callable()

## Creates a new client with the given player name.
## [param player_name] Sent to the host during the connect handshake.
## [param config] Optional configuration; defaults are used if [code]null[/code].
func _init(player_name: String, config: NeonConfig = null) -> void:
	_player_name = player_name
	_config = config if config != null else NeonConfig.new()

## Sets the callback invoked when session configuration is received.
## [param cb] A [code]Callable[/code] taking a [code]Dictionary[/code] of session parameters.
func set_session_config_callback(cb: Callable) -> void:
	session_config_callback = cb

## Sets the callback invoked when the packet type registry is received.
## [param cb] A [code]Callable[/code] taking an [code]Array[/code] of entry dictionaries.
func set_packet_registry_callback(cb: Callable) -> void:
	packet_registry_callback = cb

## Sets the callback invoked for unhandled game packets.
## [param cb] A [code]Callable[/code] taking [param type_byte] and [param sender] as integers.
func set_unhandled_packet_callback(cb: Callable) -> void:
	unhandled_packet_callback = cb

## Sets the callback invoked when the host disconnects this client.
## [param cb] A [code]Callable[/code] with no parameters.
func set_disconnect_callback(cb: Callable) -> void:
	disconnect_callback = cb

## Connects to a relay-hosted session.
##
## [param session_id] The session ID to join.
## [param relay_address] The relay address in [code]host:port[/code] format.
## Returns [code]true[/code] on success.
func join(session_id: int, relay_address: String) -> bool:
	var colon := relay_address.rfind(":")
	if colon == -1:
		push_error("NeonClient: invalid relay address '%s'" % relay_address)
		return false
	_relay_host = relay_address.left(colon)
	_relay_port = int(relay_address.substr(colon + 1))
	_session_id = session_id
	return _do_connect(session_id, false, 0, 0)

## Attempts to reconnect using the token issued during the original connection.
##
## Uses exponential backoff up to [code]NeonConfig.client_max_reconnect_attempts[/code].
## Returns [code]true[/code] if reconnection succeeded.
func reconnect() -> bool:
	if _token == 0 or _client_id == 0:
		return false
	var delay := _config.client_initial_reconnect_delay_ms
	for _attempt in _config.client_max_reconnect_attempts:
		if _do_connect(_session_id, true, _token, _client_id):
			return true
		OS.delay_msec(delay)
		delay = mini(delay * 2, _config.client_max_reconnect_delay_ms)
	return false


func _do_connect(session_id: int, is_reconnect: bool,
		token: int, prev_client_id: int) -> bool:
	if _socket != null:
		_socket.close()
	_socket = _SockMod.new()
	var err := _socket.open(_relay_host, _relay_port, _config.dtls_config)
	if err != OK:
		return false
	if not _socket.wait_for_handshake(_config.client_connection_timeout_ms):
		_socket.close()
		return false

	if is_reconnect:
		var payload := _Proto.build_reconnect_request(token, session_id, prev_client_id)
		var pkt := _Proto.build_packet(
			_Proto.PT_RECONNECT_REQUEST, _next_seq(), prev_client_id, 1, payload)
		_socket.send(pkt)
	else:
		var payload := _Proto.build_connect_request(1, _player_name, session_id, 0)
		var pkt := _Proto.build_packet(
			_Proto.PT_CONNECT_REQUEST, _next_seq(), 0, 1, payload)
		_socket.send(pkt)

	var deadline := Time.get_ticks_msec() + _config.client_connection_timeout_ms
	while Time.get_ticks_msec() < deadline:
		var data := _socket.receive()
		if data.size() >= _Proto.HEADER_SIZE:
			var hdr := _Proto.parse_header(data)
			if hdr.is_empty():
				OS.delay_msec(2)
				continue
			match hdr["type"]:
				_Proto.PT_CONNECT_ACCEPT:
					var pl := _Proto.parse_connect_accept(_Proto.payload_of(data))
					if pl.is_empty():
						return false
					_mutex.lock()
					_client_id = pl["client_id"]
					_session_id = pl["session_id"]
					_token = pl["token"]
					_mutex.unlock()
					return true
				_Proto.PT_CONNECT_DENY:
					var pl := _Proto.parse_connect_deny(_Proto.payload_of(data))
					push_warning("NeonClient: connection denied: %s" %
						(pl.get("reason", "unknown") if not pl.is_empty() else "unknown"))
					return false
		OS.delay_msec(2)
	return false

## Blocking packet processing loop. Call on a background thread after [method connect].
##
## Processes packets and sends keepalive pings until [method stop] is called.
func run() -> void:
	_running = true
	_last_ping = Time.get_ticks_msec()
	while _running:
		process_packets()
		_check_auto_ping()
		OS.delay_msec(_config.client_processing_loop_sleep_ms)
	_socket.close()

## Sends a disconnect notice and stops the run loop.
func stop() -> void:
	if not _running:
		return
	if _config.client_disconnect_notice_delay_ms > 0:
		OS.delay_msec(_config.client_disconnect_notice_delay_ms)
	_send_disconnect_notice()
	_running = false

## Returns [code]true[/code] if the run loop is active.
func is_running() -> bool:
	return _running

## The client ID assigned by the host after a successful connection.
var current_client_id: int:
	get: return _client_id

## Drains all pending packets from the socket without blocking.
##
## Called automatically by [method run]. Can also be called manually if you manage
## the loop yourself instead of calling [method run].
func process_packets() -> void:
	while true:
		var data := _socket.receive()
		if data.size() < _Proto.HEADER_SIZE:
			break
		_handle_packet(data)

func _check_auto_ping() -> void:
	var now := Time.get_ticks_msec()
	if now - _last_ping >= _config.client_ping_interval_ms:
		_last_ping = now
		var payload := _Proto.build_ping(now)
		var pkt := _Proto.build_packet(
			_Proto.PT_PING, _next_seq(), _client_id, 1, payload)
		_socket.send(pkt)

func _handle_packet(data: PackedByteArray) -> void:
	var hdr := _Proto.parse_header(data)
	if hdr.is_empty():
		return
	var dest: int = hdr["destination"]
	if _client_id != 0 and dest != 0 and dest != _client_id:
		return
	var payload := _Proto.payload_of(data)
	match hdr["type"]:
		_Proto.PT_SESSION_CONFIG:
			_on_session_config(payload, hdr["sequence"])
		_Proto.PT_PACKET_TYPE_REGISTRY:
			_on_packet_type_registry(payload)
		_Proto.PT_PING:
			_on_ping(payload, hdr["client_id"])
		_Proto.PT_PONG:
			pass
		_Proto.PT_DISCONNECT_NOTICE:
			_running = false
			if disconnect_callback.is_valid():
				disconnect_callback.call()
		_:
			if unhandled_packet_callback.is_valid():
				unhandled_packet_callback.call(hdr["type"], hdr["client_id"])

func _on_session_config(payload: PackedByteArray, sequence: int) -> void:
	var p := _Proto.parse_session_config(payload)
	if p.is_empty():
		return
	var ack_payload := _Proto.build_ack([sequence])
	var ack_pkt := _Proto.build_packet(
		_Proto.PT_ACK, _next_seq(), _client_id, 1, ack_payload)
	_socket.send(ack_pkt)
	if session_config_callback.is_valid():
		session_config_callback.call(p)

func _on_packet_type_registry(payload: PackedByteArray) -> void:
	var p := _Proto.parse_packet_type_registry(payload)
	if p.is_empty():
		return
	if packet_registry_callback.is_valid():
		packet_registry_callback.call(p["entries"])

func _on_ping(payload: PackedByteArray, sender: int) -> void:
	var p := _Proto.parse_ping(payload)
	if p.is_empty():
		return
	var pong_payload := _Proto.build_pong(p["timestamp"])
	var pkt := _Proto.build_packet(
		_Proto.PT_PONG, _next_seq(), _client_id, sender, pong_payload)
	_socket.send(pkt)

## Sends a game packet through the relay.
##
## [param data] The raw packet payload.
## [param type_byte] The game packet type byte (must be >= 0x10).
## [param dest] The destination client ID, or [code]0[/code] to broadcast, [code]1[/code] for host.
func send_packet(data: PackedByteArray, type_byte: int, dest: int) -> void:
	var pkt := _Proto.build_packet(type_byte, _next_seq(), _client_id, dest, data)
	_socket.send(pkt)

func _send_disconnect_notice() -> void:
	if _socket == null or not _socket.is_open():
		return
	var pkt := _Proto.build_packet(
		_Proto.PT_DISCONNECT_NOTICE, _next_seq(), _client_id, 0, PackedByteArray())
	_socket.send(pkt)

func _next_seq() -> int:
	var s := _sequence
	_sequence = (_sequence + 1) & 0xFFFF
	return s
