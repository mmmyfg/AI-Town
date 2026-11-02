# 可靠 Agent 后端设计

## 数据职责

- MySQL 是任务、会话消息和玩家关系的事实源。
- Redis 缓存任务查询结果，并持久化会话级短期记忆与消费者租约。
- Chroma 仅保存按 `player_id + npc_id` 隔离的长期语义记忆。
- RabbitMQ 负责 Java 网关和 Python 推理节点之间的异步事件传递。

## 任务状态机

```text
PENDING -> PROCESSING -> MODEL_DONE -> COMPLETED
                |             |
                v             v
         WAITING_RETRY     补偿回传
                |
                +-> PROCESSING
                +-> FAILED
                +-> TIMEOUT
```

终态是 `COMPLETED / FAILED / TIMEOUT`。Java 结果消费者会先锁定 `chat_tasks`
记录再检查终态，因此重复结果不会再次写入消息或增加好感度。

## 可靠消息

1. 创建任务、保存玩家消息和写入 `task_outbox` 在同一个 MySQL 事务中完成。
2. `OutboxPublisher` 抢占事件，通过 Publisher Confirm 确认 Broker 接管消息。
3. Python 使用 Redis `SET NX EX` 获取任务消费租约，成功发布结果后才 ACK。
4. 推理失败依次进入 5 秒、15 秒、45 秒重试队列；超过上限后进入任务 DLQ。
5. Java 结果监听器使用手动 ACK，处理异常时 NACK 并进入结果 DLQ。
6. 定时补偿扫描长期停留在非终态的任务，重新写 Outbox；超过补偿上限置为 `TIMEOUT`。

## 并发一致性

- `task_id`、`event_id`、`player_id + npc_id` 都有唯一约束。
- 同一任务结果通过 `SELECT ... FOR UPDATE` 串行处理。
- 好感度使用单条 SQL 原子增量并限制在 `0..100`，同时递增关系版本号。
- 会话序号通过 MySQL `LAST_INSERT_ID(last_sequence + 1)` 原子生成。
- WebSocket 按 `playerId` 建立会话并定向推送，不再广播其他玩家的结果。

## 记忆策略

- Redis Key 包含 `player_id + npc_id + conversation_id`，保存最近 10 条消息并设置 TTL。
- Chroma 检索必须同时匹配 `player_id` 和 `npc_id`，禁止退化为只按 NPC 检索。
- 长期记忆只保存非寒暄且达到重要度阈值的对话。
- 召回综合语义相关度、时间衰减和重要度，并使用高相似度阈值抑制重复写入。
- 新实现默认使用 cosine 集合 `cyber_town_memories_v2`；旧版 L2 集合不会自动混用，
  如需保留旧数据，应离线读取旧集合并用当前 embedding 重新写入新集合。

## 验证命令

```bash
cd java-gateway
./mvnw test

cd ../backend
./venv/bin/python -m unittest -v test_memory_policy.py
./venv/bin/python -m py_compile mq_consumer.py langchain_agent.py main.py
```

全链路验证前先启动 Docker Desktop，再运行 `docker compose up -d`。如果 MySQL 数据卷
来自旧版本，需要手动执行 `docker/mysql-init/002_reliable_chat.sql`，或重新创建开发数据卷。
