package io.gave.group.exception;

import org.springframework.http.HttpStatus;

public class GroupOperationException extends RuntimeException {
    public enum Code {
        GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "Group not found"),
        MEMBER_ALREADY_EXISTS(HttpStatus.CONFLICT, "User is already a group member");

        private final HttpStatus status;
        private final String detail;

        Code(HttpStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    private final Code code;

    public GroupOperationException(Code code) {
        super(code.detail);
        this.code = code;
    }

    public Code code() { return code; }
    public HttpStatus status() { return code.status; }
}
