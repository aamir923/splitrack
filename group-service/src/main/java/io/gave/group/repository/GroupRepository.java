package io.gave.group.repository;

import io.gave.group.domain.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<Group, UUID> {
    @Query("""
            select g from Group g
            where exists (select m.id from GroupMember m where m.groupId = g.id and m.userId = :userId)
            order by g.createdAt asc, g.id asc
            """)
    List<Group> findAllByMemberId(@Param("userId") UUID userId);
}
