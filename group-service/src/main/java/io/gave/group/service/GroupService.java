package io.gave.group.service;

import io.gave.group.client.UserDirectoryClient;
import io.gave.group.dto.GroupResponse;
import io.gave.group.dto.MemberResponse;
import io.gave.group.exception.GroupOperationException;
import io.gave.group.security.CurrentUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static io.gave.group.exception.GroupOperationException.Code.USER_NOT_FOUND;
import static io.gave.group.exception.GroupOperationException.Code.INACTIVE_CALLER;

/** Remote checks finish before any local write transaction starts. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class GroupService {
    private final UserDirectoryClient directory;
    private final GroupReads reads;
    private final GroupWrites writes;

    public GroupService(UserDirectoryClient directory, GroupReads reads, GroupWrites writes) {
        this.directory = directory;
        this.reads = reads;
        this.writes = writes;
    }

    public GroupResponse create(CurrentUser caller, Jwt credential, String name) {
        requireActiveCaller(caller, credential);
        return GroupResponse.from(writes.create(name, caller.userId()));
    }

    public List<GroupResponse> list(CurrentUser caller, Jwt credential) {
        requireActiveCaller(caller, credential);
        return reads.list(caller.userId());
    }

    public GroupResponse view(CurrentUser caller, Jwt credential, UUID groupId) {
        requireActiveCaller(caller, credential);
        return reads.view(groupId, caller.userId());
    }

    public GroupResponse rename(CurrentUser caller, Jwt credential, UUID groupId, String name) {
        requireActiveCaller(caller, credential);
        return GroupResponse.from(writes.rename(groupId, caller.userId(), name));
    }

    public List<MemberResponse> members(CurrentUser caller, Jwt credential, UUID groupId) {
        requireActiveCaller(caller, credential);
        return reads.members(groupId, caller.userId());
    }

    public MemberResponse addMember(CurrentUser caller, Jwt credential, UUID groupId, UUID userId) {
        requireActiveCaller(caller, credential);
        reads.requireMembership(groupId, caller.userId());
        if (!caller.userId().equals(userId) && !directory.isActive(userId, credential)) {
            throw new GroupOperationException(USER_NOT_FOUND);
        }
        return MemberResponse.from(writes.addMember(groupId, caller.userId(), userId));
    }

    private void requireActiveCaller(CurrentUser caller, Jwt credential) {
        if (!directory.isActive(caller.userId(), credential)) throw new GroupOperationException(INACTIVE_CALLER);
    }
}
