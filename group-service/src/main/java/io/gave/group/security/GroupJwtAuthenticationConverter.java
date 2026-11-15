package io.gave.group.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class GroupJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new VerifiedGroupAuthentication(new CurrentUser(UUID.fromString(jwt.getSubject())), jwt);
    }

    /** Retains the verified JWT after ProviderManager erases transient authentication credentials. */
    private static final class VerifiedGroupAuthentication extends AbstractAuthenticationToken {
        private final CurrentUser user;
        private final Jwt jwt;

        private VerifiedGroupAuthentication(CurrentUser user, Jwt jwt) {
            super(List.of());
            this.user = user;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override
        public CurrentUser getPrincipal() {
            return user;
        }

        @Override
        public Jwt getCredentials() {
            return jwt;
        }

        @Override
        public String getName() {
            return user.userId().toString();
        }
    }
}
