package com.cybertown.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cybertown.config.RabbitConfig;
import com.cybertown.dto.ChatResultMessage;
import com.cybertown.dto.ChatTaskMessage;
import com.cybertown.entity.ChatTask;
import com.cybertown.entity.Npc;
import com.cybertown.entity.PlayerNpcRelation;
import com.cybertown.entity.TaskOutbox;
import com.cybertown.enums.ChatTaskStatus;
import com.cybertown.mapper.ChatTaskMapper;
import com.cybertown.mapper.ConversationMapper;
import com.cybertown.mapper.NpcMapper;
import com.cybertown.mapper.PlayerNpcRelationMapper;
import com.cybertown.mapper.TaskOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AsyncChatService {
    private static final Duration TASK_TTL = Duration.ofMinutes(30);

    @Autowired private NpcMapper npcMapper;
    @Autowired private ChatTaskMapper chatTaskMapper;
    @Autowired private PlayerNpcRelationMapper relationMapper;
    @Autowired private ConversationMapper conversationMapper;
    @Autowired private TaskOutboxMapper outboxMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> enqueueChatTask(
            String playerId, String npcId, String message, String actionType, String itemName) {
        Npc npc = npcMapper.selectOne(new LambdaQueryWrapper<Npc>().eq(Npc::getNpcId, npcId));
        if (npc == null) {
            return Map.of("success", false, "reply", "（这位居民似乎还没去数据库登记户口...）");
        }

        String taskId = UUID.randomUUID().toString();
        String conversationId = UUID.nameUUIDFromBytes(
                (playerId + "|" + npcId).getBytes(StandardCharsets.UTF_8)).toString();
        String defaultMood = readCurrentMood(npc);

        relationMapper.insertIfAbsent(playerId, npcId, defaultMood);
        PlayerNpcRelation relation = relationMapper.selectRelation(playerId, npcId);
        String currentMood = relation == null ? defaultMood : fallbackText(relation.getMood(), defaultMood);

        conversationMapper.insertIfAbsent(conversationId, playerId, npcId);
        long userSequence = conversationMapper.nextSequence(conversationId);
        conversationMapper.insertMessage(
                conversationId, taskId, playerId, npcId, userSequence, "USER", message);

        ChatTaskMessage taskMessage = new ChatTaskMessage();
        taskMessage.setTaskId(taskId);
        taskMessage.setPlayerId(playerId);
        taskMessage.setNpcId(npcId);
        taskMessage.setConversationId(conversationId);
        taskMessage.setPersonality(buildPersonality(npc));
        taskMessage.setObservation(buildObservation(npc, currentMood, message, actionType, itemName));
        taskMessage.setMemory("");
        taskMessage.setMood(currentMood);
        taskMessage.setCurrentAffinity(relation == null || relation.getAffinity() == null ? 50 : relation.getAffinity());

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(taskMessage);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("聊天任务序列化失败", exception);
        }

        ChatTask task = new ChatTask();
        task.setTaskId(taskId);
        task.setConversationId(conversationId);
        task.setPlayerId(playerId);
        task.setNpcId(npcId);
        task.setStatus(ChatTaskStatus.PENDING.name());
        task.setRequestPayload(payload);
        task.setMood(currentMood);
        task.setAffinityChange(0);
        task.setRetryCount(0);
        task.setVersion(0L);
        chatTaskMapper.insert(task);

        outboxMapper.insert(newOutbox(taskId, payload));
        afterCommit(() -> cacheTask(task));

        return Map.of(
                "success", true,
                "task_id", taskId,
                "conversation_id", conversationId,
                "status", ChatTaskStatus.PENDING.name(),
                "reply", "NPC正在思考...",
                "mood", currentMood
        );
    }

    public Map<String, Object> getTaskResult(String taskId) {
        Map<Object, Object> cached = safeReadTaskCache(taskId);
        if (!cached.isEmpty()) {
            return mapCachedTask(cached);
        }

        ChatTask task = chatTaskMapper.selectByTaskId(taskId);
        if (task == null) {
            return Map.of(
                    "success", false, "completed", true, "status", "NOT_FOUND",
                    "reply", "未找到这条对话任务");
        }
        cacheTask(task);
        return mapTask(task);
    }

    @Transactional
    public ResultHandlingOutcome handleResultEvent(ChatResultMessage message) {
        ChatTask task = chatTaskMapper.selectByTaskIdForUpdate(message.getTaskId());
        if (task == null) {
            return new ResultHandlingOutcome(message.getTaskId(), message.getPlayerId(), message.getNpcId(), false);
        }

        ChatTaskStatus current = ChatTaskStatus.from(task.getStatus());
        if (current.isTerminal()) {
            return new ResultHandlingOutcome(task.getTaskId(), task.getPlayerId(), task.getNpcId(), false);
        }

        String eventType = fallbackText(message.getEventType(), "RESULT").toUpperCase();
        if ("PROCESSING".equals(eventType)) {
            task.setStatus(ChatTaskStatus.PROCESSING.name());
            task.setStartedAt(task.getStartedAt() == null ? utcNow() : task.getStartedAt());
            task.setRetryCount(Math.max(value(task.getRetryCount()), message.getRetryCount()));
            task.setLastError(null);
            task.setVersion(value(task.getVersion()) + 1);
            chatTaskMapper.updateById(task);
            afterCommit(() -> cacheTask(task));
            return outcome(task, true);
        }

        if ("RETRYING".equals(eventType)) {
            task.setStatus(ChatTaskStatus.WAITING_RETRY.name());
            task.setRetryCount(Math.max(value(task.getRetryCount()), message.getRetryCount()));
            task.setLastError(limitError(message.getErrorMessage()));
            task.setVersion(value(task.getVersion()) + 1);
            chatTaskMapper.updateById(task);
            afterCommit(() -> cacheTask(task));
            return outcome(task, true);
        }

        if (!message.isSuccess()) {
            task.setStatus(ChatTaskStatus.FAILED.name());
            task.setLastError(limitError(fallbackText(message.getErrorMessage(), "推理服务执行失败")));
            task.setCompletedAt(utcNow());
            task.setRetryCount(Math.max(value(task.getRetryCount()), message.getRetryCount()));
            task.setVersion(value(task.getVersion()) + 1);
            chatTaskMapper.updateById(task);
            afterCommit(() -> cacheTask(task));
            return outcome(task, true);
        }

        String mood = fallbackText(message.getMood(), fallbackText(task.getMood(), "CALM"));
        relationMapper.insertIfAbsent(task.getPlayerId(), task.getNpcId(), mood);
        relationMapper.applyResult(task.getPlayerId(), task.getNpcId(), message.getAffinityChange(), mood);
        PlayerNpcRelation relation = relationMapper.selectRelation(task.getPlayerId(), task.getNpcId());
        int currentAffinity = relation == null ? 50 : value(relation.getAffinity());

        long assistantSequence = conversationMapper.nextSequence(task.getConversationId());
        conversationMapper.insertMessage(
                task.getConversationId(), task.getTaskId(), task.getPlayerId(), task.getNpcId(),
                assistantSequence, "ASSISTANT", fallbackText(message.getReply(), "..."));

        task.setStatus(ChatTaskStatus.COMPLETED.name());
        task.setReply(fallbackText(message.getReply(), "..."));
        task.setAffinityChange(message.getAffinityChange());
        task.setCurrentAffinity(currentAffinity);
        task.setMood(mood);
        task.setCompletedAt(utcNow());
        task.setLastError(null);
        task.setVersion(value(task.getVersion()) + 1);
        chatTaskMapper.updateById(task);
        afterCommit(() -> cacheTask(task));
        return outcome(task, true);
    }

    public static String taskRedisKey(String taskId) {
        return "chat:task:" + taskId;
    }

    public TaskOutbox newOutbox(String taskId, String payload) {
        TaskOutbox outbox = new TaskOutbox();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setTaskId(taskId);
        outbox.setExchangeName(RabbitConfig.CHAT_EXCHANGE);
        outbox.setRoutingKey(RabbitConfig.TASK_ROUTING_KEY);
        outbox.setPayload(payload);
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        return outbox;
    }

    public void cacheTask(ChatTask task) {
        try {
            Map<String, String> state = new LinkedHashMap<>();
            state.put("task_id", task.getTaskId());
            state.put("conversation_id", task.getConversationId());
            state.put("player_id", task.getPlayerId());
            state.put("npc_id", task.getNpcId());
            state.put("status", task.getStatus());
            state.put("mood", fallbackText(task.getMood(), "CALM"));
            state.put("reply", fallbackText(task.getReply(), "NPC正在思考..."));
            state.put("affinity_change", String.valueOf(value(task.getAffinityChange())));
            if (task.getCurrentAffinity() != null) {
                state.put("current_affinity", String.valueOf(task.getCurrentAffinity()));
            }
            state.put("retry_count", String.valueOf(value(task.getRetryCount())));
            if (task.getLastError() != null) state.put("error_message", task.getLastError());
            if (task.getCreatedAt() != null) state.put("created_at", task.getCreatedAt().toString());
            if (task.getStartedAt() != null) state.put("processing_started_at", task.getStartedAt().toString());
            if (task.getCompletedAt() != null) state.put("completed_at", task.getCompletedAt().toString());
            redisTemplate.opsForHash().putAll(taskRedisKey(task.getTaskId()), state);
            redisTemplate.expire(taskRedisKey(task.getTaskId()), TASK_TTL);
        } catch (Exception exception) {
            System.err.println("⚠️ 写入任务缓存失败 taskId=" + task.getTaskId() + ": " + exception.getMessage());
        }
    }

    private Map<Object, Object> safeReadTaskCache(String taskId) {
        try {
            return redisTemplate.opsForHash().entries(taskRedisKey(taskId));
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private Map<String, Object> mapCachedTask(Map<Object, Object> raw) {
        String status = stringValue(raw.get("status"), ChatTaskStatus.PENDING.name());
        boolean terminal = isTerminal(status);
        boolean success = ChatTaskStatus.COMPLETED.name().equals(status) || !terminal;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("completed", terminal);
        result.put("status", status);
        result.put("task_id", stringValue(raw.get("task_id"), ""));
        result.put("conversation_id", stringValue(raw.get("conversation_id"), ""));
        result.put("reply", terminal && !success
                ? stringValue(raw.get("error_message"), "（连接大脑失败...）")
                : stringValue(raw.get("reply"), "NPC正在思考..."));
        result.put("mood", stringValue(raw.get("mood"), "CALM"));
        result.put("retry_count", intValue(raw.get("retry_count")));
        if (ChatTaskStatus.COMPLETED.name().equals(status)) {
            result.put("affinity_change", intValue(raw.get("affinity_change")));
            result.put("current_affinity", intValue(raw.get("current_affinity")));
        }
        return result;
    }

    private Map<String, Object> mapTask(ChatTask task) {
        boolean terminal = ChatTaskStatus.from(task.getStatus()).isTerminal();
        boolean success = ChatTaskStatus.COMPLETED.name().equals(task.getStatus()) || !terminal;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("completed", terminal);
        result.put("status", task.getStatus());
        result.put("task_id", task.getTaskId());
        result.put("conversation_id", task.getConversationId());
        result.put("reply", terminal && !success
                ? fallbackText(task.getLastError(), "（连接大脑失败...）")
                : fallbackText(task.getReply(), "NPC正在思考..."));
        result.put("mood", fallbackText(task.getMood(), "CALM"));
        result.put("retry_count", value(task.getRetryCount()));
        if (ChatTaskStatus.COMPLETED.name().equals(task.getStatus())) {
            result.put("affinity_change", value(task.getAffinityChange()));
            result.put("current_affinity", value(task.getCurrentAffinity()));
        }
        return result;
    }

    private boolean isTerminal(String status) {
        try {
            return ChatTaskStatus.from(status).isTerminal();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private ResultHandlingOutcome outcome(ChatTask task, boolean changed) {
        return new ResultHandlingOutcome(task.getTaskId(), task.getPlayerId(), task.getNpcId(), changed);
    }

    private String buildPersonality(Npc npc) {
        return String.format("你是%s，职业是%s。性格设定：%s。你的背景故事是：%s",
                npc.getNpcId(), npc.getTitle(), npc.getPersonality(), npc.getBackground());
    }

    private String buildObservation(Npc npc, String currentMood, String message, String actionType, String itemName) {
        if ("GIFT".equalsIgnoreCase(actionType)) {
            return String.format(
                    "【系统提示：玩家送了你一件礼物：%s】。你现在的状态是：%s。你当前的心情是：【%s】。玩家同时对你说：%s",
                    itemName, npc.getInitialStatus(), currentMood, message);
        }
        return String.format("你现在的状态是：%s。你当前的心情是：【%s】。玩家对你说：%s",
                npc.getInitialStatus(), currentMood, message);
    }

    private String readCurrentMood(Npc npc) {
        try {
            String cachedMood = redisTemplate.opsForValue().get("npc:mood:" + npc.getNpcId());
            if (cachedMood != null && !cachedMood.isBlank()) return cachedMood;
        } catch (Exception ignored) {
        }
        return fallbackText(npc.getCurrentMood(), "CALM");
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private int intValue(Object value) {
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private int value(Integer value) { return value == null ? 0 : value; }
    private long value(Long value) { return value == null ? 0L : value; }

    private String fallbackText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String limitError(String value) {
        String error = fallbackText(value, "未知错误");
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC); }

    public record ResultHandlingOutcome(String taskId, String playerId, String npcId, boolean changed) {}
}
