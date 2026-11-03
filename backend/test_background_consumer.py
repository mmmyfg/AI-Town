import unittest
from unittest.mock import Mock

from background_consumer import consolidate


EVENT = {
    "type": "MEMORY_CONSOLIDATE",
    "taskId": "task-1",
    "playerId": "player-1",
    "conversationId": "conv-1",
    "candidates": [{
        "sourceId": "task-1:npc-1", "npcId": "npc-1",
        "playerMessage": "记住我喜欢咖啡", "replyText": "记住了",
        "affinityChange": 1,
    }],
}


class BackgroundConsumerTests(unittest.TestCase):
    def test_consolidation_marks_source_done_and_skips_redelivery(self):
        agent = Mock()
        cache = Mock()
        cache.exists.side_effect = [False, False, True]
        cache.set.return_value = True

        consolidate(agent, cache, EVENT)
        consolidate(agent, cache, EVENT)

        agent.store_long_term_memory.assert_called_once_with(
            player_id="player-1", npc_id="npc-1", conversation_id="conv-1",
            player_message="记住我喜欢咖啡", reply_text="记住了",
            affinity_change=1, source_id="task-1:npc-1",
        )
        cache.set.assert_any_call("memory:done:task-1:npc-1", "1", ex=2592000)

    def test_memory_failure_does_not_mark_source_done(self):
        agent = Mock()
        agent.store_long_term_memory.side_effect = RuntimeError("storage unavailable")
        cache = Mock()
        cache.exists.return_value = False
        cache.set.return_value = True

        with self.assertRaises(RuntimeError):
            consolidate(agent, cache, EVENT)

        done_writes = [call for call in cache.set.call_args_list
                       if call.args[0].startswith("memory:done:")]
        self.assertEqual(done_writes, [])
        cache.eval.assert_called_once()


if __name__ == "__main__":
    unittest.main()
