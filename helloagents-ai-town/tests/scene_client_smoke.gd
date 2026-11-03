extends SceneTree

var received_messages: Array = []
var received_npcs: Array = []
var received_status: Dictionary = {}
var received_moods: Dictionary = {}

func _initialize() -> void:
	call_deferred("_run")

func _run() -> void:
	var client = root.get_node("APIClient")
	var deadline = Time.get_ticks_msec() + 60000
	while client.player_token.is_empty() and Time.get_ticks_msec() < deadline:
		await create_timer(0.2).timeout
	if client.player_token.is_empty():
		push_error("Guest identity was not created")
		quit(1)
		return
	client.npc_list_received.connect(_on_npc_list)
	client.npc_status_received.connect(_on_npc_status)
	client.mood_changed.connect(_on_mood_changed)
	client.get_npc_list()
	client.get_npc_status()
	while (received_npcs.is_empty() or received_status.is_empty()) and Time.get_ticks_msec() < deadline:
		await create_timer(0.2).timeout
	if received_npcs.size() < 2 or not received_status.has("牧濑红莉栖") \
			or not received_moods.has("牧濑红莉栖"):
		push_error("NPC list/status was not delivered to the Godot client")
		quit(1)
		return
	client.scene_response_received.connect(_on_scene_response)
	client.send_scene(["牧濑红莉栖", "雪之下雪乃"], "Godot 客户端联调：一起去咖啡厅。")
	while client.pending_task_id.is_empty() and Time.get_ticks_msec() < deadline:
		await create_timer(0.2).timeout
	if client.pending_task_id.is_empty():
		push_error("Scene task was not accepted")
		quit(1)
		return
	# Force WebSocket offline so the scene must complete through HTTP polling.
	if client.websocket.get_ready_state() == WebSocketPeer.STATE_OPEN:
		client.websocket.close(1000, "polling fallback test")
	client.websocket = WebSocketPeer.new()
	client.websocket_connected = false
	client.websocket_retry_seconds = -100.0
	while received_messages.is_empty() and Time.get_ticks_msec() < deadline:
		await create_timer(0.2).timeout
	if received_messages.size() != 2 or received_messages[0].get("npcId") != "牧濑红莉栖" \
			or received_messages[1].get("npcId") != "雪之下雪乃":
		push_error("Scene response has wrong speakers")
		quit(1)
		return
	if not client.pending_task_id.is_empty():
		push_error("Pending scene was not cleared after polling result")
		quit(1)
		return
	client.websocket_retry_seconds = 5.0
	var reconnect_deadline = Time.get_ticks_msec() + 10000
	while not client.websocket_connected and Time.get_ticks_msec() < reconnect_deadline:
		await create_timer(0.2).timeout
	if not client.websocket_connected:
		push_error("WebSocket did not reconnect after polling fallback")
		quit(1)
		return
	print("GODOT_SCENE_SMOKE_OK")
	quit(0)

func _on_scene_response(messages: Array) -> void:
	received_messages = messages

func _on_npc_list(npcs: Array) -> void:
	received_npcs = npcs

func _on_npc_status(status: Dictionary) -> void:
	received_status = status

func _on_mood_changed(npc_name: String, mood: String) -> void:
	received_moods[npc_name] = mood
