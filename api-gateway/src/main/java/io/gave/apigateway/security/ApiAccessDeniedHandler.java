package io.gave.apigateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (accessDeniedException instanceof CsrfException) {
            ProblemResponseWriter.write(response, HttpStatus.FORBIDDEN,
                    "Invalid or missing CSRF token");
            return;
        }
        ProblemResponseWriter.write(response, HttpStatus.FORBIDDEN,
                "You do not have permission to access this resource");
    }
}