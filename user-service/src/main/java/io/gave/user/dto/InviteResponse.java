package io.gave.user.dto;

import io.gave.user.domain.UserStatus;

import java.util.UUID;

public record InviteResponse(UUID id, UserStatus status) {
}
