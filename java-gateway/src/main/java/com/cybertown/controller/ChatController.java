package com.cybertown.controller;

import com.cybertown.service.AsyncChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private AsyncChatService asyncChatService;

    @PostMapping("/chat")
    public Map<String, Object> chatWithNpc(@RequestBody GodotRequest request) {
        System.out.println("💌 收到 Godot 消息，目标NPC: [" + request.getNpc_id() + "], 交互类型: [" + request.getActionType() + "]");
        return asyncChatService.enqueueChatTask(
                request.getPlayer_id() == null || request.getPlayer_id().isBlank() ? "player_1" : request.getPlayer_id(),
                request.getNpc_id(),
                request.getMessage(),
                request.getActionType(),
                request.getItemName()
        );
    }

    @GetMapping("/chat/result/{taskId}")
    public Map<String, Object> getChatResult(@PathVariable String taskId) {
        return asyncChatService.getTaskResult(taskId);
    }
}

// ================= 数据传输对象定义 (DTO) =================

class GodotRequest {
    private String player_id;
    private String npc_id;
    private String message;
    private String actionType;
    private String itemName;

    public String getPlayer_id() { return player_id; }
    public void setPlayer_id(String player_id) { this.player_id = player_id; }

    public String getNpc_id() {
        return npc_id;
    }

    public void setNpc_id(String npc_id) {
        this.npc_id = npc_id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }
}
