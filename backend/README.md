# backend - Python AI 推理服务

这个目录不是直接给 Godot 用的业务后端，而是当前项目里的 Python 推理节点，主要负责 NPC 对话生成、记忆检索，以及消费 Java 网关投递过来的聊天任务。

## 当前目录结构

```text
backend/
├── main.py               # FastAPI 推理服务
├── mq_consumer.py        # RabbitMQ 消费端，处理聊天任务
├── langchain_agent.py    # LLM、工具调用、短期/长期记忆逻辑
├── view_logs.py          # 日志查看脚本
├── requirements.txt      # Python 依赖
├── .env / .env.example   # 环境变量示例
├── chroma_db/            # Chroma 长期记忆数据
├── memory_data/          # 记忆数据目录
└── logs/                 # 对话或运行日志
```

## 服务职责

### `main.py`

提供 FastAPI 接口，默认端口 `8000`：

- `POST /agent/react`
  - 单个 NPC 反应生成
  - 当前已内置轻量 `Intent Router -> Planner -> Executor -> Final Responder` 对话流程
- `POST /agent/batch_react`
  - 批量生成多个 NPC 的状态台词
- `GET /health`
  - 健康检查

### `mq_consumer.py`

- 监听 RabbitMQ 队列 `chat_task_queue`
- 调用 `LangChainNPCAgent.react(...)`
- 将任务状态写入 Redis
- 把结果投递到 `chat_result_queue`
- 使用消费租约避免同一任务并行推理
- 失败后按 5/15/45 秒退避重试，超过上限进入 DLQ
- 发布状态和结果时等待 RabbitMQ Publisher Confirm

## 运行链路

```text
Godot
  -> Java Gateway (/api/chat)
  -> RabbitMQ chat_task_queue
  -> mq_consumer.py
  -> LangChainNPCAgent
  -> Redis / chat_result_queue
  -> Java Gateway
  -> Godot
```

## 依赖

`requirements.txt` 当前包含：

- `fastapi`
- `uvicorn`
- `pydantic`
- `langchain`
- `langchain-openai`
- `langchain-huggingface`
- `python-dotenv`
- `pika`
- `redis`

另外本项目实际还会用到：

- DeepSeek OpenAI 兼容接口
- RabbitMQ
- Redis
- Chroma 本地向量库
- HuggingFace Embeddings

当前 `LangChainNPCAgent` 第一阶段已支持：

- 普通闲聊直接回复
- 基于 Embedding 与关键词兜底的对话路由
- 对话版 Planner-Executor
- 多步工具调用：
  - `get_current_weather`
  - `get_npc_profile`
  - `get_recent_memory`
  - `get_affinity_status`

## 环境变量

当前代码实际读取的关键变量主要有：

```env
NPC_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=your-deepseek-api-key
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DB=0
```

说明：

- `langchain_agent.py` 当前默认走 DeepSeek 的 OpenAI 兼容接口；本地测试使用 `deepseek-flash` 非思考模式
- `mq_consumer.py` 会读取 RabbitMQ 和 Redis 地址
- `.env.example` 已包含推理超时、重试、短期记忆和长期记忆策略参数

## 启动方式

### 1. 安装依赖

```bash
cd backend
pip install -r requirements.txt
```

### 2. 启动 FastAPI 推理服务

```bash
python main.py
```

默认地址：

- `http://localhost:8000/health`
- `http://localhost:8000/docs`

### 3. 启动 RabbitMQ 消费端

```bash
python mq_consumer.py
```

## 接口示例

### `POST /agent/react`

```json
{
  "player_id": "player_1",
  "npc_id": "雪之下雪乃",
  "personality": "你是雪之下雪乃，性格高冷，毒舌。",
  "observation": "你现在的状态是：在小镇广场。你当前的心情是：【CALM】。玩家对你说：你好。",
  "memory": ""
}
```

返回示例：

```json
{
  "success": true,
  "reply": "{\"reply\":\"……你好，有事就直说。\",\"affinity_change\":0}"
}
```

### `POST /agent/batch_react`

```json
{
  "context": "当前是傍晚，小镇广场比较安静。",
  "npc_list": "牧濑红莉栖, 雪之下雪乃, 仪玄"
}
```

## 注意事项

- 这个目录里的 README 已按当前代码结构重写，不再沿用旧版 `NPC 列表 /chat /npcs` 的描述
- 现在 Godot 默认不直接连这个服务，而是先走 `java-gateway`
- 如果只启动 `main.py` 而不启动 `mq_consumer.py`，异步聊天链路不会完整工作
- 如果你准备把当前 NPC 对话升级成多步工具调用 + 轻量 planning，可以参考上层文档 [DIALOGUE_PLANNER_EXECUTOR_GUIDE.md](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/DIALOGUE_PLANNER_EXECUTOR_GUIDE.md)
