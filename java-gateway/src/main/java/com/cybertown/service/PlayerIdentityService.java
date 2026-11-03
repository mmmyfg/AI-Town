package com.cybertown.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class PlayerIdentityService {
    private final boolean required;
    private final byte[] secret;
    private final long ttlSeconds;
    private final SecureRandom random = new SecureRandom();

    public PlayerIdentityService(@Value("${auth.required:true}") boolean required,
                                 @Value("${auth.token-secret:}") String configuredSecret,
                                 @Value("${auth.ttl-seconds:2592000}") long ttlSeconds) {
        this.required = required;
        this.ttlSeconds = ttlSeconds;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            secret = new byte[32];
            random.nextBytes(secret);
            System.err.println("⚠️ PLAYER_TOKEN_SECRET 未配置，游客令牌将在服务重启后失效");
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public GuestSession issueGuest() {
        String playerId = "guest_" + UUID.randomUUID().toString().replace("-", "");
        long expiry = Instant.now().getEpochSecond() + ttlSeconds;
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String payload = "v1." + encode(playerId.getBytes(StandardCharsets.UTF_8)) + "."
                + expiry + "." + encode(nonce);
        return new GuestSession(playerId, payload + "." + encode(sign(payload)), expiry);
    }

    public String authenticate(String authorization) {
        if (!required) return null;
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少玩家令牌");
        }
        return verify(authorization.substring(7));
    }

    public String verify(String token) {
        if (token == null) throw unauthorized();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || !"v1".equals(parts[0])) throw unauthorized();
        try {
            String payload = String.join(".", parts[0], parts[1], parts[2], parts[3]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[4]);
            if (!MessageDigest.isEqual(sign(payload), actual)) throw unauthorized();
            long expiry = Long.parseLong(parts[2]);
            if (expiry <= Instant.now().getEpochSecond()) throw unauthorized();
            String playerId = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (!playerId.matches("guest_[0-9a-f]{32}")) throw unauthorized();
            return playerId;
        } catch (IllegalArgumentException exception) {
            throw unauthorized();
        }
    }

    public boolean required() { return required; }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("无法签发玩家令牌", exception);
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "玩家令牌无效或已过期");
    }

    public record GuestSession(String player_id, String token, long expires_at) {}
}
