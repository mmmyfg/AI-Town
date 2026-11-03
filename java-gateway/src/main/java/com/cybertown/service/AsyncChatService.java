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
import com.cybertown.mapper.ClientRequestKeyMapper;
import com.cybertown.mapper.ConversationMapper;
import com.cybertown.mapper.NpcMapper;
import com.cybertown.mapper.PlayerNpcRelationMapper;
import com.cybertown.mapper.TaskOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AsyncChatService {
    private static final Duration TASK_TTL = Duration.ofMinutes(30);

    @Autowired private NpcMapper npcMapper;
    @Autowired private ChatTaskMapper chatTaskMapper;
    @Autowired private ClientRequestKeyMapper clientRequestKeyMapper;
    @Autowired private PlayerNpcRelationMapper relationMapper;
    @Autowired private ConversationMapper conversationMapper;
    @Autowired private TaskOutboxMapper outboxMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Value("${chat.scene.max-npcs:3}") private int maxSceneNpcs;
    @Value("${chat.scene.rounds:1}") private int sceneRounds = 1;
    @Value("${chat.scene.max-model-calls:3}") private int maxSceneModelCalls = 3;

    @Transactional
    public Map<String, Object> enqueueSceneTask(String playerId, List<String> npcIds,
                                                String message, String clientRequestId) {
        return createSceneTask(playerId, npcIds, message, clientRequestId, false);
    }

    @Transactional
    public Map<String, Object> enqueueAutonomousSceneTask(List<String> npcIds, String event) {
        return createSceneTask("world", npcIds, event, null, true);
    }

    private Map<String, Object> createSceneTask(String playerId, List<String> npcIds,
                                                String message, String clientRequestId,
                                                boolean background) {
        if (playerId == null || playerId.isBlank() || playerId.length() > 64
                || message == null || message.isBlank()
                || npcIds == null || npcIds.size() < 2 || npcIds.size() > maxSceneNpcs
                || sceneRounds < 1 || sceneRounds > 2 || npcIds.size() * sceneRounds > maxSceneModelCalls
                || npcIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64)
                || new HashSet<>(npcIds).size() != npcIds.size()) {
            return Map.of("success", false, "reply", "场景请求需要玩家、消息和 2 至 " + maxSceneNpcs + " 位不同 NPC");
        }
        List<String> sorted = new ArrayList<>(npcIds);
        sorted.sort(String::compareTo);
        String sceneNpcId = "scene:" + UUID.nameUUIDFromBytes(String.join("|", sorted).getBytes(StandardCharsets.UTF_8));
        String conversationId = UUID.nameUUIDFromBytes((playerId + "|" + sceneNpcId).getBytes(StandardCharsets.UTF_8)).toString();
        String taskId = UUID.randomUUID().toString();
        List<Npc> npcs = new ArrayList<>();
        for (String npcId : npcIds) {
            Npc npc = npcMapper.selectOne(new LambdaQueryWrapper<Npc>().eq(Npc::getNpcId, npcId));
            if (npc == null) return Map.of("success", false, "reply", "NPC 不存在: " + npcId);
            npcs.add(npc);
        }
        Map<String, Object> replay = claimRequest(playerId, clientRequestId, taskId,
                fingerprint("group", String.join("\u0000", npcIds), message));
        if (replay != null) return replay;
        List<ChatTaskMessage.SceneParticipant> participants = new ArrayList<>();
        for (Npc npc : npcs) {
            String npcId = npc.getNpcId();
            String defaultMood = readCurrentMood(npc);
            relationMapper.insertIfAbsent(playerId, npcId, defaultMood);
            PlayerNpcRelation relation = relationMapper.selectRelation(playerId, npcId);
            String mood = defaultMood;
            ChatTaskMessage.SceneParticipant participant = new ChatTaskMessage.SceneParticipant();
            participant.setNpcId(npcId);
            participant.setPersonality(buildPersonality(npc));
            participant.setObservation(background
                    ? "【NPC自主互动】你当前状态：" + npc.getInitialStatus() + "；心情：【" + mood + "】。" + message
                    : buildObservation(npc, mood, message, "CHAT", ""));
            participant.setMemory("");
            participant.setMood(mood);
            participant.setCurrentAffinity(relation == null || relation.getAffinity() == null ? 50 : relation.getAffinity());
            participants.add(participant);
        }
        conversationMapper.insertIfAbsent(conversationId, playerId, sceneNpcId);
        long sequence = conversationMapper.nextSequence(conversationId);
        conversationMapper.insertMessage(conversationId, taskId, playerId, sceneNpcId, sequence, "USER", message);
        ChatTaskMessage taskMessage = new ChatTaskMessage();
        taskMessage.setMode("group");
        taskMessage.setSceneRounds(sceneRounds);
        taskMessage.setTaskId(taskId);
        taskMessage.setTraceId(taskId);
        taskMessage.setUserMessage(message);
        taskMessage.setPlayerId(playerId);
        taskMessage.setNpcId(sceneNpcId);
        taskMessage.setConversationId(conversationId);
        taskMessage.setParticipants(participants);
        String payload;
        try { payload = objectMapper.writeValueAsString(taskMessage); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("场景任务序列化失败", exception); }
        ChatTask task = new ChatTask();
        task.setTaskId(taskId);
        task.setConversationId(conversationId);
        task.setPlayerId(playerId);
        task.setNpcId(sceneNpcId);
        task.setStatus(ChatTaskStatus.PENDING.name());
        task.setRequestPayload(payload);
        task.setMood("CALM");
        task.setAffinityChange(0);
        task.setRetryCount(0);
        task.setVersion(0L);
        chatTaskMapper.insert(task);
        TaskOutbox requestOutbox = newOutbox(taskId, payload);
        if (background) requestOutbox.setRoutingKey(RabbitConfig.BACKGROUND_ROUTING_KEY);
        outboxMapper.insert(requestOutbox);
        afterCommit(() -> cacheTask(task));
        return Map.of("success", true, "task_id", taskId, "conversation_id", conversationId,
                "status", ChatTaskStatus.PENDING.name(), "reply", "场景正在生成...");
    }

    @Transactional
    public Map<String, Object> enqueueChatTask(
            String playerId, String npcId, String message, String actionType,
            String itemName, String clientRequestId) {
        if (playerId == null || playerId.isBlank() || playerId.length() > 64
                || npcId == null || npcId.isBlank() || npcId.length() > 64
                || message == null || message.isBlank()) {
            return Map.of("success", false, "reply", "需要有效的玩家、NPC 和消息");
        }
        Npc npc = npcMapper.selectOne(new LambdaQueryWrapper<Npc>().eq(Npc::getNpcId, npcId));
        if (npc == null) {
            return Map.of("success", false, "reply", "（这位居民似乎还没去数据库登记户口...）");
        }

        String taskId = UUID.randomUUID().toString();
        Map<String, Object> replay = claimRequest(playerId, clientRequestId, taskId,
                fingerprint("single", npcId, message, fallbackText(actionType, "CHAT"), fallbackText(itemName, "")));
        if (replay != null) return replay;
        String conversationId = UUID.nameUUIDFromBytes(
                (playerId + "|" + npcId).getBytes(StandardCharsets.UTF_8)).toString();
        String defaultMood = readCurrentMood(npc);

        relationMapper.insertIfAbsent(playerId, npcId, defaultMood);
        PlayerNpcRelation relation = relationMapper.selectRelation(playerId, npcId);
        String currentMood = defaultMood;

        conversationMapper.insertIfAbsent(conversationId, playerId, npcId);
        long userSequence = conversationMapper.nextSequence(conversationId);
        conversationMapper.insertMessage(
                conversationId, taskId, playerId, npcId, userSequence, "USER", message);

        ChatTaskMessage taskMessage = new ChatTaskMessage();
        taskMessage.setTaskId(taskId);
        taskMessage.setTraceId(taskId);
        taskMessage.setUserMessage(message);
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

    private String fingerprint(String... parts) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Map<String, Object> claimRequest(String playerId, String requestId, String taskId,
                                             String fingerprint) {
        if (requestId == null || requestId.isBlank()) return null;
        if (requestId.length() > 128) throw new IllegalArgumentException("client_request_id too long");
        if (clientRequestKeyMapper.insertIfAbsent(playerId, requestId, taskId, fingerprint) == 1) return null;
        if (!fingerprint.equals(clientRequestKeyMapper.selectFingerprint(playerId, requestId))) {
            return Map.of("success", false, "status", "CONFLICT",
                    "reply", "client_request_id 已用于不同请求");
        }
        String existingTaskId = clientRequestKeyMapper.selectTaskId(playerId, requestId);
        if (existingTaskId == null) throw new IllegalStateException("请求幂等键已存在但任务不可见");
        Map<String, Object> result = new LinkedHashMap<>(getTaskResult(existingTaskId));
        result.put("idempotent_replay", true);
        return result;
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

    public Map<String, Object> getTaskResultForPlayer(String taskId, String playerId) {
        ChatTask task = chatTaskMapper.selectByTaskId(taskId);
        if (task == null || !playerId.equals(task.getPlayerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到这条对话任务");
        }
        return getTaskResult(taskId);
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

        if (task.getNpcId().startsWith("scene:")) {
            return handleSceneResult(task, message);
        }

        String mood = readCurrentMood(task.getNpcId(),
                fallbackText(message.getMood(), fallbackText(task.getMood(), "CALM")));
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
        enqueueMemoryEvent(task, List.of(memoryCandidate(task.getTaskId(), task.getNpcId(),
                requestMessage(task), message.getReply(), message.getAffinityChange())));
        afterCommit(() -> cacheTask(task));
        return outcome(task, true);
    }

    private ResultHandlingOutcome handleSceneResult(ChatTask task, ChatResultMessage message) {
        List<Map<String, Object>> sceneMessages;
        try {
            sceneMessages = objectMapper.readValue(message.getReply(), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("无效场景结果", exception);
        }
        if (sceneMessages == null || sceneMessages.isEmpty()) throw new IllegalArgumentException("空场景结果");
        List<Map<String, Object>> memoryCandidates = new ArrayList<>();
        try {
            ChatTaskMessage request = objectMapper.readValue(task.getRequestPayload(), ChatTaskMessage.class);
            List<String> expected = request.getParticipants().stream().map(ChatTaskMessage.SceneParticipant::getNpcId).toList();
            int rounds = request.getSceneRounds();
            if (rounds < 1 || rounds > 2 || sceneMessages.size() != expected.size() * rounds) {
                throw new IllegalArgumentException("场景结果数量不匹配");
            }
            int completed = 0;
            for (int i = 0; i < sceneMessages.size(); i++) {
                Map<String, Object> item = sceneMessages.get(i);
                String npcId = String.valueOf(item.get("npcId"));
                int round = i / expected.size() + 1;
                int turn = i % expected.size() + 1;
                if (!expected.get(turn - 1).equals(npcId)
                        || item.containsKey("round") && !(item.get("round") instanceof Number number && number.intValue() == round)
                        || item.containsKey("turn") && !(item.get("turn") instanceof Number number && number.intValue() == turn)) {
                    throw new IllegalArgumentException("场景发言者或轮次不匹配");
                }
                if ("FAILED".equals(item.get("status"))) continue;
                if (!"COMPLETED".equals(item.get("status"))) throw new IllegalArgumentException("未知角色状态");
                completed++;
                if (!(item.get("reply") instanceof String reply)) throw new IllegalArgumentException("无效 NPC 回复");
                if (reply.isBlank()) throw new IllegalArgumentException("空 NPC 回复");
                if (!(item.get("affinityChange") instanceof Integer delta)) {
                    throw new IllegalArgumentException("非法好感度变化");
                }
                if (delta < -3 || delta > 3) throw new IllegalArgumentException("非法好感度变化");
                String mood = readCurrentMood(npcId, String.valueOf(item.getOrDefault("mood", "CALM")));
                relationMapper.applyResult(task.getPlayerId(), npcId, delta, mood);
                item.put("mood", mood);
                PlayerNpcRelation relation = relationMapper.selectRelation(task.getPlayerId(), npcId);
                item.put("currentAffinity", relation == null ? 50 : value(relation.getAffinity()));
                long sequence = conversationMapper.nextSequence(task.getConversationId());
                item.put("sequence", sequence);
                conversationMapper.insertSceneMessage(task.getConversationId(), task.getTaskId(), task.getPlayerId(),
                        npcId, sequence, reply, round);
                Map<String, Object> candidate = memoryCandidate(task.getTaskId(), npcId,
                        request.getUserMessage(), reply, delta);
                candidate.put("sourceId", task.getTaskId() + ":" + npcId + ":" + round);
                memoryCandidates.add(candidate);
            }
            if (completed == 0) throw new IllegalArgumentException("所有场景角色都生成失败");
            task.setReply(objectMapper.writeValueAsString(sceneMessages));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("场景结果序列化失败", exception);
        }
        task.setStatus(ChatTaskStatus.COMPLETED.name());
        task.setCompletedAt(utcNow());
        task.setLastError(null);
        task.setVersion(value(task.getVersion()) + 1);
        chatTaskMapper.updateById(task);
        enqueueMemoryEvent(task, memoryCandidates);
        afterCommit(() -> cacheTask(task));
        return outcome(task, true);
    }

    private String requestMessage(ChatTask task) {
        if (task.getRequestPayload() == null || task.getRequestPayload().isBlank()) return null;
        try { return objectMapper.readValue(task.getRequestPayload(), ChatTaskMessage.class).getUserMessage(); }
        catch (JsonProcessingException exception) { return null; }
    }

    private Map<String, Object> memoryCandidate(String taskId, String npcId,
                                                String playerMessage, String reply, int delta) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("sourceId", taskId + ":" + npcId);
        candidate.put("npcId", npcId);
        candidate.put("playerMessage", playerMessage);
        candidate.put("replyText", reply);
        candidate.put("affinityChange", delta);
        return candidate;
    }

    private void enqueueMemoryEvent(ChatTask task, List<Map<String, Object>> candidates) {
        candidates = candidates.stream().filter(candidate ->
                candidate.get("playerMessage") instanceof String text && !text.isBlank()
                        && candidate.get("replyText") instanceof String reply && !reply.isBlank()).toList();
        if (candidates.isEmpty()) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "MEMORY_CONSOLIDATE");
        event.put("taskId", task.getTaskId());
        event.put("traceId", task.getTaskId());
        event.put("playerId", task.getPlayerId());
        event.put("conversationId", task.getConversationId());
        event.put("candidates", candidates);
        try {
            String payload = objectMapper.writeValueAsString(event);
            TaskOutbox outbox = newOutbox(task.getTaskId(), payload);
            outbox.setRoutingKey(RabbitConfig.BACKGROUND_ROUTING_KEY);
            outboxMapper.insert(outbox);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆任务序列化失败", exception);
        }
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
            state.put("trace_id", task.getTaskId());
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
        result.put("trace_id", stringValue(raw.get("trace_id"), stringValue(raw.get("task_id"), "")));
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
        addSceneMessages(result, stringValue(raw.get("npc_id"), ""), stringValue(raw.get("reply"), ""));
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
        result.put("trace_id", task.getTaskId());
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
        addSceneMessages(result, task.getNpcId(), task.getReply());
        return result;
    }

    private void addSceneMessages(Map<String, Object> result, String npcId, String reply) {
        if (npcId == null || !npcId.startsWith("scene:")) return;
        result.put("mode", "group");
        if (!"COMPLETED".equals(result.get("status"))) return;
        try { result.put("scene_messages", objectMapper.readValue(reply, new TypeReference<List<Map<String, Object>>>() {})); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已保存的场景结果无法读取", exception); }
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
        return readCurrentMood(npc.getNpcId(), fallbackText(npc.getCurrentMood(), "CALM"));
    }

    private String readCurrentMood(String npcId, String fallback) {
        try {
            String cachedMood = redisTemplate.opsForValue().get("npc:mood:" + npcId);
            if (cachedMood != null && !cachedMood.isBlank()) return cachedMood;
        } catch (Exception ignored) {
        }
        return fallback;
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
