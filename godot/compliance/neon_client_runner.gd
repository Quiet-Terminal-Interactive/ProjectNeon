#!/usr/bin/env -S godot --headless --script
## Compliance test client runner.
##
## Connects to a session, sends one game packet, then exits.
## Prints protocol events to stdout for the test harness to parse.
##
## Usage: [code]godot --headless --script neon_client_runner.gd -- <session_id> <relay_address>[/code]

extends SceneTree


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() < 2:
		print("CONNECT_FAILED")
		quit(1)
		return

	var session_id := int(args[0])
	var relay := args[1]

	var client := NeonClient.new("godot-client")
	client.set_unhandled_packet_callback(func(type_byte: int, sender: int, payload: PackedByteArray) -> void:
		print("PACKET_RECEIVED:%d:%d" % [type_byte, sender])
	)

	var ok := client.join(session_id, relay)
	if not ok:
		print("CONNECT_FAILED")
		quit(1)
		return

	print("CONNECTED:%d" % client.current_client_id)

	var run_thread := Thread.new()
	run_thread.start(func() -> void:
		client.run()
	)

	OS.delay_msec(300)
	client.send_packet(PackedByteArray([0x42]), 0x10, 1)
	print("PACKET_SENT")

	OS.delay_msec(1500)
	if client.is_running():
		client.stop()

	quit(0)
