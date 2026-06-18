## Client-side socket for [NeonClient] and [NeonHost].
##
## Wraps a single UDP or DTLS connection to a relay. Call [method open] to connect,
## [method wait_for_handshake] if using DTLS, then [method send] and [method receive]
## to exchange packets.

const _Proto = preload("res://addons/qti_neon/_protocol.gd")

var _udp: PacketPeerUDP = null
var _dtls: PacketPeerDTLS = null
var _relay_host: String = ""


## Opens a UDP connection to the relay.
##
## If [param dtls_cfg] is not [code]null[/code], initiates a DTLS handshake.
## Call [method wait_for_handshake] after this returns [code]OK[/code] before sending packets.
## Returns a Godot [Error] code.
func open(relay_host: String, relay_port: int, dtls_cfg: DtlsConfig) -> Error:
	_relay_host = relay_host
	var resolved_host := IP.resolve_hostname(relay_host, IP.TYPE_IPV4)
	if resolved_host.is_empty():
		resolved_host = IP.resolve_hostname(relay_host, IP.TYPE_IPV6)
		if resolved_host.is_empty():
			push_error("_socket: could not resolve hostname '%s'" % relay_host)
			return ERR_CANT_RESOLVE
	_udp = PacketPeerUDP.new()
	_udp.set_dest_address(resolved_host, relay_port)
	var err := _udp.bind(0)
	if err != OK:
		return err

	if dtls_cfg != null:
		_dtls = PacketPeerDTLS.new()
		var tls_opts := dtls_cfg.build_tls_options(relay_host)
		err = _dtls.connect_to_peer(_udp, resolved_host, tls_opts)
		if err != OK:
			_udp.close()
			return err
	else:
		err = _udp.connect_to_host(resolved_host, relay_port)
		if err != OK:
			_udp.close()
			return err

	return OK

## Blocks until the DTLS handshake completes or [param timeout_ms] elapses.
##
## Always returns [code]true[/code] immediately for plain UDP connections.
## Returns [code]false[/code] on handshake failure or timeout.
func wait_for_handshake(timeout_ms: int) -> bool:
	if _dtls == null:
		return true
	var deadline := Time.get_ticks_msec() + timeout_ms
	while Time.get_ticks_msec() < deadline:
		_dtls.poll()
		match _dtls.get_status():
			PacketPeerDTLS.STATUS_CONNECTED:
				return true
			PacketPeerDTLS.STATUS_ERROR, PacketPeerDTLS.STATUS_DISCONNECTED:
				return false
		OS.delay_msec(5)
	return false


## Sends [param data] to the relay. Returns a Godot [Error] code.
func send(data: PackedByteArray) -> Error:
	if _dtls != null:
		return _dtls.put_packet(data)
	if _udp != null:
		return _udp.put_packet(data)
	return ERR_UNCONFIGURED

## Returns the next available packet, or an empty [PackedByteArray] if none are waiting.
func receive() -> PackedByteArray:
	if _dtls != null:
		_dtls.poll()
		if _dtls.get_status() == PacketPeerDTLS.STATUS_CONNECTED and \
				_dtls.get_available_packet_count() > 0:
			return _dtls.get_packet()
	elif _udp != null and _udp.get_available_packet_count() > 0:
		return _udp.get_packet()
	return PackedByteArray()

## Returns [code]true[/code] if the socket is open and connected.
func is_open() -> bool:
	if _dtls != null:
		return _dtls.get_status() == PacketPeerDTLS.STATUS_CONNECTED
	return _udp != null

## Closes the connection and releases all socket resources.
func close() -> void:
	if _dtls != null:
		_dtls.disconnect_from_peer()
		_dtls = null
	if _udp != null:
		_udp.close()
		_udp = null
