import os
import unittest
from unittest.mock import patch

import langchain_agent
from langchain_core.messages import AIMessage, HumanMessage
from mq_consumer import is_non_retryable_model_error, model_error_message


class ModelConnectionTests(unittest.TestCase):
    def test_permission_error_is_terminal_and_readable(self):
        error = RuntimeError("Forbidden")
        error.status_code = 403
        self.assertTrue(is_non_retryable_model_error(error))
        self.assertIn("HTTP 403", model_error_message(error))
        self.assertFalse(is_non_retryable_model_error(ConnectionError("temporary")))

    def test_custom_model_requires_all_three_settings(self):
        with patch.dict(os.environ, {"NPC_LLM_PROVIDER": "custom",
                                          "NPC_LLM_BASE_URL": "https://provider.example/v1"}, clear=True):
            with self.assertRaisesRegex(ValueError, "NPC_LLM_BASE_URL"):
                langchain_agent.LangChainNPCAgent()

    def test_custom_model_uses_explicit_endpoint_and_key(self):
        settings = {"NPC_LLM_PROVIDER": "custom",
                    "NPC_LLM_BASE_URL": "https://provider.example/v1",
                    "NPC_LLM_MODEL": "example-model", "NPC_LLM_API_KEY": "test-secret"}
        with patch.dict(os.environ, settings, clear=True), \
             patch.object(langchain_agent, "ChatOpenAI") as llm, \
             patch.object(langchain_agent, "RedisShortTermMemory"), \
             patch.object(langchain_agent, "HuggingFaceEmbeddings"), \
             patch.object(langchain_agent, "Chroma"), \
             patch.object(langchain_agent.LangChainNPCAgent, "build_intent_vectors", return_value={}):
            langchain_agent.LangChainNPCAgent()
        self.assertEqual(llm.call_args.kwargs["model_name"], "example-model")
        self.assertEqual(llm.call_args.kwargs["base_url"], "https://provider.example/v1")
        self.assertEqual(llm.call_args.kwargs["api_key"], "test-secret")

    def test_deepseek_requires_its_own_key(self):
        with patch.dict(os.environ, {"GROQ_API_KEY": "groq-test"}, clear=True):
            with self.assertRaisesRegex(ValueError, "DEEPSEEK_API_KEY"):
                langchain_agent.LangChainNPCAgent()

    def test_deepseek_uses_current_flash_model_without_thinking(self):
        settings = {"DEEPSEEK_API_KEY": "deepseek-test"}
        with patch.dict(os.environ, settings, clear=True), \
             patch.object(langchain_agent, "ChatOpenAI") as llm, \
             patch.object(langchain_agent, "RedisShortTermMemory"), \
             patch.object(langchain_agent, "HuggingFaceEmbeddings"), \
             patch.object(langchain_agent, "Chroma"), \
             patch.object(langchain_agent.LangChainNPCAgent, "build_intent_vectors", return_value={}):
            langchain_agent.LangChainNPCAgent()
        kwargs = llm.call_args.kwargs
        self.assertEqual(kwargs["model_name"], "deepseek-flash")
        self.assertEqual(kwargs["base_url"], "https://api.deepseek.com")
        self.assertEqual(kwargs["api_key"], "deepseek-test")
        self.assertEqual(kwargs["extra_body"], {"thinking": {"type": "disabled"}})

    def test_deepseek_history_is_structured_only_for_model_prompt(self):
        agent = object.__new__(langchain_agent.LangChainNPCAgent)
        agent.provider = "deepseek"
        history = [HumanMessage(content="你好"), AIMessage(content="你好呀")]

        model_history = agent.history_for_model(history)

        self.assertEqual(model_history[0].content, "你好")
        self.assertEqual(model_history[1].content, '{"reply": "你好呀", "affinity_change": 0}')
        self.assertEqual(history[1].content, "你好呀")


if __name__ == "__main__":
    unittest.main()
