package io.gave.user.exception;

public class UnverifiedEmailException extends RuntimeException {

    public UnverifiedEmailException(String email) {
        super("Email is not verified by the provider: " + email);
    }
}