package com.cybertown.service;

import com.cybertown.dto.ChatResultMessage;
import com.cybertown.entity.ChatTask;
import com.cybertown.entity.PlayerNpcRelation;
import com.cybertown.enums.ChatTaskStatus;
import com.cybertown.mapper.ChatTaskMapper;
import com.cybertown.mapper.ConversationMapper;
import com.cybertown.mapper.PlayerNpcRelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncChatServiceIdempotencyTests {
    @Mock private ChatTaskMapper taskMapper;
    @Mock private PlayerNpcRelationMapper relationMapper;
    @Mock private ConversationMapper conversationMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @InjectMocks private AsyncChatService service;

    @Test
    void duplicateResultAppliesAffinityOnlyOnce() {
        ChatTask task = new ChatTask();
        task.setId(1L);
        task.setTaskId("task-1");
        task.setConversationId("conversation-1");
        task.setPlayerId("player-1");
        task.setNpcId("npc-1");
        task.setStatus(ChatTaskStatus.PROCESSING.name());
        task.setMood("CALM");
        task.setRetryCount(0);
        task.setVersion(0L);

        PlayerNpcRelation relation = new PlayerNpcRelation();
        relation.setAffinity(55);
        relation.setMood("HAPPY");

        ChatResultMessage result = new ChatResultMessage();
        result.setTaskId("task-1");
        result.setEventType("RESULT");
        result.setSuccess(true);
        result.setReply("你好");
        result.setAffinityChange(5);
        result.setMood("HAPPY");

        when(taskMapper.selectByTaskIdForUpdate("task-1")).thenReturn(task);
        when(relationMapper.selectRelation("player-1", "npc-1")).thenReturn(relation);
        when(conversationMapper.nextSequence("conversation-1")).thenReturn(2L);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        AsyncChatService.ResultHandlingOutcome first = service.handleResultEvent(result);
        AsyncChatService.ResultHandlingOutcome duplicate = service.handleResultEvent(result);

        assertThat(first.changed()).isTrue();
        assertThat(duplicate.changed()).isFalse();
        assertThat(task.getStatus()).isEqualTo(ChatTaskStatus.COMPLETED.name());
        verify(relationMapper, times(1)).applyResult("player-1", "npc-1", 5, "HAPPY");
        verify(conversationMapper, times(1)).insertMessage(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString());
    }
}
