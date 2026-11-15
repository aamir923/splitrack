package io.gave.group.service;

import io.gave.group.domain.Group;
import io.gave.group.domain.GroupMember;
import io.gave.group.exception.GroupOperationException;
import io.gave.group.repository.GroupMemberRepository;
import io.gave.group.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static io.gave.group.exception.GroupOperationException.Code.GROUP_NOT_FOUND;

/** Local writes only. Call through GroupWrites after remote eligibility checks have completed. */
@Service
@Transactional
public class GroupTransactions {
    private final GroupRepository groups;
    private final GroupMemberRepository members;

    public GroupTransactions(GroupRepository groups, GroupMemberRepository members) {
        this.groups = groups;
        this.members = members;
    }

    public Group create(String name, UUID creatorId) {
        Group group = groups.saveAndFlush(new Group(name, creatorId));
        members.saveAndFlush(new GroupMember(group.getId(), creatorId));
        return group;
    }

    public Group rename(UUID groupId, UUID callerId, String name) {
        requireMembership(groupId, callerId);
        Group group = groups.findById(groupId).orElseThrow(() -> new GroupOperationException(GROUP_NOT_FOUND));
        group.rename(name);
        return groups.saveAndFlush(group);
    }

    public GroupMember addMember(UUID groupId, UUID callerId, UUID userId) {
        requireMembership(groupId, callerId);
        return members.saveAndFlush(new GroupMember(groupId, userId));
    }

    private void requireMembership(UUID groupId, UUID callerId) {
        if (!members.existsByGroupIdAndUserId(groupId, callerId)) {
            throw new GroupOperationException(GROUP_NOT_FOUND);
        }
    }
}
