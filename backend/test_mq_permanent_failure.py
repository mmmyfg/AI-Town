import json
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import mq_consumer


class ForbiddenAgent:
    def react(self, **kwargs):
        error = RuntimeError("Forbidden")
        error.status_code = 403
        raise error


class PermanentFailureTests(unittest.TestCase):
    def test_http_403_fails_task_without_retry_queue(self):
        cache = MagicMock()
        cache.hget.return_value = None
        cache.set.return_value = True
        connection = MagicMock()
        channel = connection.channel.return_value
        task = {"taskId": "test-403", "playerId": "player", "npcId": "npc",
                "conversationId": "conversation", "personality": "npc profile",
                "observation": "玩家对你说：你好"}

        def consume_once():
            callback = channel.basic_consume.call_args.args[1]
            callback(channel, SimpleNamespace(delivery_tag=7),
                     SimpleNamespace(headers={}), json.dumps(task).encode())

        channel.start_consuming.side_effect = consume_once
        with patch.object(mq_consumer, "LangChainNPCAgent", ForbiddenAgent), \
             patch.object(mq_consumer, "redis_client", return_value=cache), \
             patch.object(mq_consumer.pika, "BlockingConnection", return_value=connection), \
             patch.object(mq_consumer, "publish_retry") as retry:
            mq_consumer.main()

        retry.assert_not_called()
        channel.basic_nack.assert_called_once_with(7, requeue=False)
        self.assertFalse(channel.basic_ack.called)
        result = json.loads(channel.basic_publish.call_args.kwargs["body"])
        self.assertFalse(result["success"])
        self.assertIn("HTTP 403", result["errorMessage"])


if __name__ == "__main__":
    unittest.main()
