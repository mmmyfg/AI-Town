# Cyber Town 2.0 升级交付记录

更新时间：2026-11-03。本文前面的性能数字来自改为按序接话之前的本机 Mock 角色模型测试；最新实现与验证见文末补充。

## 架构与实现

原有单 NPC 链路保持为 `Godot → Java /api/chat → MySQL 任务/消息/Outbox → RabbitMQ interactive → Python Character Agent → RabbitMQ result → Java 事务提交 → WebSocket/轮询`。单 NPC 不经过场景编排。

新增 `POST /api/chat/scene`：玩家指定 2 至 3 位不同 NPC，Java 校验 NPC 存在并读取各自的画像、关系值和情绪，生成带 `mode=group`、`participants`、`userMessage`、`traceId` 的任务。Python Scene Orchestrator 为每位 NPC 独立读取记忆，并行调用角色执行器，限制同时调用数、单角色超时、总超时、角色数与模型调用次数。输出按请求中的 NPC 顺序排列；单个失败时保留其他成功台词。确定性检查拒绝空文本、错误身份、非法好感度值和未批准的工具调用。Java 在一个事务中提交场景发言和关系变化，发言含服务端 `sequence`；客户端按该顺序显示姓名与状态。

玩家身份采用项目内签名访客令牌，因为当前没有外部登录接口。`POST /api/player/guest` 签发 `player_id`、令牌和到期时间；Godot 保存会话并在聊天、结果查询、WebSocket 连接中带上令牌。Java 从令牌确定玩家 ID，拒绝请求体冒充其他玩家，结果查询按任务归属检查。访客身份不提供账户找回、跨设备同步或正式账号登录；公开部署前仍需接入正式身份服务、HTTPS/WSS 和入口限流。

客户端的 `client_request_id` 在 MySQL 中按玩家唯一，重复请求返回原任务，若请求内容改变则拒绝。RabbitMQ 消费保留原有 Redis 认领、Publisher Confirm、手动 ACK、重试、死信和任务补偿。关系值通过 MySQL 原子增量更新，避免并发请求互相覆盖；会话消息使用数据库分配的递增序号。NPC 实时心情从现有 Redis FSM 状态读取，不用旧的关系行心情覆盖新值。WebSocket 发送交给有界线程池，队列拒绝时客户端依赖轮询补齐。

新增独立 `chat_background_queue`、30 秒重试队列和 DLQ。成功提交的对话产生记忆 Outbox 事件，由 `background_consumer.py` 异步筛选、去重并写入 Chroma；来源 ID 和 Redis 完成标记用于重投幂等，记忆失败不会撤销对话结果。后台自主互动默认关闭；开启后 Java 定时选择非双睡眠的 NPC 组合，Redis 原子限制每日次数及组合冷却，产生一轮且至多两位角色的后台场景任务。超时补偿仍投后台队列。

## API、迁移与配置

- `POST /api/player/guest`：返回 `player_id`、`token`、`expires_at`。
- `POST /api/chat`：保留原路径，新增可选 `client_request_id`；需要 `Authorization: Bearer <token>`。
- `POST /api/chat/scene`：请求为 `npc_ids`、`message`、可选 `client_request_id`；响应含 `task_id`、`conversation_id`、`status`。
- `GET /api/chat/result/{taskId}`：场景完成时另返回 `mode=group`、`scene_messages`；每项含 `npcId`、`reply`、`affinityChange`、`mood`、`status`、成功发言的 `sequence`。WebSocket 推送复用该结果格式。

新数据库自动执行 `docker/mysql-init/003_scene_messages.sql` 和 `004_client_request_keys.sql`。**已在本机现有开发数据库执行并核对**。现有部署需先备份，再手工执行两个脚本；003 将消息唯一键由 `(task_id, role)` 扩展为 `(task_id, role, npc_id)`，004 新建请求幂等表。回滚 003 前须清理多 NPC 发言，否则原唯一键无法重建；回滚 004 会删除请求重放映射。不要在已有数据卷上假定 Docker 初始化脚本会自动重跑。

根目录 `.env` 已配置本机 `MYSQL_PASSWORD` 和随机生成的 `PLAYER_TOKEN_SECRET`，并被 Git 忽略；模板见 `.env.example`。生产环境必须设置稳定的令牌密钥。默认 `AUTH_REQUIRED=true`、`NPC_AUTONOMOUS_ENABLED=false`。配置项还有 `MAX_NPCS_PER_SCENE`、`LLM_MAX_CONCURRENCY`、`AGENT_TIMEOUT_SECONDS`、`WORKFLOW_TIMEOUT_SECONDS`、`MAX_AGENT_STEPS`、`MAX_LLM_CALLS_PER_TASK`、`BACKGROUND_MAX_RETRIES`、`BACKGROUND_PREFETCH`、`NPC_INTERACTION_COOLDOWN_SECONDS`、`NPC_INTERACTION_MAX_PER_DAY`。默认值是保护配置，不是已验证容量。

启动：先启动 Docker 依赖并执行迁移；加载根目录 `.env` 后启动 `java-gateway`；在 `backend` 虚拟环境分别运行 `python mq_consumer.py` 与 `python background_consumer.py`；再打开 `helloagents-ai-town` 的 Godot 项目。后台自主互动仅在需要时设 `NPC_AUTONOMOUS_ENABLED=true`。本地联调使用了显式 Mock 角色消费者，**正式运行必须使用原来的真实 `mq_consumer.py`**。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Java `./mvnw test` | 8 项通过，0 失败 |
| Python `unittest`（场景、记忆、后台、模型配置与错误处理） | 28 项通过，0 失败 |
| 本地 Java → RabbitMQ → Python Mock → MySQL 场景 | 2 位 NPC 有序落库，任务 `COMPLETED` |
| 签名身份与请求去重 | 重放得到同一任务；无令牌 401；其他玩家查任务 404 |
| WebSocket | 带访客令牌连接，收到与提交任务 ID 一致的完成事件 |
| Godot 4.6.2 | `tests/scene_client_smoke.gd` 通过，真实客户端收到了双 NPC 有序结果 |
| 异步记忆 | 场景两位 NPC 的来源 ID 均在 Redis 标记完成，Chroma 各有一条来源记录 |
| 自主 NPC 互动 | 临时开启调度并用 Mock 后台消费者完成一轮；MySQL 任务为 `COMPLETED` |
| DeepSeek 真实模型 | 模型列表 200；单 NPC 任务 `COMPLETED`；双 NPC 场景两位均 `COMPLETED`、序号 2/3；后台记忆任务完成 |
| DeepSeek 多轮对话 | 使用真实模型连续发送“你好”与“我们交朋友吧”，两轮任务均 `COMPLETED` |
| DeepSeek Planner 回复 | “你是谁？”进入画像工具规划路径，真实模型返回有效结构化回复 |

本机 Mock 压测环境：macOS、Java 17、Godot 4.6.2、MySQL 8.4、Redis 7、RabbitMQ（localhost:5672）；交互消费者单进程，角色 Mock 每次睡眠 150 毫秒，后台记忆消费者单进程。脚本 `backend/load_test_mock.py` 为每位虚拟玩家签发访客令牌，提交一个请求并轮询至终态。吞吐量按整个阶段耗时计算；P50/P95 为从访客身份创建到任务终态的耗时。

| 场景 | 并发玩家 | 完成/请求 | 吞吐量 | P50 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 不同 NPC 单聊 | 10 | 10/10 | 5.18 请求/秒 | 1.20 秒 | 1.93 秒 |
| 同一 NPC 单聊 | 20 | 20/20 | 5.56 请求/秒 | 2.04 秒 | 3.59 秒 |
| 双 NPC 场景 | 50 | 50/50 | 5.82 请求/秒 | 4.53 秒 | 8.14 秒 |

测试后实际连接的 RabbitMQ `interactive`、`result`、`background` 队列待处理数均为 0；消费者数分别为 1、2、1。测试后进程快照：Java RSS 248 MB，Mock 交互消费者 69 MB，后台消费者 363 MB；这不是测试期间 CPU/内存峰值。未在此轮执行后台互动与交互任务混合压测或消费者强制退出的完整恢复演练，因此不能据此推断故障期间性能。`traceId` 已沿任务消息与结果消息传递；Token 用量尚未进入统一指标记录。

## 尚未通过的验收与限制

- Groq 接口从本机仍返回 HTTP 403；项目已切换 DeepSeek，真实单 NPC 和双 NPC 场景均已通过小规模联调。Mock 压测结果仍只代表系统链路容量，不代表 DeepSeek 的吞吐或角色生成质量。
- 2026-11-03，从本机请求 Groq 聊天接口得到 Cloudflare 403，匿名与带密钥访问模型列表也都得到 403，不能仅据此判断密钥是否无效。消费者现对不可重试的 HTTP 400/401/403/404/422 直接标记失败并给出明确错误；可通过 `NPC_LLM_BASE_URL`、`NPC_LLM_MODEL`、`NPC_LLM_API_KEY` 切换其他兼容端点。
- 2026-11-03，默认模型已切换为 DeepSeek 官方 `https://api.deepseek.com` 的 `deepseek-flash`，并关闭默认思考模式。本机 `DEEPSEEK_API_KEY` 已配置；真实单 NPC、双 NPC、后台记忆链路通过。原有 Groq 403 不再影响当前配置。
- 2026-11-03，多轮对话曾因历史 NPC 台词是普通文本而引导模型返回非 JSON。现仅在模型调用时把历史台词包装为 JSON，并启用 DeepSeek 的 JSON 输出模式；非 JSON 纯文本回复会以好感度变化 0 降级保留。原失败输入已用相同角色上下文复测为有效 JSON，连续两轮真实 API 请求均完成。
- 2026-11-03，Hugging Face 多语言嵌入模型缓存缺少权重文件，官方地址连接被重置。已在本机 `backend/.env` 临时切换到完整缓存的 `all-MiniLM-L6-v2`、强制离线读取，并使用独立 Chroma collection；真实消费者已能启动并监听 RabbitMQ。该模型的中文检索质量有限，网络恢复后应换回多语言模型。
- 自主互动调度默认关闭，已做启用后的 Mock 链路验收；切换 DeepSeek 后尚未单独演练自主互动真实生成。
- Godot 脚本端到端通过；人工操作窗口中的点击、重连后补齐界面状态尚未演练。
- 访客令牌不是正式账号系统；跨设备身份、撤销、账户恢复与入口限流尚未实现。
- 同步模型调用超时后无法强制中断已发出的外部调用，可能仍产生模型费用。后台与交互各自有队列和消费者，若部署在同一机器上仍共享主机 CPU/内存及外部模型配额。

## 2026-11-03：NPC 状态接口与轮次接话补充

- Java 新增 `GET /api/npcs` 和 `GET /api/npcs/status`。NPC 画像与状态文字从 MySQL 读取，情绪优先读 Redis、失败时退回 MySQL；Godot 客户端解析列表与状态并发出对应信号，界面伙伴选项可使用服务端角色列表。
- Scene Orchestrator 改为按参与者顺序执行：后发言角色收到此前成功发言的有限长度记录。默认 `SCENE_ROUNDS=1`，每位角色发言一次；最多配置两轮，模型调用次数同时受 `MAX_LLM_CALLS_PER_TASK` 限制。每位角色和整个场景都有超时限制，失败角色不会覆盖已经完成的台词。结果附 `round`、`turn`，Java 按顺序入库并回传，Godot 按轮次展示。
- 新迁移 `005_scene_turns.sql` 为 `conversation_messages` 增加 `turn_no`，将唯一键扩为 `(task_id, role, npc_id, turn_no)`，允许同一 NPC 在两轮中各保存一条台词。本机已备份原表并完成迁移。开启两轮双 NPC 时，Java 与 Python 均须配置模型调用预算为 4。
- 验证：Java 10 项、Python 30 项测试通过；两个 NPC 接口返回 3 位角色；Godot 双 NPC 真实模型任务完成。客户端脚本在提交后强制断开 WebSocket，结果由 HTTP 轮询补齐，随后 WebSocket 重新连接；独立界面脚本确认场景按钮和两轮台词展示顺序。用户已在游戏窗口手动确认多人对话可用，后发言 NPC 能接续前一位的信息；断线重连的窗口人工操作尚未单独验证。
