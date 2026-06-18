## Tracks outbound packets that require acknowledgement from a single peer.
##
## Used internally by [NeonHost] to track [code]SESSION_CONFIG[/code] delivery per client.
## Call [method track] when a packet is sent, [method on_ack] when the ACK arrives,
## and [method check] periodically to retransmit timed-out packets.

var _pending: Dictionary = {}


## Registers a sent packet for ACK tracking.
## [param sequence] The packet's sequence number.
## [param packet_bytes] The full serialized packet, used for retransmission.
func track(sequence: int, packet_bytes: PackedByteArray) -> void:
	_pending[sequence] = {
		"packet": packet_bytes,
		"sent_at": Time.get_ticks_msec(),
		"retries": 0,
	}


## Removes a sequence number from the pending set when its ACK is received.
## [param sequence] The acknowledged sequence number.
func on_ack(sequence: int) -> void:
	_pending.erase(sequence)


## Returns [code]true[/code] if there are any unacknowledged packets.
func has_pending() -> bool:
	return not _pending.is_empty()


## Retransmits timed-out packets and removes those that have exceeded [param max_retries].
##
## Returns an [code]Array[/code] of sequence numbers that were dropped due to exhausted retries.
## [param send_fn] A [code]Callable[/code] that accepts a [PackedByteArray] and sends it.
## [param ack_timeout_ms] Milliseconds before a packet is considered timed out.
## [param max_retries] Maximum retransmission attempts before dropping.
func check(send_fn: Callable, ack_timeout_ms: int, max_retries: int) -> Array:
	var now := Time.get_ticks_msec()
	var failed: Array = []

	for seq in _pending.keys():
		var entry: Dictionary = _pending[seq]
		if now - entry["sent_at"] < ack_timeout_ms:
			continue
		if entry["retries"] >= max_retries:
			failed.append(seq)
			_pending.erase(seq)
		else:
			entry["retries"] += 1
			entry["sent_at"] = now
			send_fn.call(entry["packet"])

	return failed


## Clears all tracked packets without sending any retransmissions.
func clear() -> void:
	_pending.clear()
