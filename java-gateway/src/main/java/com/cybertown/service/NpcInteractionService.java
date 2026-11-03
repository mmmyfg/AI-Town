package com.cybertown.service;

import com.cybertown.entity.Npc;
import com.cybertown.mapper.NpcMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class NpcInteractionService {
    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if tonumber(redis.call('GET', KEYS[1]) or '0') >= tonumber(ARGV[1]) then return 0 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 86400) end
            return 1
            """, Long.class);

    private final NpcMapper npcs;
    private final AsyncChatService chats;
    private final StringRedisTemplate redis;

    @Value("${chat.npc-interaction.enabled:false}") private boolean enabled;
    @Value("${chat.npc-interaction.max-per-day:4}") private int maxPerDay;
    @Value("${chat.npc-interaction.cooldown-seconds:3600}") private long cooldownSeconds;

    public NpcInteractionService(NpcMapper npcs, AsyncChatService chats, StringRedisTemplate redis) {
        this.npcs = npcs;
        this.chats = chats;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${chat.npc-interaction.interval-ms:300000}")
    public void scheduleOneInteraction() {
        if (!enabled || maxPerDay < 1 || cooldownSeconds < 1) return;
        try {
            List<Npc> residents = new ArrayList<>(npcs.selectList(null));
            residents.sort(Comparator.comparing(Npc::getNpcId));
            List<List<Npc>> pairs = new ArrayList<>();
            for (int i = 0; i < residents.size(); i++) {
                for (int j = i + 1; j < residents.size(); j++) {
                    if (!"SLEEPING".equals(residents.get(i).getCurrentMood())
                            || !"SLEEPING".equals(residents.get(j).getCurrentMood())) {
                        pairs.add(List.of(residents.get(i), residents.get(j)));
                    }
                }
            }
            if (pairs.isEmpty()) return;
            Long cursor = redis.opsForValue().increment("npc:interaction:cursor");
            int start = Math.floorMod(cursor == null ? 0 : cursor.intValue(), pairs.size());
            String dailyKey = "npc:interaction:daily:" + LocalDate.now(ZoneOffset.UTC);
            for (int offset = 0; offset < pairs.size(); offset++) {
                List<Npc> pair = pairs.get((start + offset) % pairs.size());
                String pairId = UUID.nameUUIDFromBytes((pair.get(0).getNpcId() + "|" + pair.get(1).getNpcId())
                        .getBytes(StandardCharsets.UTF_8)).toString();
                Long claimed = redis.execute(CLAIM_SCRIPT,
                        List.of(dailyKey, "npc:interaction:cooldown:" + pairId),
                        String.valueOf(maxPerDay), String.valueOf(cooldownSeconds));
                if (claimed == null || claimed != 1L) continue;
                String event = "小镇里，" + pair.get(0).getNpcId() + "（" + pair.get(0).getInitialStatus()
                        + "）和" + pair.get(1).getNpcId() + "（" + pair.get(1).getInitialStatus()
                        + "）相遇。请根据各自性格与记忆交流一轮，然后自然结束。";
                chats.enqueueAutonomousSceneTask(
                        List.of(pair.get(0).getNpcId(), pair.get(1).getNpcId()), event);
                return;
            }
        } catch (Exception exception) {
            System.err.println("⚠️ NPC 后台互动跳过: " + exception.getMessage());
        }
    }
}
