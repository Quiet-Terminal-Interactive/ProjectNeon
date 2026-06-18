## Tracks outbound reliable packets and retransmits them on timeout.
##
## Also provides duplicate detection for inbound packets using sequence number comparison.
## Call [method send_reliable] instead of sending directly. Call [method on_ack_received]
## when ACK packets arrive, and [method check_timeouts] once per tick to retransmit.
class_name ReliablePacketManager
extends RefCounted

const _Proto = preload("res://addons/qti_neon/_protocol.gd")

var _my_client_id: int

var _pending: Dictionary = {}

var _last_seqs: Dictionary = {}

## Called with the sequence number of a packet whose retries were exhausted.
## [param sequence] The failed sequence number.
var on_delivery_failed: Callable = Callable()

## Creates a manager for the given client.
## [param my_client_id] The local client ID, included in outbound packet headers.
func _init(my_client_id: int) -> void:
	_my_client_id = my_client_id

## Sends a packet and registers it for ACK tracking and retransmission.
##
## [param payload] The packet payload bytes.
## [param type_byte] The game packet type byte (must be >= 0x10).
## [param dest] The destination client ID.
## [param seq] The sequence number for this packet.
## [param send_callable] A [code]Callable[/code] that accepts a [PackedByteArray] and sends it.
func send_reliable(payload: PackedByteArray, type_byte: int, dest: int,
		seq: int, send_callable: Callable) -> void:
	var pkt := _Proto.build_packet(type_byte, seq, _my_client_id, dest, payload)
	send_callable.call(pkt)
	_pending[seq] = {
		"packet": pkt,
		"sent_at": Time.get_ticks_msec(),
		"retries": 0,
		"dest": dest,
	}

## Removes acknowledged sequence numbers from the pending set.
## [param sequences] An [code]Array[/code] of sequence numbers from a received ACK packet.
func on_ack_received(sequences: Array) -> void:
	for seq in sequences:
		_pending.erase(seq)

## Returns [code]true[/code] if this sequence number was already received from [param sender_id].
##
## Uses wrapping sequence comparison to handle 16-bit rollover.
## [param sequence] The received sequence number.
## [param sender_id] The sender's client ID.
func is_duplicate(sequence: int, sender_id: int) -> bool:
	if not _last_seqs.has(sender_id):
		return false
	return _Proto.is_duplicate(sequence, _last_seqs[sender_id])

## Records the most recently seen sequence number from a sender.
## Call this after accepting a non-duplicate packet.
## [param sequence] The accepted sequence number.
## [param sender_id] The sender's client ID.
func update_last_seq(sequence: int, sender_id: int) -> void:
	_last_seqs[sender_id] = sequence

## Retransmits any tracked packets that have not been acknowledged within [param timeout_ms].
##
## Packets that exceed [param max_retries] are dropped and [member on_delivery_failed] is called.
## [param send_callable] A [code]Callable[/code] that accepts a [PackedByteArray] and sends it.
## [param timeout_ms] Milliseconds before a packet is considered timed out.
## [param max_retries] Maximum retransmissions before giving up.
func check_timeouts(send_callable: Callable, timeout_ms: int, max_retries: int) -> void:
	var now := Time.get_ticks_msec()
	for seq in _pending.keys():
		var entry: Dictionary = _pending[seq]
		if now - entry["sent_at"] < timeout_ms:
			continue
		if entry["retries"] >= max_retries:
			_pending.erase(seq)
			if on_delivery_failed.is_valid():
				on_delivery_failed.call(seq)
		else:
			entry["retries"] += 1
			entry["sent_at"] = now
			send_callable.call(entry["packet"])
