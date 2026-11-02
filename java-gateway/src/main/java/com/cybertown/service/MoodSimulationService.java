package com.cybertown.service;

import com.cybertown.entity.Npc;
import com.cybertown.enums.NPCState;
import com.cybertown.mapper.NpcMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

@Service
@EnableScheduling
public class MoodSimulationService {

    @Autowired
    private NpcMapper npcMapper;

    @Autowired
    private StringRedisTemplate redisTemplate; // ✨ 注入 Redis 工具

    @Scheduled(fixedRate = 180000) // 每3分钟执行一次
    public void executeStateTransitions() {
        System.out.println("⚙️ [FSM 引擎] 开始推演 NPC 状态流转并写入 Redis...");

        List<Npc> npcs = npcMapper.selectList(null);
        int currentHour = LocalTime.now().getHour();

        for (Npc npc : npcs) {
            String currentMoodStr = npc.getCurrentMood() == null ? NPCState.CALM.name() : npc.getCurrentMood();
            NPCState currentState = NPCState.valueOf(currentMoodStr);
            NPCState nextState = currentState;
            int affinity = npc.getAffinity() == null ? 0 : npc.getAffinity();

            // FSM 核心流转逻辑
            switch (currentState) {
                case SLEEPING:
                    if (currentHour >= 8 && currentHour < 23) nextState = NPCState.CALM;
                    break;
                case CALM:
                    if (currentHour >= 23 || currentHour < 8) nextState = NPCState.SLEEPING;
                    else if (affinity > 10) nextState = NPCState.HAPPY;
                    else if (affinity < -5) nextState = NPCState.ANGRY;
                    break;
                case HAPPY:
                    if (currentHour >= 23) nextState = NPCState.SLEEPING;
                    else if (Math.random() > 0.7) nextState = NPCState.CALM;
                    break;
                case ANGRY:
                    if (affinity >= 0) nextState = NPCState.CALM;
                    break;
            }

            // 更新 MySQL 兜底，并强制刷新 Redis 缓存！
            npc.setCurrentMood(nextState.name());
            npcMapper.updateById(npc);

            // ✨ 将最新状态存入 Redis，Key 格式为 "npc:mood:雪之下雪乃"
            redisTemplate.opsForValue().set("npc:mood:" + npc.getNpcId(), nextState.name());

            if (nextState != currentState) {
                System.out.println("   -> [" + npc.getNpcId() + "] 触发流转: " + currentState + " ➡️ " + nextState);
            }
        }
    }
}