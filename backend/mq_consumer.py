import json
import os
import sys
import uuid

import pika
import redis
from dotenv import load_dotenv

from langchain_agent import LangChainNPCAgent

load_dotenv(override=True)

CHAT_EXCHANGE = "chat.exchange"
CHAT_DLX = "chat.dlx"
CHAT_TASK_QUEUE = "chat_task_queue"
CHAT_RESULT_QUEUE = "chat_result_queue"
CHAT_TASK_DLQ = "chat_task_dlq"
CHAT_RESULT_DLQ = "chat_result_dlq"
TASK_ROUTING_KEY = "chat.task"
RESULT_ROUTING_KEY = "chat.result"
TASK_DEAD_ROUTING_KEY = "chat.task.dead"
RESULT_DEAD_ROUTING_KEY = "chat.result.dead"
RETRY_QUEUES = ("chat_task_retry_5s", "chat_task_retry_15s", "chat_task_retry_45s")
RETRY_ROUTING_KEYS = ("chat.task.retry.1", "chat.task.retry.2", "chat.task.retry.3")
RETRY_TTLS = (5_000, 15_000, 45_000)
MAX_RETRIES = int(os.getenv("CHAT_MAX_RETRIES", "3"))
CLAIM_TTL_SECONDS = int(os.getenv("CHAT_CLAIM_TTL_SECONDS", "120"))
TASK_TTL_SECONDS = int(os.getenv("CHAT_TASK_TTL_SECONDS", "1800"))
REDIS_TASK_PREFIX = "chat:task:"
REDIS_CLAIM_PREFIX = "chat:claim:"
TERMINAL_STATES = {"COMPLETED", "TIMEOUT"}


def redis_client() -> redis.Redis:
    return redis.Redis(
        host=os.getenv("REDIS_HOST", "localhost"),
        port=int(os.getenv("REDIS_PORT", "6379")),
        db=int(os.getenv("REDIS_DB", "0")),
        decode_responses=True,
        socket_connect_timeout=3,
        socket_timeout=3,
    )


def task_key(task_id: str) -> str:
    return REDIS_TASK_PREFIX + task_id


def claim_task(cache: redis.Redis, task_id: str) -> tuple[str, str | None]:
    status = cache.hget(task_key(task_id), "status")
    if status in TERMINAL_STATES:
        return "terminal", None
    if status == "FAILED":
        return "failed", None
    if status == "MODEL_DONE":
        return "model_done", None

    token = uuid.uuid4().hex
    acquired = cache.set(
        REDIS_CLAIM_PREFIX + task_id,
        token,
        nx=True,
        ex=CLAIM_TTL_SECONDS,
    )
    return ("acquired", token) if acquired else ("busy", None)


def release_claim(cache: redis.Redis, task_id: str, token: str | None) -> None:
    if not token:
        return
    cache.eval(
        """
        if redis.call('get', KEYS[1]) == ARGV[1] then
          return redis.call('del', KEYS[1])
        end
        return 0
        """,
        1,
        REDIS_CLAIM_PREFIX + task_id,
        token,
    )


def store_state(cache: redis.Redis, task_id: str, **fields) -> None:
    normalized = {key: str(value) for key, value in fields.items() if value is not None}
    cache.hset(task_key(task_id), mapping=normalized)
    cache.expire(task_key(task_id), TASK_TTL_SECONDS)


def persistent_properties(task_id: str, event_type: str, headers: dict | None = None):
    return pika.BasicProperties(
        content_type="application/json",
        delivery_mode=2,
        message_id=f"{task_id}:{event_type}:{uuid.uuid4().hex[:8]}",
        correlation_id=task_id,
        headers=headers or {},
    )


def publish_json(channel, routing_key: str, payload: dict, event_type: str, headers: dict | None = None) -> None:
    published = channel.basic_publish(
        exchange=CHAT_EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        properties=persistent_properties(payload.get("taskId", "unknown"), event_type, headers),
        mandatory=True,
    )
    if published is False:
        raise RuntimeError(f"RabbitMQ did not confirm routing_key={routing_key}")


def status_payload(task: dict, event_type: str, retry_count: int, error_message: str = "") -> dict:
    return {
        "taskId": task["taskId"],
        "eventType": event_type,
        "playerId": task.get("playerId", ""),
        "npcId": task.get("npcId", ""),
        "conversationId": task.get("conversationId", ""),
        "success": False,
        "reply": "",
        "affinityChange": 0,
        "mood": task.get("mood", "CALM"),
        "errorMessage": error_message,
        "retryCount": retry_count,
    }


def result_payload(task: dict, parsed: dict, success: bool, retry_count: int, error_message: str = "") -> dict:
    return {
        "taskId": task["taskId"],
        "eventType": "RESULT",
        "playerId": task.get("playerId", ""),
        "npcId": task.get("npcId", ""),
        "conversationId": task.get("conversationId", ""),
        "success": success,
        "reply": parsed.get("reply", "") if success else "",
        "affinityChange": int(parsed.get("affinity_change", 0)) if success else 0,
        "mood": task.get("mood", "CALM"),
        "errorMessage": error_message,
        "retryCount": retry_count,
    }


def publish_retry(channel, task: dict, retry_count: int) -> None:
    index = max(0, min(retry_count - 1, len(RETRY_ROUTING_KEYS) - 1))
    publish_json(
        channel,
        RETRY_ROUTING_KEYS[index],
        task,
        "TASK_RETRY",
        headers={"x-retry-count": retry_count},
    )


def parse_agent_reply(raw_reply: str) -> dict:
    cleaned = raw_reply.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned.replace("```json", "").replace("```", "").strip()
    parsed = json.loads(cleaned)
    if isinstance(parsed, str):
        parsed = json.loads(parsed)
    if not isinstance(parsed, dict) or "reply" not in parsed:
        raise ValueError("模型响应缺少 reply 字段")
    return parsed


def declare_topology(channel) -> None:
    channel.exchange_declare(exchange=CHAT_EXCHANGE, exchange_type="direct", durable=True)
    channel.exchange_declare(exchange=CHAT_DLX, exchange_type="direct", durable=True)

    channel.queue_declare(
        queue=CHAT_TASK_QUEUE,
        durable=True,
        arguments={"x-dead-letter-exchange": CHAT_DLX, "x-dead-letter-routing-key": TASK_DEAD_ROUTING_KEY},
    )
    channel.queue_declare(
        queue=CHAT_RESULT_QUEUE,
        durable=True,
        arguments={"x-dead-letter-exchange": CHAT_DLX, "x-dead-letter-routing-key": RESULT_DEAD_ROUTING_KEY},
    )
    channel.queue_bind(CHAT_TASK_QUEUE, CHAT_EXCHANGE, TASK_ROUTING_KEY)
    channel.queue_bind(CHAT_RESULT_QUEUE, CHAT_EXCHANGE, RESULT_ROUTING_KEY)

    channel.queue_declare(queue=CHAT_TASK_DLQ, durable=True)
    channel.queue_declare(queue=CHAT_RESULT_DLQ, durable=True)
    channel.queue_bind(CHAT_TASK_DLQ, CHAT_DLX, TASK_DEAD_ROUTING_KEY)
    channel.queue_bind(CHAT_RESULT_DLQ, CHAT_DLX, RESULT_DEAD_ROUTING_KEY)

    for queue, routing_key, ttl in zip(RETRY_QUEUES, RETRY_ROUTING_KEYS, RETRY_TTLS):
        channel.queue_declare(
            queue=queue,
            durable=True,
            arguments={
                "x-message-ttl": ttl,
                "x-dead-letter-exchange": CHAT_EXCHANGE,
                "x-dead-letter-routing-key": TASK_ROUTING_KEY,
            },
        )
        channel.queue_bind(queue, CHAT_EXCHANGE, routing_key)


def main():
    print("🚀 正在启动赛博小镇 Python 推理消费者...")
    agent = LangChainNPCAgent()
    cache = redis_client()
    cache.ping()

    parameters = pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", "5672")),
        credentials=pika.PlainCredentials(
            os.getenv("RABBITMQ_USERNAME", "guest"),
            os.getenv("RABBITMQ_PASSWORD", "guest"),
        ),
        heartbeat=600,
        blocked_connection_timeout=300,
    )
    connection = pika.BlockingConnection(parameters)
    channel = connection.channel()
    declare_topology(channel)
    channel.confirm_delivery()

    def callback(ch, method, properties, body):
        task: dict = {}
        claim_token: str | None = None
        retry_count = int((properties.headers or {}).get("x-retry-count", 0))
        try:
            task = json.loads(body.decode("utf-8"))
            task_id = task["taskId"]
            claim_state, claim_token = claim_task(cache, task_id)
            if claim_state == "terminal":
                ch.basic_ack(method.delivery_tag)
                return
            if claim_state == "model_done":
                cached = cache.hgetall(task_key(task_id))
                publish_json(
                    ch,
                    RESULT_ROUTING_KEY,
                    result_payload(
                        task,
                        {
                            "reply": cached.get("reply", "..."),
                            "affinity_change": int(cached.get("affinity_change", 0)),
                        },
                        True,
                        int(cached.get("retry_count", retry_count)),
                    ),
                    "RESULT_RECOVERY",
                )
                ch.basic_ack(method.delivery_tag)
                return
            if claim_state == "failed":
                cached = cache.hgetall(task_key(task_id))
                publish_json(
                    ch,
                    RESULT_ROUTING_KEY,
                    result_payload(
                        task,
                        {},
                        False,
                        int(cached.get("retry_count", retry_count)),
                        cached.get("error_message", "推理任务失败"),
                    ),
                    "RESULT_FAILED_RECOVERY",
                )
                ch.basic_ack(method.delivery_tag)
                return
            if claim_state == "busy":
                publish_retry(ch, task, max(1, retry_count))
                ch.basic_ack(method.delivery_tag)
                return

            store_state(
                cache,
                task_id,
                status="PROCESSING",
                mood=task.get("mood", "CALM"),
                retry_count=retry_count,
            )
            publish_json(
                ch,
                RESULT_ROUTING_KEY,
                status_payload(task, "PROCESSING", retry_count),
                "PROCESSING",
            )

            raw_reply = agent.react(
                player_id=task["playerId"],
                npc_id=task["npcId"],
                conversation_id=task.get("conversationId", ""),
                current_affinity=int(task.get("currentAffinity", 50)),
                personality=task["personality"],
                observation=task["observation"],
                memory=task.get("memory", ""),
            )
            parsed = parse_agent_reply(raw_reply)

            store_state(
                cache,
                task_id,
                status="MODEL_DONE",
                reply=parsed.get("reply", "..."),
                affinity_change=int(parsed.get("affinity_change", 0)),
                mood=task.get("mood", "CALM"),
                retry_count=retry_count,
            )
            publish_json(
                ch,
                RESULT_ROUTING_KEY,
                result_payload(task, parsed, True, retry_count),
                "RESULT",
            )
            ch.basic_ack(method.delivery_tag)
            print(f"✅ task_id={task_id} 推理完成并确认结果投递")
        except Exception as exc:
            task_id = task.get("taskId", "unknown")
            error_message = f"推理失败: {exc}"
            print(f"❌ task_id={task_id} attempt={retry_count}: {error_message}")

            if task_id == "unknown":
                ch.basic_nack(method.delivery_tag, requeue=False)
                return

            next_retry = retry_count + 1
            try:
                if next_retry <= MAX_RETRIES:
                    store_state(
                        cache,
                        task_id,
                        status="WAITING_RETRY",
                        retry_count=next_retry,
                        error_message=error_message,
                    )
                    publish_json(
                        ch,
                        RESULT_ROUTING_KEY,
                        status_payload(task, "RETRYING", next_retry, error_message),
                        "RETRYING",
                    )
                    publish_retry(ch, task, next_retry)
                    ch.basic_ack(method.delivery_tag)
                else:
                    store_state(
                        cache,
                        task_id,
                        status="FAILED",
                        retry_count=retry_count,
                        error_message=error_message,
                    )
                    publish_json(
                        ch,
                        RESULT_ROUTING_KEY,
                        result_payload(task, {}, False, retry_count, error_message),
                        "RESULT_FAILED",
                    )
                    ch.basic_nack(method.delivery_tag, requeue=False)
            except Exception as publish_error:
                print(f"❌ task_id={task_id} 重试/失败事件投递异常: {publish_error}")
                ch.basic_nack(method.delivery_tag, requeue=True)
        finally:
            if task.get("taskId"):
                try:
                    release_claim(cache, task["taskId"], claim_token)
                except Exception as exc:
                    print(f"⚠️ task_id={task['taskId']} 释放消费租约失败: {exc}")

    channel.basic_qos(prefetch_count=int(os.getenv("CHAT_PREFETCH", "5")))
    channel.basic_consume(CHAT_TASK_QUEUE, callback, auto_ack=False)
    print(f"✅ 消费者已监听 {CHAT_TASK_QUEUE}，最大重试次数={MAX_RETRIES}")
    channel.start_consuming()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n🛑 消费端已手动关闭。")
        try:
            sys.exit(0)
        except SystemExit:
            os._exit(0)
