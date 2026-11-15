package io.gave.group.repository;

import io.gave.group.domain.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {
    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

    List<GroupMember> findAllByGroupIdOrderByJoinedAtAscUserIdAsc(UUID groupId);
}
