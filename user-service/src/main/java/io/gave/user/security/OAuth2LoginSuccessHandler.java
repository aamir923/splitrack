package io.gave.user.security;

import io.gave.user.domain.AuthProvider;
import io.gave.user.domain.User;
import io.gave.user.service.ProviderProfile;
import io.gave.user.service.UserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserProvisioningService provisioningService;

    public OAuth2LoginSuccessHandler(UserProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
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

        response.sendRedirect("/api/health");
    }
}