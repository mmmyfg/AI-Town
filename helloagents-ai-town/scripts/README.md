# Godot 脚本说明

这个目录保存的是 Godot 客户端主要脚本。当前版本已经不是“直接请求 FastAPI”的老结构，而是默认连到本地 `Java Gateway`。

## 脚本列表

```text
scripts/
├── config.gd
├── api_client.gd
├── player.gd
├── npc.gd
├── dialogue_ui.gd
└── main.gd
```

## 当前配置重点

### `config.gd`

主要保存全局常量：

```gdscript
const API_BASE_URL = "http://localhost:8080"
const API_CHAT = API_BASE_URL + "/api/chat"
const API_CHAT_RESULT = API_BASE_URL + "/api/chat/result/"
const API_NPCS = API_BASE_URL + "/api/npcs"
const API_NPC_STATUS = API_BASE_URL + "/api/npcs/status"
const WS_CHAT_URL = "ws://localhost:8080/ws/chat"
```

当前 NPC 名字为：

- `牧濑红莉栖`
- `雪之下雪乃`
- `仪玄`

### `api_client.gd`

负责和 Java 网关通信：

- 发送聊天任务 `send_chat(...)`
- 轮询聊天结果 `GET /api/chat/result/{taskId}`
- 拉取 NPC 状态 `get_npc_status()`
- 拉取 NPC 列表 `get_npc_list()`
- 连接 WebSocket `ws://localhost:8080/ws/chat`

主要信号：

- `chat_response_received`
- `chat_error`
- `npc_status_received`
- `npc_list_received`
- `chat_task_accepted`
- `affinity_changed`
- `mood_changed`

## 角色与交互脚本

### `player.gd`

- 继承 `CharacterBody2D`
- 处理移动、交互和输入屏蔽
- 通过 `E` 键或 `Enter` 键与附近 NPC 交互
- 进入对话时会禁用移动

节点要求：

```text
Player (CharacterBody2D)
├── AnimatedSprite2D
├── CollisionShape2D
├── Camera2D
├── InteractSound       # 可选
└── RunningSound        # 可选
```

### `npc.gd`

- 继承 `CharacterBody2D`
- 支持巡逻、交互范围检测、对话气泡、情绪变化
- 可响应 `mood_changed` 信号切换视觉效果

节点要求：

```text
NPC (CharacterBody2D)
├── AnimatedSprite2D
├── InteractionArea (Area2D)
│   └── CollisionShape2D
├── NameLabel (Label)
├── DialogueLabel (Label)
├── InteractionHint (Label)   # 可选
└── MoodBubble (Sprite2D)     # 可选
```

导出参数包括：

- `npc_name`
- `npc_title`
- `sprite_frames`
- `move_speed`
- `wander_enabled`

### `dialogue_ui.gd`

- 管理对话框显示和关闭
- 显示玩家与 NPC 的聊天记录
- 发送消息后展示“NPC 正在思考”
- 收到结果后更新文本

节点要求：

```text
DialogueUI (CanvasLayer)
└── Panel
    ├── NPCName (Label)
    ├── NPCTitle (Label)
    ├── DialogueText (RichTextLabel)
    ├── PlayerInput (LineEdit)
    ├── SendButton (Button)
    └── CloseButton (Button)
```

### `main.gd`

- 主场景协调脚本
- 定时拉取 NPC 状态
- 将后端返回的台词分发给对应 NPC 节点

当前写死映射的角色有：

- `牧濑红莉栖`
- `雪之下雪乃`
- `仪玄`

场景中对应节点路径默认是：

```text
Main (Node2D)
├── NPCs
│   ├── 牧濑红莉栖
│   ├── 雪之下雪乃
│   └── 仪玄
├── Player
└── DialogueUI
```

## 使用方式

### AutoLoad

在 `Project Settings -> AutoLoad` 中添加：

- `scripts/config.gd` 作为 `Config`
- `scripts/api_client.gd` 作为 `APIClient`

### 场景挂载

- `player.tscn` 挂载 `player.gd`
- `npc.tscn` 挂载 `npc.gd`
- `dialogue_ui.tscn` 挂载 `dialogue_ui.gd`
- `main.tscn` 挂载 `main.gd`

## 注意

- 如果你把 Java 网关端口改掉了，需要同步修改 `config.gd`
- 如果你改了 NPC 名字，`config.gd`、`main.gd` 和场景节点名要保持一致
- 当前 `api_client.gd` 里 `npc_status_received` 和 `npc_list_received` 的 HTTP 回调还是空实现，后续如果你要补完整功能，可以优先从这里继续
