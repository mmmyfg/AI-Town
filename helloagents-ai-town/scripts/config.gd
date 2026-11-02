# 赛博小镇 - 全局配置
extends Node

# ==================== API配置 ====================
const API_BASE_URL = "http://localhost:8080"
const API_CHAT = API_BASE_URL + "/api/chat"
const API_CHAT_RESULT = API_BASE_URL + "/api/chat/result/"
const API_NPCS = API_BASE_URL + "/api/npcs"
const API_NPC_STATUS = API_BASE_URL + "/api/npcs/status"
const WS_CHAT_URL = "ws://localhost:8080/ws/chat?playerId=player_1"
const CHAT_RESULT_POLL_INTERVAL = 1.0

# ==================== NPC配置 (已更新为动漫女神版) ====================
# 注意：这里的名字必须和数据库里的 npc_id 字段完全一致！
const NPC_NAMES = ["牧濑红莉栖", "雪之下雪乃", "仪玄"]

const NPC_TITLES = {
	"牧濑红莉栖": "天才助手",
	"雪之下雪乃": "冰之女王",
	"仪玄": "云岿山门主"
}

# ==================== 游戏配置 ====================
const PLAYER_SPEED = 200.0  # 玩家移动速度
const INTERACTION_DISTANCE = 80.0  # 交互距离
const NPC_STATUS_UPDATE_INTERVAL = 30.0  # NPC状态更新间隔(秒)

# ==================== UI配置 ====================
const DIALOGUE_FADE_TIME = 0.3  # 对话框淡入淡出时间
const NPC_LABEL_OFFSET = Vector2(0, -60)  # NPC名字标签偏移

# ==================== 调试配置 ====================
const DEBUG_MODE = true  # 调试模式
const SHOW_INTERACTION_RANGE = true  # 显示交互范围

# ==================== 工具函数 ====================
func log_info(message: String) -> void:
	if DEBUG_MODE:
		print("[INFO] ", message)

func log_error(message: String) -> void:
	print("[ERROR] ", message)

func log_api(endpoint: String, data: Dictionary) -> void:
	if DEBUG_MODE:
		print("[API] ", endpoint, " -> ", JSON.stringify(data))
