package io.gave.apigateway.security;

import java.util.UUID;

public record AuthenticatedSession(UUID userId, String sessionId) {
}