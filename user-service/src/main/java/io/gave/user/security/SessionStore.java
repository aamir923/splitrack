package io.gave.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class SessionStore {

    private static final String SESSION = "session:";
    private static final String USER_SESSIONS = "user_sessions:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public SessionStore(StringRedisTemplate redis,
                        @Value("${gave.session.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public String create(UUID userId, String device) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = hash(token);

        redis.opsForHash().putAll(SESSION + id,
                Map.of("userId", userId.toString(), "device", device));
        redis.expire(SESSION + id, ttl);
        redis.opsForSet().add(USER_SESSIONS + userId, id);
        return token;
    }

    public Optional<UUID> resolve(String token) {
        Object userId = redis.opsForHash().get(SESSION + hash(token), "userId");
        return userId == null
                ? Optional.empty()
                : Optional.of(UUID.fromString(userId.toString()));
    }

    public void revoke(String token) {
        String id = hash(token);
        Object userId = redis.opsForHash().get(SESSION + id, "userId");
        redis.delete(SESSION + id);
        if (userId != null) {
            redis.opsForSet().remove(USER_SESSIONS + userId, id);
        }
    }

    public void revokeAll(UUID userId) {
        Set<String> ids = redis.opsForSet().members(USER_SESSIONS + userId);
        if (ids != null) {
            ids.forEach(id -> redis.delete(SESSION + id));
        }
        redis.delete(USER_SESSIONS + userId);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}