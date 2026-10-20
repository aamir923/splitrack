package io.gave.user.controller;

import io.gave.user.security.CurrentUser;
import io.gave.user.security.SessionStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final SessionStore sessions;

    public AuthController(SessionStore sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("userId", user.userId());
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal CurrentUser user) {
        sessions.revoke(user.sessionId());
        ResponseCookie expired = ResponseCookie.from(SessionStore.COOKIE_NAME, "")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }

    @PostMapping("/auth/logout-all")
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal CurrentUser user) {
        sessions.revokeAll(user.userId());
        return ResponseEntity.noContent().build();
    }
}
