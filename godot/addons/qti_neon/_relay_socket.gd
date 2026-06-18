## Low-level server socket for [NeonRelay].
##
## Multiplexes multiple peers over a single bound port using either plain UDP or DTLS.
## Peers are identified by opaque string keys. In plain UDP mode the key is
## [code]"ip:port"[/code]; in DTLS mode it is an instance-ID-based string assigned
## after the handshake completes.

const _Proto = preload("res://addons/qti_neon/_protocol.gd")

var _udp_server: UDPServer = null
var _dtls_server: DTLSServer = null
var _dtls_enabled: bool = false
var _udp_peers: Dictionary = {}
var _dtls_handshaking: Array = []
var _dtls_peers: Dictionary = {}


## Binds the socket to [param port].
##
## If [param dtls_cfg] is not [code]null[/code], DTLS is enabled using its server-mode
## TLS options. Returns a Godot [Error] code.
func bind(port: int, dtls_cfg: DtlsConfig) -> Error:
	_udp_server = UDPServer.new()
	var err := _udp_server.listen(port)
	if err != OK:
		return err

	if dtls_cfg != null:
		_dtls_enabled = true
		_dtls_server = DTLSServer.new()
		var tls_opts := dtls_cfg.build_tls_options()
		err = _dtls_server.setup(tls_opts)
		if err != OK:
			_udp_server.stop()
			return err

	return OK

## Receives all available packets from all connected peers.
##
## Returns an [code]Array[/code] of [code]{"data": PackedByteArray, "peer_key": String}[/code]
## dictionaries, one per packet. Packets shorter than the protocol header or exactly equal
## to [param buffer_size] (when [param enforce_buffer_size] is [code]true[/code]) are dropped.
func receive_all(buffer_size: int, enforce_buffer_size: bool) -> Array:
	if _dtls_enabled:
		return _receive_dtls(buffer_size, enforce_buffer_size)
	return _receive_plain(buffer_size, enforce_buffer_size)


func _receive_plain(buffer_size: int, enforce: bool) -> Array:
	_udp_server.poll()

	while _udp_server.is_connection_available():
		var peer: PacketPeerUDP = _udp_server.take_connection()
		var key := peer.get_packet_ip() + ":" + str(peer.get_packet_port())
		if not _udp_peers.has(key):
			_udp_peers[key] = peer

	var result: Array = []
	for key in _udp_peers.keys():
		var peer: PacketPeerUDP = _udp_peers[key]
		while peer.get_available_packet_count() > 0:
			var data: PackedByteArray = peer.get_packet()
			if enforce and data.size() == buffer_size:
				continue
			if data.size() < _Proto.HEADER_SIZE:
				continue
			result.append({"data": data, "peer_key": key})
	return result


func _receive_dtls(buffer_size: int, enforce: bool) -> Array:
	_udp_server.poll()

	while _udp_server.is_connection_available():
		var udp_peer: PacketPeerUDP = _udp_server.take_connection()
		var dtls_peer: PacketPeerDTLS = _dtls_server.take_connection(udp_peer)
		_dtls_handshaking.append(dtls_peer)

	var graduated: Array = []
	var failed: Array = []
	for peer in _dtls_handshaking:
		peer.poll()
		match peer.get_status():
			PacketPeerDTLS.STATUS_CONNECTED:
				graduated.append(peer)
			PacketPeerDTLS.STATUS_ERROR, PacketPeerDTLS.STATUS_DISCONNECTED:
				failed.append(peer)
	for peer in failed:
		_dtls_handshaking.erase(peer)
	for peer in graduated:
		_dtls_handshaking.erase(peer)
		var key := "dtls:" + str(peer.get_instance_id())
		_dtls_peers[key] = peer

	var result: Array = []
	var dead_keys: Array = []
	for key in _dtls_peers.keys():
		var peer: PacketPeerDTLS = _dtls_peers[key]
		peer.poll()
		if peer.get_status() != PacketPeerDTLS.STATUS_CONNECTED:
			dead_keys.append(key)
			continue
		while peer.get_available_packet_count() > 0:
			var data: PackedByteArray = peer.get_packet()
			if enforce and data.size() == buffer_size:
				continue
			if data.size() < _Proto.HEADER_SIZE:
				continue
			result.append({"data": data, "peer_key": key})
	for key in dead_keys:
		_dtls_peers.erase(key)

	return result


## Sends [param data] to the peer identified by [param peer_key].
##
## Returns a Godot [Error] code. Returns [code]ERR_DOES_NOT_EXIST[/code] if the peer
## is not tracked.
func send(data: PackedByteArray, peer_key: String) -> Error:
	if _dtls_enabled:
		if not _dtls_peers.has(peer_key):
			return ERR_DOES_NOT_EXIST
		return (_dtls_peers[peer_key] as PacketPeerDTLS).put_packet(data)
	else:
		if not _udp_peers.has(peer_key):
			return ERR_DOES_NOT_EXIST
		return (_udp_peers[peer_key] as PacketPeerUDP).put_packet(data)

## Returns the peer key for a given IP and port (plain UDP mode only).
func peer_key_for(ip: String, port: int) -> String:
	return ip + ":" + str(port)


## Removes a peer from the tracking table.
## [param peer_key] The key returned in a previous [method receive_all] result.
func remove_peer(peer_key: String) -> void:
	if _dtls_enabled:
		_dtls_peers.erase(peer_key)
	else:
		_udp_peers.erase(peer_key)


## Closes the socket and clears all peer state.
func stop() -> void:
	_dtls_handshaking.clear()
	_dtls_peers.clear()
	_udp_peers.clear()
	if _dtls_server != null:
		_dtls_server = null
	if _udp_server != null:
		_udp_server.stop()
		_udp_server = null
