package io.gave.user.dto;

import io.gave.user.domain.User;
import io.gave.user.domain.UserStatus;

import java.util.UUID;

public record UserSummary(UUID id, String displayName, String avatarUrl, UserStatus status) {

    private static final String DELETED_DISPLAY_NAME = "Deleted user";

    public static UserSummary from(User user) {
        if (user.isDeleted()) {
            return new UserSummary(user.getId(), DELETED_DISPLAY_NAME, null, UserStatus.DELETED);
        }
        return new UserSummary(user.getId(), user.getDisplayName(), user.getAvatarUrl(), user.getStatus());
    }
}
