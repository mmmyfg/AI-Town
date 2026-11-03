package com.cybertown.controller;

import com.cybertown.entity.Npc;
import com.cybertown.mapper.NpcMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/npcs")
@CrossOrigin(origins = "*")
public class NpcController {
    private final NpcMapper npcs;
    private final StringRedisTemplate redis;

    public NpcController(NpcMapper npcs, StringRedisTemplate redis) {
        this.npcs = npcs;
        this.redis = redis;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, String>> items = residents().stream().map(npc -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("npcId", npc.getNpcId());
            item.put("title", value(npc.getTitle()));
            item.put("personality", value(npc.getPersonality()));
            item.put("background", value(npc.getBackground()));
            item.put("initialStatus", value(npc.getInitialStatus()));
            item.put("mood", mood(npc));
            return item;
        }).toList();
        return Map.of("npcs", items);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, String> dialogues = new LinkedHashMap<>();
        Map<String, String> moods = new LinkedHashMap<>();
        for (Npc npc : residents()) {
            dialogues.put(npc.getNpcId(), value(npc.getInitialStatus()));
            moods.put(npc.getNpcId(), mood(npc));
        }
        return Map.of("dialogues", dialogues, "moods", moods);
    }

    private List<Npc> residents() {
        return npcs.selectList(null).stream().sorted(Comparator.comparing(Npc::getNpcId)).toList();
    }

    private String mood(Npc npc) {
        try {
            String current = redis.opsForValue().get("npc:mood:" + npc.getNpcId());
            if (current != null && !current.isBlank()) return current;
        } catch (Exception ignored) {
            // MySQL's last known mood is available when Redis is down.
        }
        return npc.getCurrentMood() == null || npc.getCurrentMood().isBlank() ? "CALM" : npc.getCurrentMood();
    }

    private String value(String text) { return text == null ? "" : text; }
}
