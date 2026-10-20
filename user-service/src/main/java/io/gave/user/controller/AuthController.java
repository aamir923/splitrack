package io.gave.user.controller;

import io.gave.user.security.SessionAuthenticationFilter;
import io.gave.user.security.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final SessionStore sessions;

    public AuthController(SessionStore sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/me")
    Map<String, Object> me(Authentication authentication) {
        return Map.of("userId", authentication.getPrincipal());
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = SessionAuthenticationFilter.extractToken(request);
        if (token != null) {
            sessions.revoke(token);
        }
        ResponseCookie expired = ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, "")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }

    @PostMapping("/auth/logout-all")
    ResponseEntity<Void> logoutAll(Authentication authentication) {
        sessions.revokeAll((UUID) authentication.getPrincipal());
        return ResponseEntity.noContent().build();
    }
}