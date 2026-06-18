## Registry of game-specific packet type IDs, names, and descriptions.
##
## Assign an instance to [member NeonHost.packet_registry] to have the host broadcast
## the registry to each client when they connect. Clients receive it via
## [member NeonClient.packet_registry_callback].
##
## All game packet IDs must be >= [code]0x10[/code]. IDs below that are reserved for
## the Neon protocol.
class_name GamePacketRegistry
extends RefCounted

var _entries: Dictionary = {}

## Registers a game packet type.
##
## [param packet_id] A unique identifier >= [code]0x10[/code].
## [param name] A short human-readable name for this packet type.
## [param description] Optional longer description.
func register(packet_id: int, name: String, description: String = "") -> void:
	assert(packet_id >= 0x10, "Game packet IDs must be >= 0x10")
	_entries[packet_id] = {"name": name, "description": description}

## Removes a previously registered packet type.
## [param packet_id] The ID to remove.
func unregister(packet_id: int) -> void:
	_entries.erase(packet_id)

## Returns [code]true[/code] if [param packet_id] is registered.
func has(packet_id: int) -> bool:
	return _entries.has(packet_id)

## Serializes the registry to an array suitable for protocol transmission.
##
## Each entry is a [code]Dictionary[/code] with [code]packet_id[/code], [code]name[/code],
## and [code]description[/code] keys.
func to_entry_array() -> Array:
	var arr: Array = []
	for id in _entries.keys():
		var e: Dictionary = _entries[id]
		arr.append({
			"packet_id": id,
			"name": e["name"],
			"description": e["description"],
		})
	return arr
