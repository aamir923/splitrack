package io.gave.user.security;

import io.gave.user.domain.AuthProvider;
import io.gave.user.domain.User;
import io.gave.user.service.ProviderProfile;
import io.gave.user.service.UserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserProvisioningService provisioningService;
    private final SessionStore sessions;
    private final Duration ttl;
    private final boolean cookieSecure;

    public OAuth2LoginSuccessHandler(UserProvisioningService provisioningService,
                                     SessionStore sessions,
                                     @Value("${gave.session.ttl}") Duration ttl,
                                     @Value("${gave.session.cookie-secure}") boolean cookieSecure) {
        this.provisioningService = provisioningService;
        this.sessions = sessions;
        this.ttl = ttl;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        ProviderProfile profile = new ProviderProfile(
                AuthProvider.GOOGLE,
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                oidcUser.getFullName(),
                oidcUser.getPicture(),
                oidcUser.getLocale());

        User user = provisioningService.provision(profile);
        log.info("Login complete for user {}", user.getId());

        String device = Objects.toString(request.getHeader(HttpHeaders.USER_AGENT), "unknown");
        String token = sessions.create(user.getId(), device);

        ResponseCookie cookie = ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // the login handshake needed the HTTP session for state/nonce; we don't want it afterwards
        HttpSession handshake = request.getSession(false);
        if (handshake != null) {
            handshake.invalidate();
        }

        response.sendRedirect("/api/me");
    }
}