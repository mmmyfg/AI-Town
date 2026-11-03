import json
import time
import unittest
from unittest.mock import patch

import scene_orchestrator


def task():
    return {"playerId": "p", "conversationId": "c", "participants": [
        {"npcId": "slow", "personality": "A", "observation": "hello", "mood": "CALM"},
        {"npcId": "fast", "personality": "B", "observation": "hello", "mood": "CALM"},
    ]}


class FakeAgent:
    def __init__(self, fail=None):
        self.fail = fail
        self.started = {}
        self.observations = []

    def react(self, **kwargs):
        npc_id = kwargs["npc_id"]
        self.started[npc_id] = time.monotonic()
        self.observations.append((npc_id, kwargs["observation"]))
        if npc_id == self.fail:
            raise RuntimeError("model failed")
        time.sleep(0.08 if npc_id == "slow" else 0.01)
        return json.dumps({"reply": npc_id, "affinity_change": 1})


class InvalidAgent:
    def __init__(self, response):
        self.response = response

    def react(self, **kwargs):
        return json.dumps(self.response)


class PermissionDeniedAgent:
    def react(self, **kwargs):
        error = RuntimeError("Forbidden")
        error.status_code = 403
        raise error


class SceneOrchestratorTests(unittest.TestCase):
    def test_later_npc_sees_previous_reply_in_speaker_order(self):
        agent = FakeAgent()
        with patch.object(scene_orchestrator, "MAX_CONCURRENCY", 2):
            result = scene_orchestrator.run_scene(agent, task())
        self.assertEqual([item["npcId"] for item in result], ["slow", "fast"])
        self.assertEqual([item["status"] for item in result], ["COMPLETED", "COMPLETED"])
        self.assertEqual([(item["round"], item["turn"]) for item in result], [(1, 1), (1, 2)])
        self.assertNotIn("slow：slow", agent.observations[0][1])
        self.assertIn("slow：slow", agent.observations[1][1])
        self.assertLess(agent.started["slow"], agent.started["fast"])

    def test_one_failure_preserves_other_reply(self):
        with patch.object(scene_orchestrator, "MAX_CONCURRENCY", 2):
            result = scene_orchestrator.run_scene(FakeAgent(fail="slow"), task())
        self.assertEqual([item["status"] for item in result], ["FAILED", "COMPLETED"])
        self.assertEqual(result[1]["reply"], "fast")

    def test_z_agent_timeout_returns_failure(self):
        with patch.object(scene_orchestrator, "MAX_CONCURRENCY", 2), \
             patch.object(scene_orchestrator, "AGENT_TIMEOUT", 0.02):
            result = scene_orchestrator.run_scene(FakeAgent(), task())
        self.assertEqual(result[0]["status"], "FAILED")
        self.assertEqual(result[1]["status"], "COMPLETED")

    def test_rejects_wrong_npc_identity(self):
        result = scene_orchestrator.run_scene(
            InvalidAgent({"npc_id": "intruder", "reply": "hello", "affinity_change": 1}), task())
        self.assertEqual([item["status"] for item in result], ["FAILED", "FAILED"])

    def test_rejects_unapproved_tool_calls(self):
        result = scene_orchestrator.run_scene(
            InvalidAgent({"reply": "hello", "affinity_change": 1,
                          "tool_calls": [{"name": "change_relationship"}]}), task())
        self.assertEqual([item["status"] for item in result], ["FAILED", "FAILED"])

    def test_rejects_out_of_range_relationship_change(self):
        result = scene_orchestrator.run_scene(
            InvalidAgent({"reply": "hello", "affinity_change": 99}), task())
        self.assertEqual([item["status"] for item in result], ["FAILED", "FAILED"])

    def test_preserves_http_status_for_permanent_model_failure(self):
        result = scene_orchestrator.run_scene(PermissionDeniedAgent(), task())
        self.assertEqual([item["httpStatus"] for item in result], [403, 403])

    def test_two_rounds_see_prior_speakers_and_respect_call_budget(self):
        two_round_task = {**task(), "sceneRounds": 2}
        agent = FakeAgent()
        with patch.object(scene_orchestrator, "MAX_LLM_CALLS", 4):
            result = scene_orchestrator.run_scene(agent, two_round_task)
        self.assertEqual([(item["npcId"], item["round"], item["turn"]) for item in result],
                         [("slow", 1, 1), ("fast", 1, 2), ("slow", 2, 1), ("fast", 2, 2)])
        self.assertIn("fast：fast", agent.observations[2][1])
        with self.assertRaises(ValueError):
            scene_orchestrator.run_scene(agent, two_round_task)

    def test_rejects_excessive_rounds(self):
        with self.assertRaises(ValueError):
            scene_orchestrator.run_scene(FakeAgent(), {**task(), "sceneRounds": 3})


if __name__ == "__main__":
    unittest.main()
