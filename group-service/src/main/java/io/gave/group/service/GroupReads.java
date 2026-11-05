package io.gave.group.service;

import io.gave.group.dto.GroupResponse;
import io.gave.group.dto.MemberResponse;
import io.gave.group.exception.GroupOperationException;
import io.gave.group.repository.GroupMemberRepository;
import io.gave.group.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static io.gave.group.exception.GroupOperationException.Code.GROUP_NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class GroupReads {
    private final GroupRepository groups;
    private final GroupMemberRepository members;

    public GroupReads(GroupRepository groups, GroupMemberRepository members) {
        this.groups = groups;
        this.members = members;
    }

    public List<GroupResponse> list(UUID callerId) {
        return groups.findAllByMemberId(callerId).stream().map(GroupResponse::from).toList();
    }

    public GroupResponse view(UUID groupId, UUID callerId) {
        requireMembership(groupId, callerId);
        return GroupResponse.from(groups.findById(groupId).orElseThrow(() -> new GroupOperationException(GROUP_NOT_FOUND)));
    }

    public List<MemberResponse> members(UUID groupId, UUID callerId) {
        requireMembership(groupId, callerId);
        return members.findAllByGroupIdOrderByJoinedAtAscUserIdAsc(groupId).stream().map(MemberResponse::from).toList();
    }

    public void requireMembership(UUID groupId, UUID callerId) {
        if (!members.existsByGroupIdAndUserId(groupId, callerId)) throw new GroupOperationException(GROUP_NOT_FOUND);
    }
}
