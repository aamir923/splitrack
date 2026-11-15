package io.gave.group.controller;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import io.gave.group.exception.GroupOperationException;
import io.gave.group.exception.UserDirectoryException;
import io.gave.group.security.ProblemResponseWriter;
import io.gave.group.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private final ProblemResponseWriter problems;

    public GlobalExceptionHandler(ProblemResponseWriter problems) { this.problems = problems; }

    @ExceptionHandler(GroupOperationException.class)
    ResponseEntity<Object> groupFailure(GroupOperationException failure, HttpServletRequest request) {
        return problem(request, failure.status(), failure.code().name(), failure.getMessage(), new HttpHeaders());
    }

    @ExceptionHandler(UserDirectoryException.class)
    ResponseEntity<Object> directoryFailure(UserDirectoryException failure, HttpServletRequest request) {
        return problem(request, failure.status(), failure.code().name(), failure.getMessage(), new HttpHeaders());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> authenticationFailure(HttpServletRequest request) {
        return problem(request, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required", new HttpHeaders());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> accessDenied(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(HttpServletRequest request) {
        return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 404 -> "GROUP_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
        return problem(((ServletWebRequest) request).getRequest(), status, code,
                status.is5xxServerError() ? "An unexpected error occurred" : "Request could not be processed", headers);
    }

    private ResponseEntity<Object> problem(HttpServletRequest request, HttpStatusCode status, String code, String detail, HttpHeaders original) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(original);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(RequestIdFilter.HEADER, RequestIdFilter.requestId(request));
        if (status.value() == 401) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return new ResponseEntity<>(problems.body(request, status, code, detail), headers, status);
    }
}
