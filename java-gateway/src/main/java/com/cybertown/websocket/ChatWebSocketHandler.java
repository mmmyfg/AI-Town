package com.cybertown.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cybertown.service.PlayerIdentityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, Set<WebSocketSession>> playerSessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionPlayers = new ConcurrentHashMap<>();

    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlayerIdentityService identities;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        String playerId;
        if (identities.required()) {
            try { playerId = identities.verify(queryParameter(session, "token")); }
            catch (Exception exception) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid player token"));
                return;
            }
        } else {
            playerId = queryParameter(session, "playerId");
        }
        if (playerId == null || playerId.isBlank()) {
            session.close(CloseStatus.BAD_DATA.withReason("playerId is required"));
            return;
        }
        sessionPlayers.put(session.getId(), playerId);
        playerSessions.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        System.out.println("🔌 WebSocket connected: " + session.getId() + " player=" + playerId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String playerId = sessionPlayers.remove(session.getId());
        if (playerId != null) {
            Set<WebSocketSession> sessions = playerSessions.get(playerId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) playerSessions.remove(playerId, sessions);
            }
        }
    }

    public void sendToPlayer(String playerId, Map<String, Object> payload) {
        if (playerId == null || playerId.isBlank()) return;
        Set<WebSocketSession> sessions = playerSessions.get(playerId);
        if (sessions == null || sessions.isEmpty()) return;
        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(payload));
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) continue;
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException exception) {
                    System.err.println("❌ WebSocket 定向推送失败 session=" + session.getId() + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            System.err.println("❌ WebSocket 消息序列化失败: " + exception.getMessage());
        }
    }

    private String queryParameter(WebSocketSession session, String name) {
        if (session.getUri() == null || session.getUri().getRawQuery() == null) return null;
        for (String pair : session.getUri().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
