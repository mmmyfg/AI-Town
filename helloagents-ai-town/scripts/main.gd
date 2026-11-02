# 主场景脚本 (MainScene.gd)
extends Node2D

# ==================== NPC 节点引用 (路径已修正) ====================
# 注意：这里的 $NPCs/xxx 必须与你左侧场景面板里的节点名字完全一致
@onready var npc_kurisu: Node2D = $NPCs/牧濑红莉栖
@onready var npc_yukino: Node2D = $NPCs/雪之下雪乃
@onready var npc_yixuan: Node2D = $NPCs/仪玄

# API 客户端引用
var api_client: Node = null

# NPC 状态更新计时器
var status_update_timer: float = 0.0

func _ready():
	print("[INFO] 主场景初始化 - 动漫角色路径已校准")
	
	# 获取自动加载的 APIClient 节点
	api_client = get_node_or_null("/root/APIClient")
	if api_client:
		# 连接收到 NPC 状态时的信号
		if api_client.has_signal("npc_status_received"):
			api_client.npc_status_received.connect(_on_npc_status_received)
		
		# 立即获取一次所有 NPC 的初始状态（如气泡对话）
		api_client.get_npc_status()
	else:
		print("[ERROR] 无法找到 APIClient 节点，请检查‘项目设置->自动加载’")

func _process(delta: float):
	# 根据 Config 中设置的时间间隔定时请求 NPC 状态更新
	status_update_timer += delta
	if status_update_timer >= Config.NPC_STATUS_UPDATE_INTERVAL:
		status_update_timer = 0.0
		if api_client:
			api_client.get_npc_status()

func _on_npc_status_received(dialogues: Dictionary):
	"""收到来自 Java 后端的 NPC 状态/对话更新"""
	print("[INFO] 批量更新 NPC 状态: ", dialogues)
	
	# 遍历返回的字典，更新场景中对应 NPC 的显示内容
	for npc_name in dialogues:
		var dialogue = dialogues[npc_name]
		update_npc_dialogue(npc_name, dialogue)

func update_npc_dialogue(npc_name: String, dialogue: String):
	"""查找对应的 NPC 节点并调用其对话更新方法"""
	var npc_node = get_npc_node(npc_name)
	if npc_node and npc_node.has_method("update_dialogue"):
		npc_node.update_dialogue(dialogue)
	else:
		print("[WARN] 无法更新对话：找不到节点或方法缺失 -> ", npc_name)

func get_npc_node(npc_name: String) -> Node2D:
	"""
	【核心逻辑】根据数据库返回的 npc_id 匹配场景变量。
	字符串必须与数据库 npcs 表中的 npc_id 字段 100% 对应。
	"""
	match npc_name:
		"牧濑红莉栖":
			return npc_kurisu
		"雪之下雪乃":
			return npc_yukino
		"仪玄":
			return npc_yixuan
		_:
			# 兜底：如果手动匹配失败，尝试在 'npcs' 组中全局搜索
			return find_npc_in_group(npc_name)

# 💡 动态搜索函数：只要 NPC 在 'npcs' 组且 npc_name 变量正确，就能找到
func find_npc_in_group(target_name: String) -> Node2D:
	var npcs = get_tree().get_nodes_in_group("npcs")
	for npc in npcs:
		# 优先检查脚本里定义的 npc_name 变量
		if "npc_name" in npc and npc.npc_name == target_name:
			return npc
		# 其次检查节点本身的名字
		if npc.name == target_name:
			return npc
	return null
