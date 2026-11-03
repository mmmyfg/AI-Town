package com.cybertown.service;

import com.cybertown.websocket.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WebSocketPushService {
    private final ChatWebSocketHandler handler;
    private final ThreadPoolTaskExecutor executor;

    public WebSocketPushService(ChatWebSocketHandler handler,
                                @Qualifier("webSocketPushExecutor") ThreadPoolTaskExecutor executor) {
        this.handler = handler;
        this.executor = executor;
    }

    public void sendLater(String playerId, Map<String, Object> payload) {
        try {
            executor.execute(() -> handler.sendToPlayer(playerId, payload));
        } catch (TaskRejectedException exception) {
            System.err.println("⚠️ WebSocket 推送队列已满，客户端可通过任务查询补齐 taskId="
                    + payload.get("task_id"));
        }
    }
}
