import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from langchain_agent import LangChainNPCAgent
from mq_consumer import parse_agent_reply


class MemoryPolicyTests(unittest.TestCase):
    def setUp(self):
        self.agent = object.__new__(LangChainNPCAgent)

    def test_trivial_message_is_not_persisted(self):
        should_store, importance = self.agent.should_store_memory("你好", 0)
        self.assertFalse(should_store)
        self.assertEqual(importance, 0.0)

    def test_relationship_memory_is_persisted(self):
        should_store, importance = self.agent.should_store_memory("你还记得我们上次聊过什么吗", 1)
        self.assertTrue(should_store)
        self.assertGreaterEqual(importance, 0.5)

    def test_explicit_remember_request_is_persisted(self):
        should_store, importance = self.agent.should_store_memory("请记住，我最喜欢绿色。", 0)
        self.assertTrue(should_store)
        self.assertGreaterEqual(importance, 0.5)

    def test_filter_always_contains_player_and_npc(self):
        where = self.agent.memory_filter("player-a", "npc-a")
        self.assertEqual(where["$and"][0]["player_id"]["$eq"], "player-a")
        self.assertEqual(where["$and"][1]["npc_id"]["$eq"], "npc-a")

    def test_atomic_memory_parser_keeps_distinct_high_confidence_facts(self):
        memories = self.agent.parse_atomic_memories(
            '{"memories": ['
            '{"type": "preference", "content": "玩家最喜欢绿色", "confidence": 0.98},'
            '{"type": "profile", "content": "玩家住在伦敦", "confidence": 0.91},'
            '{"type": "profile", "content": "玩家住在伦敦", "confidence": 0.91},'
            '{"type": "event", "content": "不确定的事情", "confidence": 0.2}'
            ']}'
        )
        self.assertEqual([item["content"] for item in memories], ["玩家最喜欢绿色", "玩家住在伦敦"])
        self.assertEqual(memories[0]["memory_type"], "preference")
        self.assertEqual(memories[0]["extraction_method"], "llm")

    def test_atomic_memory_extraction_falls_back_when_model_fails(self):
        self.agent.memory_llm = Mock()
        self.agent.memory_llm.invoke.side_effect = RuntimeError("model unavailable")

        memories = self.agent.extract_atomic_memories("请记住我喜欢绿色", "我会记住", 1)

        self.assertEqual(len(memories), 1)
        self.assertEqual(memories[0]["memory_type"], "interaction")
        self.assertEqual(memories[0]["extraction_method"], "fallback")

    def test_store_long_term_memory_deduplicates_each_atomic_fact(self):
        self.agent.extract_atomic_memories = Mock(return_value=[
            {
                "memory_type": "preference",
                "content": "玩家最喜欢绿色",
                "confidence": 0.98,
                "extraction_method": "llm",
            },
            {
                "memory_type": "profile",
                "content": "玩家住在伦敦",
                "confidence": 0.95,
                "extraction_method": "llm",
            },
        ])
        self.agent.vector_db = Mock()
        self.agent.vector_db.similarity_search_with_relevance_scores.side_effect = [
            [(SimpleNamespace(page_content="玩家喜欢绿色"), 0.95)],
            [],
        ]

        self.agent.store_long_term_memory(
            player_id="player-a",
            npc_id="npc-a",
            conversation_id="conversation-a",
            player_message="请记住，我喜欢绿色而且住在伦敦。",
            reply_text="我会记住。",
            affinity_change=1,
        )

        self.agent.vector_db.add_texts.assert_called_once()
        call = self.agent.vector_db.add_texts.call_args.kwargs
        self.assertEqual(call["texts"], ["玩家住在伦敦"])
        self.assertEqual(call["metadatas"][0]["memory_type"], "profile")
        self.assertEqual(call["metadatas"][0]["extraction_method"], "llm")

    def test_double_encoded_model_json_is_accepted(self):
        parsed = self.agent.parse_json_reply(
            '"{\\"reply\\": \\"记得\\", \\"affinity_change\\": 0}"'
        )
        self.assertEqual(parsed["reply"], "记得")

    def test_non_object_model_json_is_rejected(self):
        with self.assertRaises(ValueError):
            self.agent.parse_json_reply('["unexpected"]')

    def test_mq_parser_accepts_double_encoded_json(self):
        parsed = parse_agent_reply(
            '"{\\"reply\\": \\"记得\\", \\"affinity_change\\": 0}"'
        )
        self.assertEqual(parsed["affinity_change"], 0)


if __name__ == "__main__":
    unittest.main()
