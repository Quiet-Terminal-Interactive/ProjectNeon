## Manages a game session as the host, connected through a [NeonRelay].
##
## Create a [NeonHost], register callbacks, then call [method start_and_run] (blocking) or
## [method start] followed by a manual loop calling [method process_packets].
## The host handles client connection, reconnection, session config delivery, and ACK tracking.
class_name NeonHost
extends RefCounted

const _Proto = preload("res://addons/qti_neon/_protocol.gd")
const _SockMod = preload("res://addons/qti_neon/_socket.gd")
const _AckMod = preload("res://addons/qti_neon/_ack.gd")

var _session_id: int
var _relay_address: String
var _config: NeonConfig

var _socket: _SockMod = null
var _running: bool = false
var _sequence: int = 0
var _next_client_id: int = 2

var _mutex: Mutex = Mutex.new()

var _connected_clients: Dictionary = {}
var _disconnected_clients: Dictionary = {}
var _connected_names: Dictionary = {}
var _seq_to_client: Dictionary = {}

var _crypto: Crypto = Crypto.new()

## Called when a client completes the connection handshake.
## [param cid] The assigned client ID.
## [param name] The player name supplied by the client.
## [param sid] The session ID.
var client_connect_callback: Callable = Callable()

## Called when a client disconnects or times out.
## [param cid] The client ID.
## [param name] The player name.
var client_disconnect_callback: Callable = Callable()

## Called for any received game packet not consumed by the host internally.
## [param type_byte] The packet type byte.
## [param sender] The client ID of the sender.
var unhandled_packet_callback: Callable = Callable()

## Optional packet type registry sent to clients when they connect.
var packet_registry: GamePacketRegistry = null

## Creates a new host for the given session.
## [param session_id] Identifies this session on the relay.
## [param relay_address] The relay address in [code]host:port[/code] format.
## [param config] Optional configuration; defaults are used if [code]null[/code].
func _init(session_id: int, relay_address: String, config: NeonConfig = null) -> void:
	_session_id = session_id
	_relay_address = relay_address
	_config = config if config != null else NeonConfig.new()

## Sets the callback invoked when a client connects.
## [param cb] A [code]Callable[/code] taking [code](cid: int, name: String, sid: int)[/code].
func set_client_connect_callback(cb: Callable) -> void:
	client_connect_callback = cb

## Sets the callback invoked when a client disconnects.
## [param cb] A [code]Callable[/code] taking [code](cid: int, name: String)[/code].
func set_client_disconnect_callback(cb: Callable) -> void:
	client_disconnect_callback = cb

## Sets the callback invoked for unhandled game packets.
## [param cb] A [code]Callable[/code] taking [param type_byte] and [param sender] as integers.
func set_unhandled_packet_callback(cb: Callable) -> void:
	unhandled_packet_callback = cb

## Starts the host and blocks in the processing loop until [method stop] is called.
##
## Equivalent to calling [method start] then looping [method process_packets].
## Run on a dedicated thread.
func start_and_run() -> void:
	if not _do_start():
		return
	while _running:
		process_packets()
		_check_pending_acks()
		_evict_expired_tokens()
		OS.delay_msec(_config.host_processing_loop_sleep_ms)
	_socket.close()

## Connects to the relay and registers this session without blocking.
##
## Returns [code]true[/code] on success. After this returns, call [method process_packets]
## in a loop to handle incoming connections.
func start() -> bool:
	return _do_start()

func _do_start() -> bool:
	var colon := _relay_address.rfind(":")
	if colon == -1:
		push_error("NeonHost: invalid relay address '%s'" % _relay_address)
		return false
	var relay_host := _relay_address.left(colon)
	var relay_port := int(_relay_address.substr(colon + 1))

	_socket = _SockMod.new()
	var err := _socket.open(relay_host, relay_port, _config.dtls_config)
	if err != OK:
		push_error("NeonHost: socket open failed: " + error_string(err))
		return false

	if not _socket.wait_for_handshake(_config.client_connection_timeout_ms):
		push_error("NeonHost: DTLS handshake timed out")
		_socket.close()
		return false

	var host_token := _random_token()
	var reg_payload := _Proto.build_host_register(_session_id, host_token)
	var reg_pkt := _Proto.build_packet(
		_Proto.PT_HOST_REGISTER, _next_seq(), 1, 0, reg_payload)
	_socket.send(reg_pkt)

	var deadline := Time.get_ticks_msec() + _config.client_connection_timeout_ms
	while Time.get_ticks_msec() < deadline:
		var data := _socket.receive()
		if data.size() >= _Proto.HEADER_SIZE:
			var hdr := _Proto.parse_header(data)
			if not hdr.is_empty() and hdr["type"] == _Proto.PT_CONNECT_ACCEPT:
				var pl := _Proto.parse_connect_accept(_Proto.payload_of(data))
				if not pl.is_empty() and pl["client_id"] == 1:
					_running = true
					return true
		OS.delay_msec(5)

	push_error("NeonHost: registration timed out")
	_socket.close()
	return false

## Gracefully shuts down the host.
##
## Waits up to [code]NeonConfig.host_graceful_shutdown_timeout_ms[/code] for pending ACKs,
## then sends a disconnect notice to all clients and closes the socket.
func stop() -> void:
	if not _running:
		return
	_running = false
	var deadline := Time.get_ticks_msec() + _config.host_graceful_shutdown_timeout_ms
	while Time.get_ticks_msec() < deadline:
		_mutex.lock()
		var all_clear := true
		for cid in _connected_clients.keys():
			var ack: _AckMod = _connected_clients[cid]["ack"]
			if ack.has_pending():
				all_clear = false
				break
		_mutex.unlock()
		if all_clear:
			break
		OS.delay_msec(20)
	_send_disconnect_notice()
	if _socket != null:
		_socket.close()
		_socket = null

## Returns [code]true[/code] if the host is connected and processing.
func is_running() -> bool:
	return _running

## Drains all pending packets from the socket without blocking.
##
## Called automatically by [method start_and_run]. Can be called manually if you
## manage the loop yourself.
func process_packets() -> void:
	if not _running:
		return
	while true:
		var data := _socket.receive()
		if data.size() < _Proto.HEADER_SIZE:
			break
		_handle_packet(data)


func _check_pending_acks() -> void:
	if not _running:
		return
	_mutex.lock()
	var client_ids := _connected_clients.keys()
	_mutex.unlock()
	for cid in client_ids:
		_mutex.lock()
		if not _connected_clients.has(cid):
			_mutex.unlock()
			continue
		var ack: _AckMod = _connected_clients[cid]["ack"]
		_mutex.unlock()
		var failed := ack.check(
			func(pkt): _socket.send(pkt),
			_config.host_ack_timeout_ms,
			_config.host_max_ack_retries
		)
		if not failed.is_empty():
			_mutex.lock()
			for seq in failed:
				_seq_to_client.erase(seq)
			_mutex.unlock()
			push_warning("NeonHost: SESSION_CONFIG ACK not received from client %d" % cid)

func _handle_packet(data: PackedByteArray) -> void:
	var hdr := _Proto.parse_header(data)
	if hdr.is_empty():
		return
	var payload := _Proto.payload_of(data)
	var sender: int = hdr["client_id"]

	match hdr["type"]:
		_Proto.PT_CONNECT_REQUEST:
			_on_connect_request(payload)
		_Proto.PT_RECONNECT_REQUEST:
			_on_reconnect_request(payload)
		_Proto.PT_DISCONNECT_NOTICE:
			_on_disconnect_notice(sender)
		_Proto.PT_ACK:
			_on_ack(payload)
		_Proto.PT_PING:
			_on_ping(payload, sender)
		_:
			if _Proto.is_game_packet(hdr["type"]) or hdr["type"] >= _Proto.PT_SESSION_CONFIG:
				if unhandled_packet_callback.is_valid():
					unhandled_packet_callback.call(hdr["type"], sender)

func _on_connect_request(payload: PackedByteArray) -> void:
	var p := _Proto.parse_connect_request(payload)
	if p.is_empty():
		return
	var name: String = p["name"]

	_mutex.lock()
	var already := _connected_names.has(name)
	if not already:
		_connected_names[name] = 0
	_mutex.unlock()

	if already:
		_send_deny("Name already taken")
		return

	_mutex.lock()
	var cid := _next_client_id
	_next_client_id += 1
	_mutex.unlock()

	_mutex.lock()
	_connected_names[name] = cid
	_mutex.unlock()

	var token := _random_token()
	var ack := _AckMod.new()

	_mutex.lock()
	_connected_clients[cid] = {
		"name": name,
		"token": token,
		"ack": ack,
	}
	_mutex.unlock()

	var accept_payload := _Proto.build_connect_accept(cid, _session_id, token)
	var accept_pkt := _Proto.build_packet(
		_Proto.PT_CONNECT_ACCEPT, _next_seq(), 1, 0, accept_payload)
	_socket.send(accept_pkt)

	var seq := _next_seq()
	var sc_payload := _Proto.build_session_config(
		1, _config.host_session_tick_rate, _config.host_session_max_packet_size)
	var sc_pkt := _Proto.build_packet(_Proto.PT_SESSION_CONFIG, seq, 1, cid, sc_payload)
	_socket.send(sc_pkt)
	ack.track(seq, sc_pkt)
	_mutex.lock()
	_seq_to_client[seq] = cid
	_mutex.unlock()

	if packet_registry != null:
		var reg_payload := _Proto.build_packet_type_registry(packet_registry.to_entry_array())
		var reg_pkt := _Proto.build_packet(
			_Proto.PT_PACKET_TYPE_REGISTRY, _next_seq(), 1, cid, reg_payload)
		_socket.send(reg_pkt)

	if client_connect_callback.is_valid():
		client_connect_callback.call(cid, name, _session_id)

func _on_reconnect_request(payload: PackedByteArray) -> void:
	var p := _Proto.parse_reconnect_request(payload)
	if p.is_empty():
		return
	var token: int = p["token"]
	var cid: int = p["previous_client_id"]

	_mutex.lock()
	var dc: Dictionary = _disconnected_clients.get(cid, {})
	_mutex.unlock()

	if dc.is_empty() or dc["token"] != token:
		_send_deny("Invalid token")
		return

	if Time.get_ticks_msec() - dc["disconnected_at"] > _config.host_session_token_timeout_ms:
		_mutex.lock()
		_disconnected_clients.erase(cid)
		_mutex.unlock()
		_send_deny("Token expired")
		return

	var name: String = dc["name"]
	var new_token := _random_token()
	var ack := _AckMod.new()

	_mutex.lock()
	_disconnected_clients.erase(cid)
	_connected_clients[cid] = {"name": name, "token": new_token, "ack": ack}
	_connected_names[name] = cid
	_mutex.unlock()

	var accept_payload := _Proto.build_connect_accept(cid, _session_id, new_token)
	var accept_pkt := _Proto.build_packet(
		_Proto.PT_CONNECT_ACCEPT, _next_seq(), 1, 0, accept_payload)
	_socket.send(accept_pkt)

	var seq := _next_seq()
	var sc_payload := _Proto.build_session_config(
		1, _config.host_session_tick_rate, _config.host_session_max_packet_size)
	var sc_pkt := _Proto.build_packet(_Proto.PT_SESSION_CONFIG, seq, 1, cid, sc_payload)
	_socket.send(sc_pkt)
	ack.track(seq, sc_pkt)
	_mutex.lock()
	_seq_to_client[seq] = cid
	_mutex.unlock()

	if packet_registry != null:
		var reg_payload := _Proto.build_packet_type_registry(packet_registry.to_entry_array())
		var reg_pkt := _Proto.build_packet(
			_Proto.PT_PACKET_TYPE_REGISTRY, _next_seq(), 1, cid, reg_payload)
		_socket.send(reg_pkt)

	if client_connect_callback.is_valid():
		client_connect_callback.call(cid, name, _session_id)

func _on_disconnect_notice(sender: int) -> void:
	_mutex.lock()
	var info: Dictionary = _connected_clients.get(sender, {})
	if not info.is_empty():
		_connected_clients.erase(sender)
		var name: String = info["name"]
		_connected_names.erase(name)
		for seq in (info["ack"] as _AckMod)._pending.keys():
			_seq_to_client.erase(seq)
		_disconnected_clients[sender] = {
			"name": name,
			"token": info["token"],
			"disconnected_at": Time.get_ticks_msec(),
		}
	_mutex.unlock()
	if not info.is_empty() and client_disconnect_callback.is_valid():
		client_disconnect_callback.call(sender, info["name"])


func _on_ack(payload: PackedByteArray) -> void:
	var p := _Proto.parse_ack(payload)
	if p.is_empty():
		return
	for seq in p["sequences"]:
		_mutex.lock()
		var cid: int = _seq_to_client.get(seq, -1)
		if cid != -1 and _connected_clients.has(cid):
			(_connected_clients[cid]["ack"] as _AckMod).on_ack(seq)
		_seq_to_client.erase(seq)
		_mutex.unlock()


func _on_ping(payload: PackedByteArray, sender: int) -> void:
	var p := _Proto.parse_ping(payload)
	if p.is_empty():
		return
	var pong_payload := _Proto.build_pong(p["timestamp"])
	var pkt := _Proto.build_packet(
		_Proto.PT_PONG, _next_seq(), 1, sender, pong_payload)
	_socket.send(pkt)

## Sends a game packet through the relay.
##
## [param data] The raw packet payload.
## [param type_byte] The game packet type byte (must be >= 0x10).
## [param dest] The destination client ID, or [code]0[/code] to broadcast to all clients.
func send_packet(data: PackedByteArray, type_byte: int, dest: int) -> void:
	var pkt := _Proto.build_packet(type_byte, _next_seq(), 1, dest, data)
	_socket.send(pkt)


func _send_deny(reason: String) -> void:
	var deny_payload := _Proto.build_connect_deny(reason)
	var pkt := _Proto.build_packet(
		_Proto.PT_CONNECT_DENY, _next_seq(), 1, 0, deny_payload)
	_socket.send(pkt)


func _send_disconnect_notice() -> void:
	var pkt := _Proto.build_packet(
		_Proto.PT_DISCONNECT_NOTICE, _next_seq(), 1, 0, PackedByteArray())
	_socket.send(pkt)

func _next_seq() -> int:
	var s := _sequence
	_sequence = (_sequence + 1) & 0xFFFF
	return s

func _random_token() -> int:
	var bytes := _crypto.generate_random_bytes(8)
	return bytes.decode_s64(0)

func _evict_expired_tokens() -> void:
	var now := Time.get_ticks_msec()
	_mutex.lock()
	var expired: Array = []
	for cid in _disconnected_clients.keys():
		var e: Dictionary = _disconnected_clients[cid]
		if now - e["disconnected_at"] > _config.host_session_token_timeout_ms:
			expired.append(cid)
	for cid in expired:
		_disconnected_clients.erase(cid)
	_mutex.unlock()
