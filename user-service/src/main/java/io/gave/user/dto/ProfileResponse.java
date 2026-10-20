package io.gave.user.dto;

import io.gave.user.domain.User;
import io.gave.user.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record ProfileResponse(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        String defaultCurrency,
        String locale,
        UserStatus status,
        Instant createdAt) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getDefaultCurrency(),
                user.getLocale(),
                user.getStatus(),
                user.getCreatedAt());
    }
}
