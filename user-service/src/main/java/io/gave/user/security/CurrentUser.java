package io.gave.user.security;

import java.util.UUID;

public record CurrentUser(UUID userId, String sessionId) {
}