package io.gave.group.dto;

import io.gave.group.domain.GroupMember;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(UUID userId, Instant joinedAt) {
    public static MemberResponse from(GroupMember member) {
        return new MemberResponse(member.getUserId(), member.getJoinedAt());
    }
}
