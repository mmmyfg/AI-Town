# 赛博小镇 - AI NPC 对话系统

这是一个由 Godot 前端、Spring Boot 网关和 Python AI 推理服务组成的多模块项目，用来驱动小镇里的 NPC 对话、情绪和记忆表现。

## 项目结构

```text
Helloagents-AI-Town/
├── backend/                 # Python FastAPI 推理服务 + RabbitMQ 消费端
├── helloagents-ai-town/     # Godot 4 客户端项目
├── java-gateway/            # Spring Boot 网关，负责对外 API / MQ / Redis / WebSocket
├── DIALOGUE_PLANNER_EXECUTOR_GUIDE.md
├── SETUP_GUIDE.md
├── DIALOGUE_LOG_GUIDE.md
├── AFFINITY_SYSTEM_GUIDE.md
└── MEMORY_SYSTEM_GUIDE.md
```

## 当前架构

```text
Godot 客户端
  -> http://localhost:8080 / ws://localhost:8080
Java Gateway
  -> RabbitMQ / Redis / MySQL
Python backend
  -> Groq(OpenAI 兼容接口) + LangChain + Chroma
```

## 当前功能

- 3 个可交互 NPC：`牧濑红莉栖`、`雪之下雪乃`、`仪玄`
- 玩家与 NPC 对话
- NPC 巡逻、交互、气泡台词刷新
- 异步聊天任务：HTTP 提交，轮询或 WebSocket 收取结果
- 玩家—NPC 维度的好感度与情绪状态联动
- Redis 会话短期记忆 + Chroma 隔离长期记忆
- MySQL 任务状态机、会话消息、关系状态与事务 Outbox
- RabbitMQ Publisher Confirm、消费幂等、分级重试、DLQ 与超时补偿
- HTTP 轮询兜底 + 玩家级 WebSocket 定向推送

## 目录说明

### `helloagents-ai-town`

- Godot 4 项目
- 主要脚本在 `scripts/`
- 主要场景在 `scenes/`
- 通过 `AutoLoad` 加载 `Config` 和 `APIClient`
- 默认连接 `http://localhost:8080`

### `java-gateway`

- Spring Boot 3.2 / Java 17
- 统一对外提供聊天、NPC 状态、WebSocket 等接口
- 依赖 MySQL、Redis、RabbitMQ
- 当前仓库里能看到 `pom.xml` 和 `application.yml`

### `backend`

- FastAPI 提供 AI 推理接口
- `mq_consumer.py` 负责消费聊天任务队列
- `langchain_agent.py` 负责 LLM、工具调用和记忆检索
- 默认监听 `http://localhost:8000`

## 关键接口与端口

- Godot -> Java:
  - `POST /api/chat`
  - `GET /api/chat/result/{taskId}`
  - `GET /api/npcs`
  - `GET /api/npcs/status`
  - `WS /ws/chat`
- Java -> Python:
  - `POST /agent/react`
  - `POST /agent/batch_react`
  - `GET /health`

## 启动顺序

1. 启动基础设施：MySQL、Redis、RabbitMQ
2. 启动 Python 服务：`backend/main.py`
3. 启动 Python 消费端：`backend/mq_consumer.py`
4. 启动 Java 网关：`java-gateway`
5. 用 Godot 打开 `helloagents-ai-town/project.godot` 并运行

## 文档

- [安装配置指南](./SETUP_GUIDE.md)
- [可靠 Agent 后端设计](./RELIABLE_AGENT_BACKEND.md)
- [对话版 Planner-Executor 设计稿](./DIALOGUE_PLANNER_EXECUTOR_GUIDE.md)
- [对话日志系统](./DIALOGUE_LOG_GUIDE.md)
- [好感度系统](./AFFINITY_SYSTEM_GUIDE.md)
- [记忆系统](./MEMORY_SYSTEM_GUIDE.md)

## 说明

仓库里仍然保留了一些旧版示例文件或旧思路文档，但当前代码实际运行方式以上面的模块说明为准。
