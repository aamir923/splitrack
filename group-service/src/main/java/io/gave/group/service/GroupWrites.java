package io.gave.group.service;

import io.gave.group.domain.Group;
import io.gave.group.domain.GroupMember;
import io.gave.group.exception.GroupOperationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static io.gave.group.exception.GroupOperationException.Code.MEMBER_ALREADY_EXISTS;

/** Keeps failure translation outside the writer's transaction, after its proxy has rolled back. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class GroupWrites {
    private final GroupTransactions transactions;

    public GroupWrites(GroupTransactions transactions) { this.transactions = transactions; }

    public Group create(String name, UUID creatorId) { return transactions.create(name, creatorId); }

    public Group rename(UUID groupId, UUID callerId, String name) { return transactions.rename(groupId, callerId, name); }

    public GroupMember addMember(UUID groupId, UUID callerId, UUID userId) {
        try {
            return transactions.addMember(groupId, callerId, userId);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateMembership(ex)) throw new GroupOperationException(MEMBER_ALREADY_EXISTS);
            throw ex;
        }
    }

    private static boolean isDuplicateMembership(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uq_group_members_group_user".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
