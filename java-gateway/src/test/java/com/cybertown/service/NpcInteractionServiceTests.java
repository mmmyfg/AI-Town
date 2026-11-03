package com.cybertown.service;

import com.cybertown.entity.Npc;
import com.cybertown.mapper.NpcMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NpcInteractionServiceTests {
    @Mock private NpcMapper npcs;
    @Mock private AsyncChatService chats;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    @Test
    void disabledSchedulerDoesNotCreateTasks() {
        NpcInteractionService service = new NpcInteractionService(npcs, chats, redis);
        service.scheduleOneInteraction();
        verify(chats, never()).enqueueAutonomousSceneTask(anyList(), anyString());
    }

    @Test
    void schedulerCreatesOneBoundedBackgroundInteraction() {
        NpcInteractionService service = new NpcInteractionService(npcs, chats, redis);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxPerDay", 4);
        ReflectionTestUtils.setField(service, "cooldownSeconds", 3600L);
        Npc first = npc("a");
        Npc second = npc("b");
        when(npcs.selectList(null)).thenReturn(List.of(first, second));
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment("npc:interaction:cursor")).thenReturn(1L);
        when(redis.execute(any(), anyList(), anyString(), anyString())).thenReturn(1L);

        service.scheduleOneInteraction();

        verify(chats).enqueueAutonomousSceneTask(List.of("a", "b"),
                "小镇里，a（散步）和b（散步）相遇。请根据各自性格与记忆交流一轮，然后自然结束。");
    }

    private Npc npc(String id) {
        Npc npc = new Npc();
        npc.setNpcId(id);
        npc.setInitialStatus("散步");
        npc.setCurrentMood("CALM");
        return npc;
    }
}
