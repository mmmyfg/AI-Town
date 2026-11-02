# API客户端 - 与Java Spring Boot网关通信
extends Node

# 信号定义
signal chat_response_received(npc_name: String, message: String)
signal chat_error(error_message: String)
signal npc_status_received(dialogues: Dictionary)
signal npc_list_received(npcs: Array)
signal chat_task_accepted(npc_name: String, task_id: String)

# 好感度变动信号
signal affinity_changed(npc_name: String, change_amount: int, current_total: int)
# ✨ 【新增】情绪变动信号！NPC脚本只要听到这个信号就会切气泡
signal mood_changed(npc_name: String, new_mood: String)

# HTTP请求节点
var http_chat: HTTPRequest
var http_chat_result: HTTPRequest
var http_status: HTTPRequest
var http_npcs: HTTPRequest

# 记录当前正在聊天的NPC，方便收到回复时知道是谁
var current_chat_npc: String = ""
var pending_task_id: String = ""
var pending_task_npc: String = ""
var result_poll_timer: Timer
var websocket := WebSocketPeer.new()
var websocket_connected: bool = false

func _ready():
	# 创建HTTP请求节点
	http_chat = HTTPRequest.new()
	http_chat_result = HTTPRequest.new()
	http_status = HTTPRequest.new()
	http_npcs = HTTPRequest.new()
	
	add_child(http_chat)
	add_child(http_chat_result)
	add_child(http_status)
	add_child(http_npcs)

	result_poll_timer = Timer.new()
	result_poll_timer.wait_time = Config.CHAT_RESULT_POLL_INTERVAL
	result_poll_timer.one_shot = false
	add_child(result_poll_timer)
	
	# 连接信号
	http_chat.request_completed.connect(_on_chat_request_completed)
	http_chat_result.request_completed.connect(_on_chat_result_completed)
	http_status.request_completed.connect(_on_status_request_completed)
	http_npcs.request_completed.connect(_on_npcs_request_completed)
	result_poll_timer.timeout.connect(_poll_chat_result)
	
	print("[INFO] API客户端初始化完成")
	_connect_chat_websocket()

func _process(_delta: float) -> void:
	_poll_websocket()

# ==================== 核心对话 API (完美对接 Java 最新接口) ====================

func send_chat(npc_name: String, message: String, action_type: String = "CHAT", item_name: String = "") -> void:
	"""发送对话请求 (支持普通聊天与送礼)"""
	if not pending_task_id.is_empty():
		chat_error.emit("上一条消息还在处理中，请稍等")
		return

	current_chat_npc = npc_name # 记住咱们在跟谁说话
	
	# 打包成和 Java 的 GodotRequest 完全一致的格式
	var data = {
		"player_id": "player_1",
		"npc_id": npc_name,
		"message": message,
		"actionType": action_type,
		"itemName": item_name
	}
	
	var json_string = JSON.stringify(data)
	var headers = ["Content-Type: application/json"]
	
	print("[API] POST /chat -> ", data)
	
	var error = http_chat.request(
		Config.API_CHAT,
		headers,
		HTTPClient.METHOD_POST,
		json_string
	)
	
	if error != OK:
		print("[ERROR] 发送对话请求失败: ", error)
		chat_error.emit("网络请求失败")

func _on_chat_request_completed(_result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	"""处理对话响应"""
	if response_code != 200:
		print("[ERROR] 对话请求失败: HTTP ", response_code)
		chat_error.emit("服务器错误: " + str(response_code))
		return
	
	var json = JSON.new()
	var parse_result = json.parse(body.get_string_from_utf8())
	
	if parse_result != OK:
		print("[ERROR] 解析响应失败")
		chat_error.emit("响应解析失败")
		return
	
	var response = json.data
	
	if response.has("success") and response["success"]:
		var task_id = str(response.get("task_id", ""))
		if task_id.is_empty():
			chat_error.emit("服务器未返回任务ID")
			return
		
		pending_task_id = task_id
		pending_task_npc = current_chat_npc
		chat_task_accepted.emit(current_chat_npc, task_id)
		print("[INFO] 对话任务已入队: ", task_id)
		if result_poll_timer.is_stopped():
			result_poll_timer.start()
	else:
		# 提取后端返回的失败提示
		chat_error.emit(response.get("reply", "对话失败"))

func _poll_chat_result() -> void:
	if pending_task_id.is_empty():
		result_poll_timer.stop()
		return
	if http_chat_result.get_http_client_status() != HTTPClient.STATUS_DISCONNECTED:
		return
	http_chat_result.request(Config.API_CHAT_RESULT + pending_task_id)

func _connect_chat_websocket() -> void:
	var err = websocket.connect_to_url(Config.WS_CHAT_URL)
	if err != OK:
		print("[WARN] WebSocket 连接失败，继续使用 HTTP 轮询: ", err)
		return

func _poll_websocket() -> void:
	if websocket.get_ready_state() == WebSocketPeer.STATE_CLOSED:
		if websocket_connected:
			websocket_connected = false
			print("[WARN] WebSocket 已断开，继续使用 HTTP 轮询")
		return

	websocket.poll()

	if websocket.get_ready_state() == WebSocketPeer.STATE_OPEN and not websocket_connected:
		websocket_connected = true
		print("[INFO] WebSocket 已连接")

	while websocket.get_available_packet_count() > 0:
		var packet = websocket.get_packet()
		var text = packet.get_string_from_utf8()
		_handle_ws_message(text)

func _handle_ws_message(text: String) -> void:
	var json = JSON.new()
	if json.parse(text) != OK:
		return

	var response = json.data
	if not response.has("task_id"):
		return
	if str(response["task_id"]) != pending_task_id:
		return

	_finalize_chat_result(response)

func _on_chat_result_completed(_result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	if pending_task_id.is_empty():
		return
	if response_code != 200:
		print("[ERROR] 结果轮询失败: HTTP ", response_code)
		return

	var json = JSON.new()
	var parse_result = json.parse(body.get_string_from_utf8())
	if parse_result != OK:
		print("[ERROR] 解析结果响应失败")
		return

	var response = json.data
	var completed = bool(response.get("completed", false))
	if not completed:
		return

	_finalize_chat_result(response)

func _finalize_chat_result(response: Dictionary) -> void:
	result_poll_timer.stop()

	if response.has("success") and response["success"]:
		var msg = response.get("reply", "...")
		print("[INFO] 收到NPC回复: ", pending_task_npc, " -> ", msg)
		chat_response_received.emit(pending_task_npc, msg)

		if response.has("affinity_change") and response.has("current_affinity"):
			var change = int(response["affinity_change"])
			var current = int(response["current_affinity"])
			print("💖 [" + pending_task_npc + "] 好感度变动: ", change, " | 当前总好感: ", current)
			if change != 0:
				affinity_changed.emit(pending_task_npc, change, current)

		if response.has("mood"):
			var current_mood = str(response["mood"])
			print("🎭 [" + pending_task_npc + "] 情绪状态刷新: ", current_mood)
			mood_changed.emit(pending_task_npc, current_mood)
	else:
		chat_error.emit(response.get("reply", "对话失败"))

	pending_task_id = ""
	pending_task_npc = ""

# ==================== 以下为其他 API ====================
func get_npc_status() -> void:
	if http_status.get_http_client_status() != HTTPClient.STATUS_DISCONNECTED:
		return
	http_status.request(Config.API_NPC_STATUS)

func _on_status_request_completed(_result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	pass

func get_npc_list() -> void:
	http_npcs.request(Config.API_NPCS)

func _on_npcs_request_completed(_result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	pass
