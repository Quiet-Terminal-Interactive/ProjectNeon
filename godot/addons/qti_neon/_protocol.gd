## Wire protocol constants, packet serialization, and deserialization.
##
## All functions are static. Parse functions return an empty [Dictionary] on failure.
## Build functions return the serialized [PackedByteArray].
##
## Packet header layout (8 bytes):
## [codeblock]
## [0]    Magic 0 (0x4E)
## [1]    Magic 1 (0x45)
## [2]    Protocol version (0x01)
## [3]    Packet type byte
## [4-5]  Sequence number (uint16 little-endian)
## [6]    Sender client ID
## [7]    Destination client ID (0 = broadcast, 1 = host)
## [/codeblock]

## First magic byte ('N').
const MAGIC_0 := 0x4E
## Second magic byte ('E').
const MAGIC_1 := 0x45
## Current protocol version.
const PROTO_VERSION := 0x01
## Size of the fixed packet header in bytes.
const HEADER_SIZE := 8

## Packet type: client requests to join a session.
const PT_CONNECT_REQUEST := 0x01
## Packet type: relay or host accepts a connection.
const PT_CONNECT_ACCEPT := 0x02
## Packet type: relay or host denies a connection.
const PT_CONNECT_DENY := 0x03
## Packet type: host sends session parameters to a new client.
const PT_SESSION_CONFIG := 0x04
## Packet type: host broadcasts the game packet type registry.
const PT_PACKET_TYPE_REGISTRY := 0x05
## Packet type: host registers its session with the relay.
const PT_HOST_REGISTER := 0x06
## Packet type: keepalive ping carrying a timestamp.
const PT_PING := 0x0B
## Packet type: pong reply echoing the ping timestamp.
const PT_PONG := 0x0C
## Packet type: peer is disconnecting gracefully.
const PT_DISCONNECT_NOTICE := 0x0D
## Packet type: acknowledgement of one or more sequence numbers.
const PT_ACK := 0x0E
## Packet type: client requests to resume a disconnected session.
const PT_RECONNECT_REQUEST := 0x0F

## Returns [code]true[/code] if [param type_byte] is a game-defined packet type (>= 0x10).
static func is_game_packet(type_byte: int) -> bool:
	return type_byte >= 0x10

## Returns [code]true[/code] if [param first_byte] looks like a DTLS record header.
static func is_dtls_record(first_byte: int) -> bool:
	return (first_byte >= 0x14 and first_byte <= 0x17) or \
	       (first_byte >= 0x20 and first_byte <= 0x3F)

## Parses the 8-byte packet header from [param data].
##
## Returns a [Dictionary] with [code]type[/code], [code]sequence[/code], [code]client_id[/code],
## and [code]destination[/code] keys, or an empty [Dictionary] if the header is invalid.
static func parse_header(data: PackedByteArray) -> Dictionary:
	if data.size() < HEADER_SIZE:
		return {}
	if data[0] != MAGIC_0 or data[1] != MAGIC_1:
		return {}
	if data[2] != PROTO_VERSION:
		return {}
	return {
		"type": data[3],
		"sequence": data.decode_u16(4),
		"client_id": data[6],
		"destination": data[7],
	}


## Builds an 8-byte packet header.
static func build_header(type_byte: int, sequence: int, client_id: int, destination: int) -> PackedByteArray:
	var h := PackedByteArray()
	h.resize(HEADER_SIZE)
	h[0] = MAGIC_0
	h[1] = MAGIC_1
	h[2] = PROTO_VERSION
	h[3] = type_byte & 0xFF
	h.encode_u16(4, sequence & 0xFFFF)
	h[6] = client_id & 0xFF
	h[7] = destination & 0xFF
	return h


## Builds a complete packet by prepending a header to [param payload].
static func build_packet(type_byte: int, sequence: int, client_id: int,
		destination: int, payload: PackedByteArray) -> PackedByteArray:
	var h := build_header(type_byte, sequence, client_id, destination)
	h.append_array(payload)
	return h


## Returns the payload portion of [param data] (everything after the header).
static func payload_of(data: PackedByteArray) -> PackedByteArray:
	if data.size() <= HEADER_SIZE:
		return PackedByteArray()
	return data.slice(HEADER_SIZE)

## Returns [code]true[/code] if [param received_seq] is a duplicate of or older than [param last_seq].
##
## Uses wrapping 16-bit comparison to handle sequence number rollover.
static func is_duplicate(received_seq: int, last_seq: int) -> bool:
	var diff: int = (received_seq - last_seq) & 0xFFFF
	if diff >= 0x8000:
		diff -= 0x10000
	return diff <= 0

## Parses a [code]CONNECT_REQUEST[/code] payload.
## Returns [code]client_version[/code], [code]name[/code], [code]session_id[/code], [code]game_id[/code].
static func parse_connect_request(p: PackedByteArray) -> Dictionary:
	if p.size() < 11:
		return {}
	var name_len: int = p.decode_u16(1)
	if name_len < 1 or name_len > 64:
		return {}
	if p.size() < 3 + name_len + 8:
		return {}
	return {
		"client_version": p[0],
		"name": p.slice(3, 3 + name_len).get_string_from_utf8(),
		"session_id": p.decode_s32(3 + name_len),
		"game_id": p.decode_s32(7 + name_len),
	}


## Builds a [code]CONNECT_REQUEST[/code] payload.
static func build_connect_request(client_version: int, name: String,
		session_id: int, game_id: int) -> PackedByteArray:
	var nb := name.to_utf8_buffer()
	var p := PackedByteArray()
	p.resize(3 + nb.size() + 8)
	p[0] = client_version & 0xFF
	p.encode_u16(1, nb.size())
	for i in nb.size():
		p[3 + i] = nb[i]
	p.encode_s32(3 + nb.size(), session_id)
	p.encode_s32(7 + nb.size(), game_id)
	return p

## Parses a [code]CONNECT_ACCEPT[/code] payload.
## Returns [code]client_id[/code], [code]session_id[/code], [code]token[/code].
static func parse_connect_accept(p: PackedByteArray) -> Dictionary:
	if p.size() < 13:
		return {}
	return {
		"client_id": p[0],
		"session_id": p.decode_s32(1),
		"token": p.decode_s64(5),
	}


## Builds a [code]CONNECT_ACCEPT[/code] payload.
static func build_connect_accept(client_id: int, session_id: int, token: int) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(13)
	p[0] = client_id & 0xFF
	p.encode_s32(1, session_id)
	p.encode_s64(5, token)
	return p

## Parses a [code]CONNECT_DENY[/code] payload. Returns [code]reason[/code].
static func parse_connect_deny(p: PackedByteArray) -> Dictionary:
	if p.size() < 2:
		return {}
	var reason_len: int = p.decode_u16(0)
	if p.size() < 2 + reason_len:
		return {}
	return {
		"reason": p.slice(2, 2 + reason_len).get_string_from_utf8(),
	}


## Builds a [code]CONNECT_DENY[/code] payload.
static func build_connect_deny(reason: String) -> PackedByteArray:
	var rb := reason.to_utf8_buffer()
	var p := PackedByteArray()
	p.resize(2 + rb.size())
	p.encode_u16(0, rb.size())
	for i in rb.size():
		p[2 + i] = rb[i]
	return p

## Parses a [code]SESSION_CONFIG[/code] payload.
## Returns [code]version[/code], [code]tick_rate[/code], [code]max_packet_size[/code].
static func parse_session_config(p: PackedByteArray) -> Dictionary:
	if p.size() < 5:
		return {}
	return {
		"version": p[0],
		"tick_rate": p.decode_s16(1),
		"max_packet_size": p.decode_s16(3),
	}


## Builds a [code]SESSION_CONFIG[/code] payload.
static func build_session_config(version: int, tick_rate: int, max_packet_size: int) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(5)
	p[0] = version & 0xFF
	p.encode_s16(1, tick_rate)
	p.encode_s16(3, max_packet_size)
	return p

## Parses a [code]PACKET_TYPE_REGISTRY[/code] payload.
## Returns [code]entries[/code]: an [code]Array[/code] of [code]{packet_id, name, description}[/code] dicts.
static func parse_packet_type_registry(p: PackedByteArray) -> Dictionary:
	if p.size() < 2:
		return {}
	var count: int = p.decode_u16(0)
	var offset := 2
	var entries: Array = []
	for _i in count:
		if offset + 2 > p.size():
			return {}
		var packet_id: int = p[offset]
		var name_len: int = p[offset + 1]
		offset += 2
		if offset + name_len > p.size():
			return {}
		var entry_name := p.slice(offset, offset + name_len).get_string_from_utf8()
		offset += name_len
		if offset >= p.size():
			return {}
		var desc_len: int = p[offset]
		offset += 1
		if offset + desc_len > p.size():
			return {}
		var desc := p.slice(offset, offset + desc_len).get_string_from_utf8()
		offset += desc_len
		entries.append({"packet_id": packet_id, "name": entry_name, "description": desc})
	return {"entries": entries}


## Builds a [code]PACKET_TYPE_REGISTRY[/code] payload from an array of entry dictionaries.
static func build_packet_type_registry(entries: Array) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(2)
	p.encode_u16(0, entries.size())
	for e in entries:
		var nb: PackedByteArray = (e["name"] as String).to_utf8_buffer()
		var db: PackedByteArray = (e["description"] as String).to_utf8_buffer()
		var entry := PackedByteArray()
		entry.resize(3 + nb.size() + db.size())
		entry[0] = (e["packet_id"] as int) & 0xFF
		entry[1] = nb.size() & 0xFF
		for i in nb.size():
			entry[2 + i] = nb[i]
		entry[2 + nb.size()] = db.size() & 0xFF
		for i in db.size():
			entry[3 + nb.size() + i] = db[i]
		p.append_array(entry)
	return p

## Parses a [code]HOST_REGISTER[/code] payload.
## Returns [code]session_id[/code] and [code]host_token[/code].
static func parse_host_register(p: PackedByteArray) -> Dictionary:
	if p.size() < 12:
		return {}
	return {
		"session_id": p.decode_s32(0),
		"host_token": p.decode_s64(4),
	}


## Builds a [code]HOST_REGISTER[/code] payload.
static func build_host_register(session_id: int, host_token: int) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(12)
	p.encode_s32(0, session_id)
	p.encode_s64(4, host_token)
	return p

## Parses a [code]PING[/code] payload. Returns [code]timestamp[/code].
static func parse_ping(p: PackedByteArray) -> Dictionary:
	if p.size() < 8:
		return {}
	return {"timestamp": p.decode_s64(0)}


## Builds a [code]PING[/code] payload carrying [param timestamp] (milliseconds).
static func build_ping(timestamp: int) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(8)
	p.encode_s64(0, timestamp)
	return p

## Parses a [code]PONG[/code] payload. Returns [code]original_timestamp[/code].
static func parse_pong(p: PackedByteArray) -> Dictionary:
	if p.size() < 8:
		return {}
	return {"original_timestamp": p.decode_s64(0)}


## Builds a [code]PONG[/code] payload echoing [param original_timestamp].
static func build_pong(original_timestamp: int) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(8)
	p.encode_s64(0, original_timestamp)
	return p

## Builds an empty [code]DISCONNECT_NOTICE[/code] payload.
static func build_disconnect_notice() -> PackedByteArray:
	return PackedByteArray()

## Parses an [code]ACK[/code] payload. Returns [code]sequences[/code]: an [code]Array[/code] of integers.
static func parse_ack(p: PackedByteArray) -> Dictionary:
	if p.size() < 2:
		return {}
	var count: int = p.decode_u16(0)
	if p.size() < 2 + count * 2:
		return {}
	var seqs: Array = []
	for i in count:
		seqs.append(p.decode_s16(2 + i * 2))
	return {"sequences": seqs}


## Builds an [code]ACK[/code] payload acknowledging the given sequence numbers.
## [param sequences] An [code]Array[/code] of integer sequence numbers.
static func build_ack(sequences: Array) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(2 + sequences.size() * 2)
	p.encode_u16(0, sequences.size())
	for i in sequences.size():
		p.encode_s16(2 + i * 2, sequences[i] as int)
	return p

## Parses a [code]RECONNECT_REQUEST[/code] payload.
## Returns [code]token[/code], [code]session_id[/code], [code]previous_client_id[/code].
static func parse_reconnect_request(p: PackedByteArray) -> Dictionary:
	if p.size() < 13:
		return {}
	return {
		"token": p.decode_s64(0),
		"session_id": p.decode_s32(8),
		"previous_client_id": p[12],
	}


## Builds a [code]RECONNECT_REQUEST[/code] payload.
static func build_reconnect_request(token: int, session_id: int, previous_client_id: int) -> PackedByteArray:
	var p := PackedByteArray()
	p.resize(13)
	p.encode_s64(0, token)
	p.encode_s32(8, session_id)
	p[12] = previous_client_id & 0xFF
	return p
