# 对话版 Planner-Executor 设计稿

状态：第一阶段已在 `backend/langchain_agent.py` 落地，实现了轻量 `Intent Router -> Planner -> Executor -> Final Responder` 主流程。

这份设计稿面向当前项目的真实玩法：玩家走到 NPC 面前后发起对话，NPC 平时可随机走动，但系统暂时不引入复杂任务、寻路或开放世界行为树。

目标是只基于“聊天”增强 NPC 的智能感，在现有 `Godot -> Java -> Python` 架构上增加一个最小可运行的 Planner-Executor 对话层。

## 设计目标

- 不改变当前 Godot 交互方式
- 不增加复杂的任务系统和地图行为逻辑
- 保留当前短期记忆、长期记忆、好感度和情绪机制
- 在“需要时”才触发 planning，而不是每句话都走复杂流程
- 让 NPC 支持多步工具调用，提升“会查、会想、会回忆”的对话体验
- 保持项目适合简历展示，突出 LangChain / Agent / Tool / Memory 能力

## 适用对话场景

这个规划层只处理以下类型的对话：

- 信息查询型
  - “你现在心情怎么样？”
  - “你是谁？你平时做什么？”
- 回忆型
  - “你还记得我上次说了什么吗？”
  - “我们之前是不是聊过天气？”
- 社交关系型
  - “我们现在关系怎么样？”
  - “我是不是惹你生气了？”
- 建议型
  - “我该怎么和你更好相处？”
  - “现在和你聊天合适吗？”

普通闲聊仍然走现有快速回复链路。

## 总体架构

```text
Godot
  -> Java Gateway
  -> Python mq_consumer.py
  -> LangChain Dialogue Agent
       ├── Intent Router
       ├── Planner
       ├── Executor
       ├── Memory Retriever
       └── Final Responder
  -> Redis / RabbitMQ
  -> Java Gateway
  -> Godot
```

## 最小流程

```text
1. 玩家发来一句话
2. Intent Router 判断：
   - 普通闲聊
   - 单工具查询
   - 多步 planning
3. 如果是普通闲聊：
   - 直接沿用当前 react() 逻辑
4. 如果需要 planning：
   - Planner 生成结构化 plan
   - Executor 按 step 逐步执行工具
   - Final Responder 结合执行结果生成 NPC 最终台词
5. 把最终回复写回短期记忆和 Chroma 长期记忆
```

## 建议增加的工具

### 1. `get_current_weather(city)`

用途：

- 回答天气相关问题
- 给出聊天建议、出行建议

当前项目里已经存在，可保留并纳入统一工具注册。

建议返回结构：

```json
{
  "success": true,
  "tool": "get_current_weather",
  "city": "南京",
  "weather": "明天有阵雨，气温 18-24°C，空气湿度较高。"
}
```

### 2. `get_npc_profile(npc_id)`

用途：

- 让 NPC 查询自己的身份、职位、性格、背景
- 回答“你是谁”“你喜欢什么”“你现在什么状态”这类问题

当前实现：

- 第一阶段已由 Python 侧本地画像字典提供兜底数据
- 后续可升级为 Java 层从 MySQL 的 `Npc` 实体透传

建议返回结构：

```json
{
  "success": true,
  "tool": "get_npc_profile",
  "npc_id": "雪之下雪乃",
  "title": "冰之女王",
  "personality": "高冷、克制、毒舌但理性",
  "background": "擅长观察和判断他人情绪",
  "current_mood": "CALM",
  "affinity": 62
}
```

### 3. `get_recent_memory(player_id, npc_id)`

用途：

- 回答“你还记得我吗”
- 给回复提供近期互动参考
- 展示项目已有的记忆能力

当前实现：

- 第一阶段已在 Python 中直接封装 `vector_db` 检索
- 优先按 `player_id + npc_id` 精确过滤，失败时降级到仅按 `npc_id`

建议返回结构：

```json
{
  "success": true,
  "tool": "get_recent_memory",
  "player_id": "player_1",
  "npc_id": "雪之下雪乃",
  "memories": [
    "玩家对我说：你今天心情还好吗。我当时的回复是：还算平静。",
    "玩家对我说：上次那本书怎么样。我当时的回复是：内容尚可。"
  ]
}
```

### 4. `get_affinity_status(player_id, npc_id)`

用途：

- 回答关系状态问题
- 给出提升好感度的建议
- 让 NPC 对玩家的“关系感”更强

当前实现：

- 第一阶段先用 Python 进程内缓存维护当前会话好感度与最近变化原因
- 后续可升级为 Java / MySQL 提供真实关系数据

建议返回结构：

```json
{
  "success": true,
  "tool": "get_affinity_status",
  "player_id": "player_1",
  "npc_id": "雪之下雪乃",
  "current_affinity": 62,
  "affinity_level": "信任",
  "recent_reason": "最近几次对话较礼貌，并主动关心她的状态。"
}
```

## Planner 输出格式

Planner 不直接生成最终台词，而是先输出一个最小结构化计划。

建议 JSON：

```json
{
  "need_planning": true,
  "goal": "回答玩家关于当前关系状态的问题",
  "strategy": "先读取好感度，再结合近期记忆组织回复",
  "steps": [
    {
      "step_id": 1,
      "type": "tool",
      "tool": "get_affinity_status",
      "args": {
        "player_id": "player_1",
        "npc_id": "雪之下雪乃"
      },
      "reason": "先确认当前关系强弱"
    },
    {
      "step_id": 2,
      "type": "tool",
      "tool": "get_recent_memory",
      "args": {
        "player_id": "player_1",
        "npc_id": "雪之下雪乃"
      },
      "reason": "结合近期互动说明原因"
    },
    {
      "step_id": 3,
      "type": "respond",
      "tool": null,
      "args": {},
      "reason": "结合执行结果生成符合人设的回复"
    }
  ]
}
```

## 普通聊天与 Planning 的分流

建议增加一个轻量 `Intent Router`。

输出示例：

```json
{
  "route": "direct"
}
```

或：

```json
{
  "route": "plan"
}
```

建议判定规则：

- 普通问候、短闲聊、情绪寒暄：`direct`
- 需要查询、回忆、解释关系、给建议：`plan`

这样可以避免每一句话都走 Planner，降低成本和复杂度。

## Executor 输出格式

Executor 每执行一步，都把结果记录下来，最后统一交给最终回复生成器。

建议结果结构：

```json
{
  "goal": "回答玩家关于当前关系状态的问题",
  "step_results": [
    {
      "step_id": 1,
      "tool": "get_affinity_status",
      "result": {
        "current_affinity": 62,
        "affinity_level": "信任"
      }
    },
    {
      "step_id": 2,
      "tool": "get_recent_memory",
      "result": {
        "memories": [
          "玩家上次主动关心了我的状态。",
          "玩家没有表现出冒犯。"
        ]
      }
    }
  ]
}
```

## 最终回复生成

最终回复仍由 LLM 生成，但它的输入从“用户一句话 + 当前记忆”，升级为：

- NPC 角色设定
- 当前情绪
- 短期记忆
- 长期记忆
- Planner goal
- Executor step_results
- 玩家原始消息

建议输出仍保持当前项目兼容格式：

```json
{
  "reply": "从最近的互动来看，我对你并不反感。至少，你现在还算会说话。",
  "affinity_change": 0
}
```

## 与当前项目的结合方式

### Godot 层

不需要改交互模式。

仍然保持：

- 玩家靠近 NPC
- 按键发起对话
- 输入一句话
- 显示“NPC 正在思考”
- 收到结果后显示回复

第一阶段不要求 Godot 展示 plan 细节。

### Java 层

第一阶段尽量少改。

建议保持：

- 继续接收 Godot 请求
- 继续投递 MQ 任务
- 继续读取结果

如果后续要增强，可以在 Java 中额外提供：

- `GET /api/npc/profile/{npcId}`
- `GET /api/npc/affinity/{playerId}/{npcId}`

但第一版可以先不依赖它们。

### Python 层

这是第一阶段主要改动区。

建议把当前 `LangChainNPCAgent` 拆成：

- `route_dialogue(...)`
- `plan_dialogue(...)`
- `execute_plan(...)`
- `generate_direct_reply(...)`
- `generate_planned_reply(...)`

## 建议代码改造点

### 1. `backend/langchain_agent.py`

当前文件建议新增：

- `DialoguePlanStep` 数据结构
- `DialoguePlan` 数据结构
- `ExecutionTrace` 数据结构
- 4 个工具函数
- `route_dialogue`
- `plan_dialogue`
- `execute_plan`
- `generate_planned_reply`

当前 `react(...)` 可改成总入口：

```text
react
  -> 读取短期记忆
  -> 检索长期记忆
  -> route_dialogue
       -> direct: generate_direct_reply
       -> plan: plan_dialogue -> execute_plan -> generate_planned_reply
  -> 写回短期记忆
  -> 写回长期记忆
```

### 2. `backend/main.py`

第一阶段可以不改接口。

仍然沿用：

- `POST /agent/react`

因为 planning 逻辑可以完全封装在 `react(...)` 内部。

### 3. `backend/mq_consumer.py`

第一阶段只需保持现有消息流。

可选增强：

- 把 `plan` 摘要写进 Redis，用于后续前端展示
- 把 `execution_trace` 打到日志里，方便演示

## 第一阶段实现建议

建议按这个顺序做：

1. 统一工具返回格式
2. 实现 `get_npc_profile`
3. 实现 `get_recent_memory`
4. 实现 `get_affinity_status`
5. 新增 `route_dialogue`
6. 新增 `plan_dialogue`
7. 新增 `execute_plan`
8. 新增 `generate_planned_reply`
9. 接入现有 `react(...)`

## 第二阶段增强建议

第一阶段跑通后，可以继续增强：

- 给 Godot 增加“思考中：正在查询关系 / 正在回忆往事”的轻提示
- 把 `plan` 摘要透传到前端
- 把 Planner-Executor 迁移到 LangGraph
- 增加更多工具，如地点信息、礼物偏好、事件历史

## 简历表达建议

如果按这份设计实现完成，可以这样描述：

- 基于 LangChain 设计对话版 Planner-Executor 架构，使 NPC 能根据玩家目标生成结构化行动计划并逐步执行
- 为 NPC 增加多工具调用能力，支持天气查询、角色画像读取、近期记忆检索与关系状态分析
- 在不改变游戏核心交互的前提下，将单轮闲聊升级为具备轻量规划与社交推理能力的对话 Agent
- 结合 Chroma 长期记忆、RabbitMQ 异步任务与 Redis 结果缓存，构建可落地的游戏 AI 服务链路

## 当前定位

这份方案刻意保持“轻量”：

- 不引入地图规划
- 不引入行为树
- 不引入复杂任务系统
- 只增强对话体验

它非常适合作为当前项目的下一阶段演进方向。
