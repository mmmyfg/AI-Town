package com.cybertown.controller;

import com.cybertown.entity.Npc;
import com.cybertown.mapper.NpcMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NpcControllerTests {
    @Mock private NpcMapper npcs;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @InjectMocks private NpcController controller;

    @Test
    void listAndStatusUseDatabaseProfilesAndLiveMood() {
        Npc npc = new Npc();
        npc.setNpcId("牧濑红莉栖");
        npc.setTitle("天才助手");
        npc.setInitialStatus("在实验室整理记录");
        npc.setCurrentMood("CALM");
        when(npcs.selectList(null)).thenReturn(List.of(npc));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("npc:mood:牧濑红莉栖")).thenReturn("HAPPY");

        Map<String, Object> listed = controller.list();
        Map<String, Object> status = controller.status();
        assertThat((List<?>) listed.get("npcs")).hasSize(1);
        assertThat(status.get("dialogues")).isEqualTo(Map.of("牧濑红莉栖", "在实验室整理记录"));
        assertThat(status.get("moods")).isEqualTo(Map.of("牧濑红莉栖", "HAPPY"));
    }
}
