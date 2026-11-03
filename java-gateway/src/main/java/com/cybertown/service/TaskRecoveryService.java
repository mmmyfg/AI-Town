package com.cybertown.service;

import com.cybertown.entity.ChatTask;
import com.cybertown.entity.TaskOutbox;
import com.cybertown.config.RabbitConfig;
import com.cybertown.enums.ChatTaskStatus;
import com.cybertown.mapper.ChatTaskMapper;
import com.cybertown.mapper.TaskOutboxMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskRecoveryService {
    @Autowired private ChatTaskMapper taskMapper;
    @Autowired private TaskOutboxMapper outboxMapper;
    @Autowired private AsyncChatService chatService;
    @Autowired private WebSocketPushService webSocketPushService;

    @Value("${chat.task.timeout-seconds:90}")
    private long timeoutSeconds;

    @Value("${chat.task.max-compensations:3}")
    private int maxCompensations;

    @Scheduled(fixedDelayString = "${chat.task.recovery-interval-ms:15000}")
    @Transactional
    public void recoverStaleTasks() {
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(timeoutSeconds);
        List<String> taskIds = taskMapper.selectStaleTaskIds(deadline, 100);
        for (String taskId : taskIds) {
            ChatTask task = taskMapper.selectByTaskIdForUpdate(taskId);
            if (task == null || ChatTaskStatus.from(task.getStatus()).isTerminal()) continue;
            if (task.getUpdatedAt() != null && !task.getUpdatedAt().isBefore(deadline)) continue;

            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retryCount < maxCompensations) {
                task.setStatus(ChatTaskStatus.WAITING_RETRY.name());
                task.setRetryCount(retryCount + 1);
                task.setLastError("任务处理超时，已触发第 " + (retryCount + 1) + " 次补偿投递");
                task.setVersion(task.getVersion() == null ? 1L : task.getVersion() + 1);
                taskMapper.updateById(task);
                TaskOutbox compensation = chatService.newOutbox(task.getTaskId(), task.getRequestPayload());
                if ("world".equals(task.getPlayerId())) {
                    compensation.setRoutingKey(RabbitConfig.BACKGROUND_ROUTING_KEY);
                }
                outboxMapper.insert(compensation);
                afterCommit(() -> chatService.cacheTask(task));
                continue;
            }

            task.setStatus(ChatTaskStatus.TIMEOUT.name());
            task.setLastError("大模型推理超时，任务已在补偿上限后关闭");
            task.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
            task.setVersion(task.getVersion() == null ? 1L : task.getVersion() + 1);
            taskMapper.updateById(task);
            afterCommit(() -> {
                chatService.cacheTask(task);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("success", false);
                payload.put("completed", true);
                payload.put("status", ChatTaskStatus.TIMEOUT.name());
                payload.put("task_id", task.getTaskId());
                payload.put("npc_id", task.getNpcId());
                payload.put("reply", task.getLastError());
                payload.put("mood", task.getMood());
                webSocketPushService.sendLater(task.getPlayerId(), payload);
            });
        }
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
