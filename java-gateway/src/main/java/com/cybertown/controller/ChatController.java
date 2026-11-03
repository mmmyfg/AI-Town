package com.cybertown.controller;

import com.cybertown.service.AsyncChatService;
import com.cybertown.service.PlayerIdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private AsyncChatService asyncChatService;
    @Autowired private PlayerIdentityService identities;

    @PostMapping("/chat")
    public Map<String, Object> chatWithNpc(@RequestBody GodotRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String playerId = playerId(authorization, request.getPlayer_id());
        System.out.println("💌 收到 Godot 消息，目标NPC: [" + request.getNpc_id() + "], 交互类型: [" + request.getActionType() + "]");
        return asyncChatService.enqueueChatTask(
                playerId,
                request.getNpc_id(),
                request.getMessage(),
                request.getActionType(),
                request.getItemName(),
                request.getClient_request_id()
        );
    }

    @GetMapping("/chat/result/{taskId}")
    public Map<String, Object> getChatResult(@PathVariable String taskId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String playerId = identities.authenticate(authorization);
        return playerId == null ? asyncChatService.getTaskResult(taskId)
                : asyncChatService.getTaskResultForPlayer(taskId, playerId);
    }

    @PostMapping("/chat/scene")
    public Map<String, Object> chatScene(@RequestBody SceneRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return asyncChatService.enqueueSceneTask(playerId(authorization, request.getPlayer_id()), request.getNpc_ids(),
                request.getMessage(), request.getClient_request_id());
    }

    private String playerId(String authorization, String suppliedPlayerId) {
        String authenticated = identities.authenticate(authorization);
        if (authenticated == null) {
            return suppliedPlayerId == null || suppliedPlayerId.isBlank() ? "player_1" : suppliedPlayerId;
        }
        if (suppliedPlayerId != null && !suppliedPlayerId.isBlank() && !authenticated.equals(suppliedPlayerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求中的玩家 ID 不匹配");
        }
        return authenticated;
    }
}

class SceneRequest {
    private String player_id;
    private List<String> npc_ids;
    private String message;
    private String client_request_id;
    public String getPlayer_id() { return player_id; }
    public void setPlayer_id(String value) { player_id = value; }
    public List<String> getNpc_ids() { return npc_ids; }
    public void setNpc_ids(List<String> value) { npc_ids = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    public String getClient_request_id() { return client_request_id; }
    public void setClient_request_id(String value) { client_request_id = value; }
}

// ================= 数据传输对象定义 (DTO) =================

class GodotRequest {
    private String player_id;
    private String npc_id;
    private String message;
    private String actionType;
    private String itemName;
    private String client_request_id;

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
    public String getClient_request_id() { return client_request_id; }
    public void setClient_request_id(String value) { client_request_id = value; }
}
