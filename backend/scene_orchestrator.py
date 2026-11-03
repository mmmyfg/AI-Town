"""Bounded, ordered NPC turns for one scene task."""

import asyncio
import json
import os
import threading
from contextvars import copy_context
from concurrent.futures import ThreadPoolExecutor


MAX_NPCS = int(os.getenv("MAX_NPCS_PER_SCENE", "3"))
MAX_CONCURRENCY = int(os.getenv("LLM_MAX_CONCURRENCY", "2"))
AGENT_TIMEOUT = float(os.getenv("AGENT_TIMEOUT_SECONDS", "60"))
WORKFLOW_TIMEOUT = float(os.getenv("WORKFLOW_TIMEOUT_SECONDS", "75"))
MAX_LLM_CALLS = int(os.getenv("MAX_LLM_CALLS_PER_TASK", "3"))
MAX_AGENT_STEPS = int(os.getenv("MAX_AGENT_STEPS", "1"))
MAX_SCENE_ROUNDS = int(os.getenv("MAX_SCENE_ROUNDS", "2"))
MAX_SCENE_CONTEXT_CHARS = 1200
_EXECUTOR = ThreadPoolExecutor(max_workers=max(1, MAX_CONCURRENCY))


def _scene_observation(participant, transcript):
    observation = participant["observation"]
    if not transcript:
        return observation
    # Keep the player's original message after the final marker expected by the router.
    recent = transcript[-(MAX_NPCS * MAX_SCENE_ROUNDS):]
    lines = [f'{item["npcId"]}：{item["reply"]}' for item in recent]
    context = "\n".join(lines)[-MAX_SCENE_CONTEXT_CHARS:]
    return ("【本场景此前的 NPC 发言；请听取并自然回应，保持你自己的身份，不要替其他人说话】\n"
            + context + "\n【轮到你回应玩家与前面角色】\n" + observation)


def _react(agent, task, participant, observation, cancel_event):
    raw = agent.react(
        player_id=task["playerId"],
        npc_id=participant["npcId"],
        conversation_id=task["conversationId"],
        current_affinity=int(participant.get("currentAffinity", 50)),
        personality=participant["personality"],
        observation=observation,
        memory=participant.get("memory", ""),
        cancel_event=cancel_event,
        persist_memory=False,
    )
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise ValueError("Invalid character response")
    if parsed.get("npc_id", participant["npcId"]) != participant["npcId"]:
        raise ValueError("Character identity does not match the assigned NPC")
    if parsed.get("tool_calls"):
        raise ValueError("Character response contains unapproved tool calls")
    reply = parsed.get("reply")
    delta = parsed.get("affinity_change", 0)
    if not isinstance(reply, str) or not reply.strip() or len(reply) > 4000 \
            or isinstance(delta, bool) or not isinstance(delta, int) or not -3 <= delta <= 3:
        raise ValueError("Invalid character response")
    return {"npcId": participant["npcId"], "reply": reply.strip(),
            "affinityChange": delta, "mood": participant.get("mood", "CALM"),
            "status": "COMPLETED"}


async def _run(agent, task, participants, rounds):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + WORKFLOW_TIMEOUT
    results = []
    transcript = []
    for round_no in range(1, rounds + 1):
        for turn_no, participant in enumerate(participants, 1):
            cancellation = threading.Event()
            remaining = deadline - loop.time()
            if remaining <= 0:
                result = {"npcId": participant["npcId"], "reply": "", "affinityChange": 0,
                          "mood": participant.get("mood", "CALM"), "status": "FAILED",
                          "error": "WorkflowTimeout"}
            else:
                try:
                    observation = _scene_observation(participant, transcript)
                    future = loop.run_in_executor(
                        _EXECUTOR, copy_context().run,
                        _react, agent, task, participant, observation, cancellation)
                    result = await asyncio.wait_for(future, min(AGENT_TIMEOUT, remaining))
                except Exception as exc:
                    result = {"npcId": participant["npcId"], "reply": "", "affinityChange": 0,
                              "mood": participant.get("mood", "CALM"), "status": "FAILED",
                              "error": type(exc).__name__}
                    status = getattr(exc, "status_code", None)
                    if isinstance(status, int):
                        result["httpStatus"] = status
                finally:
                    cancellation.set()
            result["round"] = round_no
            result["turn"] = turn_no
            results.append(result)
            if result["status"] == "COMPLETED":
                transcript.append(result)
    return results


def run_scene(agent, task):
    participants = task.get("participants") or []
    rounds = task.get("sceneRounds", 1)
    if not isinstance(rounds, int) or isinstance(rounds, bool) or not 1 <= rounds <= MAX_SCENE_ROUNDS:
        raise ValueError("Scene rounds are out of bounds")
    if not 2 <= len(participants) <= MAX_NPCS or len({p["npcId"] for p in participants}) != len(participants):
        raise ValueError("Scene requires distinct NPCs within MAX_NPCS_PER_SCENE")
    if MAX_CONCURRENCY < 1 or AGENT_TIMEOUT <= 0 or WORKFLOW_TIMEOUT <= 0 \
            or MAX_AGENT_STEPS < 1 or len(participants) * rounds > MAX_LLM_CALLS:
        raise ValueError("Invalid scene concurrency, timeout or model-call budget")
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(_run(agent, task, participants, rounds))
    finally:
        loop.close()
