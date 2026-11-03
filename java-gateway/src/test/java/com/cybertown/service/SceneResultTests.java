package com.cybertown.service;

import com.cybertown.dto.ChatResultMessage;
import com.cybertown.entity.ChatTask;
import com.cybertown.enums.ChatTaskStatus;
import com.cybertown.mapper.ChatTaskMapper;
import com.cybertown.mapper.ConversationMapper;
import com.cybertown.mapper.PlayerNpcRelationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneResultTests {
    @Mock private ChatTaskMapper taskMapper;
    @Mock private PlayerNpcRelationMapper relationMapper;
    @Mock private ConversationMapper conversationMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private AsyncChatService service;

    @Test
    void scenePersistsEachSpeakerOnceInPlannedOrder() {
        ChatTask task = new ChatTask();
        task.setId(1L);
        task.setTaskId("task-scene");
        task.setConversationId("conversation-scene");
        task.setPlayerId("player");
        task.setNpcId("scene:two");
        task.setStatus(ChatTaskStatus.PROCESSING.name());
        task.setRequestPayload("{\"mode\":\"group\",\"participants\":[{\"npcId\":\"a\"},{\"npcId\":\"b\"}]}");

        ChatResultMessage result = new ChatResultMessage();
        result.setTaskId("task-scene");
        result.setSuccess(true);
        result.setReply("[{\"npcId\":\"a\",\"reply\":\"hello\",\"affinityChange\":1,\"status\":\"COMPLETED\"},"
                + "{\"npcId\":\"b\",\"reply\":\"hi\",\"affinityChange\":0,\"status\":\"COMPLETED\"}]");
        when(taskMapper.selectByTaskIdForUpdate("task-scene")).thenReturn(task);
        when(conversationMapper.nextSequence("conversation-scene")).thenReturn(2L, 3L);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        assertThat(service.handleResultEvent(result).changed()).isTrue();
        assertThat(service.handleResultEvent(result).changed()).isFalse();
        assertThat(task.getStatus()).isEqualTo(ChatTaskStatus.COMPLETED.name());
        verify(relationMapper, times(1)).applyResult("player", "a", 1, "CALM");
        verify(relationMapper, times(1)).applyResult("player", "b", 0, "CALM");
        verify(conversationMapper, times(2)).insertSceneMessage(anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
                org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void twoRoundsPersistRepeatedSpeakersWithSeparateTurnNumbers() {
        ChatTask task = new ChatTask();
        task.setTaskId("two-round-task");
        task.setConversationId("conversation-scene");
        task.setPlayerId("player");
        task.setNpcId("scene:two");
        task.setStatus(ChatTaskStatus.PROCESSING.name());
        task.setRequestPayload("{\"mode\":\"group\",\"sceneRounds\":2,\"participants\":[{\"npcId\":\"a\"},{\"npcId\":\"b\"}]}");
        ChatResultMessage result = new ChatResultMessage();
        result.setTaskId("two-round-task");
        result.setSuccess(true);
        result.setReply("[{\"npcId\":\"a\",\"round\":1,\"turn\":1,\"reply\":\"A1\",\"affinityChange\":1,\"status\":\"COMPLETED\"},"
                + "{\"npcId\":\"b\",\"round\":1,\"turn\":2,\"reply\":\"B1\",\"affinityChange\":0,\"status\":\"COMPLETED\"},"
                + "{\"npcId\":\"a\",\"round\":2,\"turn\":1,\"reply\":\"A2\",\"affinityChange\":0,\"status\":\"COMPLETED\"},"
                + "{\"npcId\":\"b\",\"round\":2,\"turn\":2,\"reply\":\"B2\",\"affinityChange\":1,\"status\":\"COMPLETED\"}]");
        when(taskMapper.selectByTaskIdForUpdate("two-round-task")).thenReturn(task);
        when(conversationMapper.nextSequence("conversation-scene")).thenReturn(2L, 3L, 4L, 5L);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        assertThat(service.handleResultEvent(result).changed()).isTrue();
        verify(conversationMapper, times(2)).insertSceneMessage(anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
                org.mockito.ArgumentMatchers.eq(1));
        verify(conversationMapper, times(2)).insertSceneMessage(anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
                org.mockito.ArgumentMatchers.eq(2));
        assertThat(task.getReply()).contains("\"round\":2");
    }
}
