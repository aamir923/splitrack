package io.gave.group.exception;

import org.springframework.http.HttpStatus;

public class UserDirectoryException extends RuntimeException {
    public enum Code {
        AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
        USER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "User validation unavailable");

        private final HttpStatus status;
        private final String detail;

        Code(HttpStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    private final Code code;

    public UserDirectoryException(Code code) {
        super(code.detail);
        this.code = code;
    }

    public Code code() { return code; }
    public HttpStatus status() { return code.status; }
}
