extends SceneTree

func _initialize() -> void:
	call_deferred("_run")

func _run() -> void:
	var ui = load("res://scenes/dialogue_ui.tscn").instantiate()
	root.add_child(ui)
	await process_frame
	ui.start_dialogue("牧濑红莉栖")
	ui.scene_button.pressed.emit()
	if not ui.group_mode or ui.companion.item_count < 1:
		push_error("Scene button did not enable multi-NPC dialogue")
		quit(1)
		return
	ui.is_waiting_for_reply = true
	ui._on_scene_response_received([
		{"npcId": "牧濑红莉栖", "round": 1, "turn": 1, "reply": "先看数据。", "status": "COMPLETED"},
		{"npcId": "雪之下雪乃", "round": 1, "turn": 2, "reply": "我同意先核对。", "status": "COMPLETED"},
		{"npcId": "牧濑红莉栖", "round": 2, "turn": 1, "reply": "数据已经核对。", "status": "COMPLETED"},
		{"npcId": "雪之下雪乃", "round": 2, "turn": 2, "reply": "那就继续。", "status": "COMPLETED"},
	])
	var content: String = ui.dialogue_text.get_parsed_text()
	if not content.contains("第 1 轮") or not content.contains("第 2 轮") \
			or content.find("先看数据") >= content.find("我同意先核对") \
			or content.find("我同意先核对") >= content.find("数据已经核对") \
			or ui.is_waiting_for_reply:
		push_error("Scene turns were not shown in order")
		quit(1)
		return
	print("GODOT_SCENE_UI_SMOKE_OK")
	quit(0)
