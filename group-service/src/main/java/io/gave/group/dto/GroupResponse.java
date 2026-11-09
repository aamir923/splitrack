package io.gave.group.dto;

import io.gave.group.domain.Group;

import java.time.Instant;
import java.util.UUID;

public record GroupResponse(UUID id, String name, UUID createdBy, Instant createdAt, Instant updatedAt) {
    public static GroupResponse from(Group group) {
        return new GroupResponse(group.getId(), group.getName(), group.getCreatedBy(), group.getCreatedAt(), group.getUpdatedAt());
    }
}
