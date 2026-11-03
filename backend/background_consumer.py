"""Dedicated consumer for deferred memory work. Run separately from mq_consumer.py."""

import json
import os
import uuid

import pika
from dotenv import load_dotenv

from langchain_agent import LangChainNPCAgent
from mq_consumer import (BACKGROUND_QUEUE, BACKGROUND_RETRY_ROUTING_KEY,
                         CHAT_EXCHANGE, RESULT_ROUTING_KEY, NON_RETRYABLE_MODEL_STATUSES,
                         PermanentModelError, claim_task, declare_topology,
                         is_non_retryable_model_error, model_error_message, persistent_properties,
                         publish_json, redis_client, release_claim, result_payload, status_payload,
                         store_state, task_key)
from scene_orchestrator import run_scene

load_dotenv(override=True)

MAX_RETRIES = int(os.getenv("BACKGROUND_MAX_RETRIES", "3"))
DONE_TTL = int(os.getenv("BACKGROUND_DONE_TTL_SECONDS", "2592000"))
CLAIM_TTL = int(os.getenv("BACKGROUND_CLAIM_TTL_SECONDS", "180"))


def consolidate(agent, cache, event):
    if event.get("type") != "MEMORY_CONSOLIDATE":
        raise ValueError("Unsupported background event")
    for candidate in event.get("candidates", []):
        source_id = candidate["sourceId"]
        done_key = "memory:done:" + source_id
        if cache.exists(done_key):
            continue
        claim_key = "memory:claim:" + source_id
        token = uuid.uuid4().hex
        if not cache.set(claim_key, token, nx=True, ex=CLAIM_TTL):
            raise RuntimeError("Memory source is already being processed")
        try:
            if not cache.exists(done_key):
                agent.store_long_term_memory(
                    player_id=event["playerId"],
                    npc_id=candidate["npcId"],
                    conversation_id=event.get("conversationId", ""),
                    player_message=candidate["playerMessage"],
                    reply_text=candidate["replyText"],
                    affinity_change=int(candidate.get("affinityChange", 0)),
                    source_id=source_id,
                )
                cache.set(done_key, "1", ex=DONE_TTL)
        finally:
            release_claim(cache, source_id, token, prefix="memory:claim:")


def run_autonomous_scene(agent, cache, channel, task, retry_count):
    task_id = task["taskId"]
    claim_state, token = claim_task(cache, task_id)
    try:
        if claim_state == "terminal":
            return
        if claim_state in {"model_done", "failed"}:
            cached = cache.hgetall(task_key(task_id))
            success = claim_state == "model_done"
            parsed = {"reply": cached.get("reply", ""),
                      "affinity_change": int(cached.get("affinity_change", 0))}
            publish_json(channel, RESULT_ROUTING_KEY,
                         result_payload(task, parsed, success, retry_count,
                                        cached.get("error_message", "")), "BACKGROUND_RECOVERY")
            return
        if claim_state == "busy":
            raise RuntimeError("Background scene is already being processed")
        store_state(cache, task_id, status="PROCESSING", retry_count=retry_count)
        publish_json(channel, RESULT_ROUTING_KEY,
                     status_payload(task, "PROCESSING", retry_count), "PROCESSING")
        messages = run_scene(agent, task)
        if not any(item["status"] == "COMPLETED" for item in messages):
            permanent_status = next((item.get("httpStatus") for item in messages
                                     if item.get("httpStatus") in NON_RETRYABLE_MODEL_STATUSES), None)
            if permanent_status is not None:
                raise PermanentModelError(permanent_status)
            raise RuntimeError("All autonomous characters failed")
        parsed = {"reply": json.dumps(messages, ensure_ascii=False), "affinity_change": 0}
        store_state(cache, task_id, status="MODEL_DONE", reply=parsed["reply"],
                    affinity_change=0, retry_count=retry_count)
        publish_json(channel, RESULT_ROUTING_KEY,
                     result_payload(task, parsed, True, retry_count), "RESULT")
    finally:
        release_claim(cache, task_id, token)


def main():
    agent = LangChainNPCAgent()
    cache = redis_client()
    cache.ping()
    parameters = pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", "5672")),
        credentials=pika.PlainCredentials(
            os.getenv("RABBITMQ_USERNAME", "guest"),
            os.getenv("RABBITMQ_PASSWORD", "guest")),
        heartbeat=600,
        blocked_connection_timeout=300,
    )
    connection = pika.BlockingConnection(parameters)
    channel = connection.channel()
    declare_topology(channel)
    channel.confirm_delivery()

    def callback(ch, method, properties, body):
        event = None
        try:
            event = json.loads(body)
            retry_count = int((properties.headers or {}).get("x-retry-count", 0))
            if event.get("type") == "MEMORY_CONSOLIDATE":
                consolidate(agent, cache, event)
            elif event.get("mode") == "group" and event.get("playerId") == "world":
                run_autonomous_scene(agent, cache, ch, event, retry_count)
            else:
                raise ValueError("Unsupported background event")
            ch.basic_ack(method.delivery_tag)
            print(f"✅ background task_id={event.get('taskId')} completed")
        except Exception as exc:
            retry = int((properties.headers or {}).get("x-retry-count", 0)) + 1
            error_message = model_error_message(exc)
            print(f"❌ background task_id={event.get('taskId') if event else 'unknown'} "
                  f"attempt={retry} error={type(exc).__name__}: {error_message}")
            if event is None or retry > MAX_RETRIES or is_non_retryable_model_error(exc):
                if event is not None and event.get("mode") == "group":
                    try:
                        store_state(cache, event["taskId"], status="FAILED",
                                    retry_count=retry - 1, error_message=error_message)
                        publish_json(ch, RESULT_ROUTING_KEY,
                                     result_payload(event, {}, False, retry - 1, error_message),
                                     "BACKGROUND_FAILED")
                    except Exception:
                        ch.basic_nack(method.delivery_tag, requeue=True)
                        return
                ch.basic_nack(method.delivery_tag, requeue=False)
                return
            try:
                if event.get("mode") == "group":
                    store_state(cache, event["taskId"], status="WAITING_RETRY",
                                retry_count=retry, error_message=error_message)
                    publish_json(ch, RESULT_ROUTING_KEY,
                                 status_payload(event, "RETRYING", retry, error_message),
                                 "BACKGROUND_RETRYING")
                published = ch.basic_publish(
                    exchange=CHAT_EXCHANGE,
                    routing_key=BACKGROUND_RETRY_ROUTING_KEY,
                    body=body,
                    properties=persistent_properties(event["taskId"], "BACKGROUND_RETRY",
                                                     {"x-retry-count": retry}),
                    mandatory=True,
                )
                if published is False:
                    raise RuntimeError("Background retry was not confirmed")
                ch.basic_ack(method.delivery_tag)
            except Exception:
                ch.basic_nack(method.delivery_tag, requeue=True)

    channel.basic_qos(prefetch_count=int(os.getenv("BACKGROUND_PREFETCH", "1")))
    channel.basic_consume(BACKGROUND_QUEUE, callback, auto_ack=False)
    print(f"✅ background consumer listening {BACKGROUND_QUEUE}")
    channel.start_consuming()


if __name__ == "__main__":
    main()
