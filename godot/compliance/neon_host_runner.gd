#!/usr/bin/env -S godot --headless --script
## Compliance test host runner.
##
## Starts a [NeonHost] for the given session and waits for client activity.
## Prints protocol events to stdout for the test harness to parse.
## Shuts down when stdin closes or after 30 seconds, whichever comes first.
##
## Usage: [code]godot --headless --script neon_host_runner.gd -- <session_id> <relay_address>[/code]

extends SceneTree

var _host: NeonHost = null
var _stdin_thread: Thread = null


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() < 2:
		print("HOST_FAILED")
		quit(1)
		return

	var session_id := int(args[0])
	var relay := args[1]

	_host = NeonHost.new(session_id, relay)
	_host.set_client_connect_callback(func(cid: int, name: String, sid: int) -> void:
		print("CLIENT_CONNECTED:%d:%s" % [cid, name])
	)
	_host.set_unhandled_packet_callback(func(type_byte: int, sender: int) -> void:
		print("PACKET_RECEIVED:%d:%d" % [type_byte, sender])
	)

	var host_thread := Thread.new()
	host_thread.start(func() -> void:
		_host.start_and_run()
	)

	var deadline := Time.get_ticks_msec() + 10000
	while not _host.is_running() and Time.get_ticks_msec() < deadline:
		OS.delay_msec(50)

	if not _host.is_running():
		print("HOST_FAILED")
		quit(1)
		return

	print("HOST_READY")

	_stdin_thread = Thread.new()
	_stdin_thread.start(func() -> void:
		var f := FileAccess.open("/dev/stdin", FileAccess.READ)
		if f != null:
			f.get_as_text()
		else:
			OS.delay_msec(30000)
		call_deferred("_shutdown")
	)

	var timer := Timer.new()
	timer.wait_time = 30.0
	timer.one_shot = true
	timer.timeout.connect(_shutdown)
	get_root().add_child(timer)
	timer.start()


func _shutdown() -> void:
	if _host != null and _host.is_running():
		_host.stop()
	quit(0)
