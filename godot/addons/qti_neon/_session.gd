## Relay-side session state tracker.
##
## Maps session IDs to their host and client peer keys, and tracks per-peer activity
## timestamps for timeout-based cleanup. Used internally by [NeonRelay].

## Sentinel value returned when no peer key exists for a given lookup.
const INVALID_PEER := ""

## Holds the state for a single active session.
class Session:
	## The session identifier.
	var session_id: int = 0
	## Peer key of the host connection.
	var host_peer_key: String = ""
	## Timestamp of the last packet received from the host.
	var last_host_activity: int = 0
	## Timestamp of the last packet received from any peer in this session.
	var last_any_activity: int = 0
	## Map of client_id -> peer_key for connected clients.
	var clients: Dictionary = {}
	## Map of client_id -> last activity timestamp.
	var client_activity: Dictionary = {}

	func _init(sid: int, hpk: String) -> void:
		session_id = sid
		host_peer_key = hpk
		var now := Time.get_ticks_msec()
		last_host_activity = now
		last_any_activity = now


var _sessions: Dictionary = {}

var _peer_to_session: Dictionary = {}

## Registers a host for a new session.
## [param session_id] The session to create.
## [param peer_key] The host's peer key.
func register_host(session_id: int, peer_key: String) -> void:
	var s := Session.new(session_id, peer_key)
	_sessions[session_id] = s
	_peer_to_session[peer_key] = session_id

## Removes a session and all its peer-to-session mappings.
## [param session_id] The session to remove.
func unregister_session(session_id: int) -> void:
	if not _sessions.has(session_id):
		return
	var s: Session = _sessions[session_id]
	_peer_to_session.erase(s.host_peer_key)
	for cid in s.clients.keys():
		_peer_to_session.erase(s.clients[cid])
	_sessions.erase(session_id)

## Adds a client to an existing session.
## [param session_id] The session to add the client to.
## [param client_id] The client's assigned ID.
## [param peer_key] The client's peer key.
func register_client(session_id: int, client_id: int, peer_key: String) -> void:
	if not _sessions.has(session_id):
		return
	var s: Session = _sessions[session_id]
	var now := Time.get_ticks_msec()
	s.clients[client_id] = peer_key
	s.client_activity[client_id] = now
	s.last_any_activity = now
	_peer_to_session[peer_key] = session_id

## Updates the peer key for an existing client (used during reconnection).
## [param session_id] The client's session.
## [param client_id] The client's ID.
## [param new_peer_key] The client's new peer key after reconnecting.
func update_client_peer(session_id: int, client_id: int, new_peer_key: String) -> void:
	if not _sessions.has(session_id):
		return
	var s: Session = _sessions[session_id]
	if s.clients.has(client_id):
		_peer_to_session.erase(s.clients[client_id])
	s.clients[client_id] = new_peer_key
	s.client_activity[client_id] = Time.get_ticks_msec()
	_peer_to_session[new_peer_key] = session_id

## Removes a single client from a session.
## [param session_id] The client's session.
## [param client_id] The client to remove.
func remove_client(session_id: int, client_id: int) -> void:
	if not _sessions.has(session_id):
		return
	var s: Session = _sessions[session_id]
	if s.clients.has(client_id):
		_peer_to_session.erase(s.clients[client_id])
		s.clients.erase(client_id)
		s.client_activity.erase(client_id)

## Updates the last-activity timestamp for the peer identified by [param peer_key].
func touch_peer(peer_key: String) -> void:
	if not _peer_to_session.has(peer_key):
		return
	var sid: int = _peer_to_session[peer_key]
	if not _sessions.has(sid):
		return
	var s: Session = _sessions[sid]
	var now := Time.get_ticks_msec()
	s.last_any_activity = now
	if s.host_peer_key == peer_key:
		s.last_host_activity = now
	else:
		for cid in s.clients.keys():
			if s.clients[cid] == peer_key:
				s.client_activity[cid] = now
				break

## Returns the session ID for [param peer_key], or [code]-1[/code] if not found.
func session_id_for_peer(peer_key: String) -> int:
	return _peer_to_session.get(peer_key, -1)

## Returns the [Session] for [param session_id], or [code]null[/code] if not found.
func get_session(session_id: int) -> Session:
	return _sessions.get(session_id, null)

## Returns the host peer key for [param session_id], or [constant INVALID_PEER] if not found.
func host_peer_key(session_id: int) -> String:
	var s: Session = _sessions.get(session_id, null)
	if s == null:
		return INVALID_PEER
	return s.host_peer_key

## Returns the peer key for [param client_id] in [param session_id], or [constant INVALID_PEER].
func client_peer_key(session_id: int, client_id: int) -> String:
	var s: Session = _sessions.get(session_id, null)
	if s == null:
		return INVALID_PEER
	return s.clients.get(client_id, INVALID_PEER)

## Returns all peer keys in [param session_id] (host first, then clients).
func all_peer_keys_in_session(session_id: int) -> Array:
	var s: Session = _sessions.get(session_id, null)
	if s == null:
		return []
	var keys: Array = [s.host_peer_key]
	for cid in s.clients.keys():
		keys.append(s.clients[cid])
	return keys

## Returns [code]true[/code] if [param session_id] is currently registered.
func has_session(session_id: int) -> bool:
	return _sessions.has(session_id)

## Returns the total number of connected clients across all sessions.
func total_client_count() -> int:
	var total := 0
	for sid in _sessions.keys():
		total += (_sessions[sid] as Session).clients.size()
	return total

## Returns an [code]Array[/code] of session IDs that have had no activity for longer
## than [param client_timeout_ms] milliseconds.
func collect_stale_sessions(client_timeout_ms: int) -> Array:
	var now := Time.get_ticks_msec()
	var stale: Array = []
	for sid in _sessions.keys():
		var s: Session = _sessions[sid]
		if now - s.last_any_activity > client_timeout_ms:
			stale.append(sid)
	return stale

## Returns an [code]Array[/code] of [code]{"session_id", "client_id"}[/code] dictionaries
## for clients that have had no activity for longer than [param client_timeout_ms] milliseconds.
func collect_stale_clients(client_timeout_ms: int) -> Array:
	var now := Time.get_ticks_msec()
	var stale: Array = []
	for sid in _sessions.keys():
		var s: Session = _sessions[sid]
		for cid in s.client_activity.keys():
			if now - s.client_activity[cid] > client_timeout_ms:
				stale.append({"session_id": sid, "client_id": cid})
	return stale
