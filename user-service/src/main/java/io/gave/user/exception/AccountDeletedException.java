package io.gave.user.exception;

import java.util.UUID;

public class AccountDeletedException extends RuntimeException {

    public AccountDeletedException(UUID userId) {
        super("Account has been deleted: " + userId);
    }
}
