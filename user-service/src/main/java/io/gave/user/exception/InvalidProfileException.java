package io.gave.user.exception;

public class InvalidProfileException extends RuntimeException {

    public InvalidProfileException(String message) {
        super(message);
    }
}
