package io.gave.group.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class InternalJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final Duration MAX_LIFETIME = Duration.ofSeconds(60);
    private static final Duration FUTURE_CLOCK_TOLERANCE = Duration.ofSeconds(5);
    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Invalid internal credential", null);

    private final String issuer;
    private final String audience;
    private final Clock clock;

    public InternalJwtValidator(String issuer, String audience, Clock clock) {
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            Instant now = clock.instant();
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            Instant notBefore = jwt.getNotBefore();
            String subject = jwt.getSubject();
            String sessionId = jwt.getClaimAsString("sid");

            // Read the issuer as a string: "gave-gateway" is the agreed issuer, not a URL.
            boolean identityValid = issuer.equals(jwt.getClaimAsString("iss"))
                    && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                    && subject != null && UUID.fromString(subject).toString().equalsIgnoreCase(subject)
                    && sessionId != null && !sessionId.isBlank();
            boolean timesValid = issuedAt != null && expiresAt != null
                    && expiresAt.isAfter(now)
                    && expiresAt.isAfter(issuedAt)
                    && Duration.between(issuedAt, expiresAt).compareTo(MAX_LIFETIME) <= 0
                    && !issuedAt.isAfter(now.plus(FUTURE_CLOCK_TOLERANCE))
                    && (notBefore == null || (notBefore.isBefore(expiresAt)
                        && !notBefore.isAfter(now.plus(FUTURE_CLOCK_TOLERANCE))));

            return identityValid && timesValid ? OAuth2TokenValidatorResult.success() : invalid();
        } catch (RuntimeException ex) {
            // Invalid UUIDs or claim conversions are authentication failures, never controller 500s.
            return invalid();
        }
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
