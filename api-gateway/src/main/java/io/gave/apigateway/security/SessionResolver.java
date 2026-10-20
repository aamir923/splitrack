package io.gave.apigateway.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
public class SessionResolver {

    // contract with user-service: session:<sha256(token)> is a hash with a "userId" field
    private static final String SESSION = "session:";

    private final StringRedisTemplate redis;

    public SessionResolver(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<AuthenticatedSession> resolve(String token) {
        String sessionId = hash(token);
        Object userId = redis.opsForHash().get(SESSION + sessionId, "userId");
        return userId == null
                ? Optional.empty()
                : Optional.of(new AuthenticatedSession(UUID.fromString(userId.toString()), sessionId));
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