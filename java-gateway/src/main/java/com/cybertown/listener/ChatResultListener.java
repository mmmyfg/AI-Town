package com.cybertown.listener;

import com.cybertown.config.RabbitConfig;
import com.cybertown.dto.ChatResultMessage;
import com.cybertown.service.AsyncChatService;
import com.cybertown.websocket.ChatWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChatResultListener {
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AsyncChatService asyncChatService;
    @Autowired private ChatWebSocketHandler chatWebSocketHandler;

    @RabbitListener(queues = RabbitConfig.CHAT_RESULT_QUEUE, ackMode = "MANUAL")
    public void handleChatResult(Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            ChatResultMessage resultMessage = objectMapper.readValue(amqpMessage.getBody(), ChatResultMessage.class);
            AsyncChatService.ResultHandlingOutcome outcome = asyncChatService.handleResultEvent(resultMessage);

            String eventType = resultMessage.getEventType() == null ? "RESULT" : resultMessage.getEventType();
            if (outcome.changed() && "RESULT".equalsIgnoreCase(eventType)) {
                Map<String, Object> payload = new LinkedHashMap<>(asyncChatService.getTaskResult(outcome.taskId()));
                payload.put("task_id", outcome.taskId());
                payload.put("npc_id", outcome.npcId());
                chatWebSocketHandler.sendToPlayer(outcome.playerId(), payload);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            System.err.println("❌ 处理 chat_result_queue 消息失败，转入 DLQ: " + exception.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
