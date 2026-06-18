## Queues inbound sequence numbers and flushes them as a single ACK packet.
##
## Use this to acknowledge reliable packets received from the host without sending
## one ACK per packet. Call [method queue] for each sequence number received, then
## call [method flush] once per tick to send them all in one packet.
class_name BatchAckManager
extends RefCounted

const _Proto = preload("res://addons/qti_neon/_protocol.gd")

var _my_client_id: int
var _pending: Array = []

## Creates a manager for the given client.
## [param my_client_id] The local client ID, included in outbound ACK packet headers.
func _init(my_client_id: int) -> void:
	_my_client_id = my_client_id

## Queues a sequence number to be acknowledged on the next [method flush].
## [param sequence] The sequence number from the received packet header.
func queue(sequence: int) -> void:
	_pending.append(sequence)

## Sends all queued sequence numbers as a single ACK packet and clears the queue.
##
## Does nothing if the queue is empty.
## [param next_seq] The sequence number to use for the outbound ACK packet.
## [param dest_id] The destination client ID (typically [code]1[/code] for the host).
## [param send_callable] A [code]Callable[/code] that accepts a [PackedByteArray] and sends it.
func flush(next_seq: int, dest_id: int, send_callable: Callable) -> void:
	if _pending.is_empty():
		return
	var ack_payload := _Proto.build_ack(_pending)
	var pkt := _Proto.build_packet(
		_Proto.PT_ACK, next_seq, _my_client_id, dest_id, ack_payload)
	send_callable.call(pkt)
	_pending.clear()

## Returns [code]true[/code] if there are no queued sequence numbers.
func is_empty() -> bool:
	return _pending.is_empty()
