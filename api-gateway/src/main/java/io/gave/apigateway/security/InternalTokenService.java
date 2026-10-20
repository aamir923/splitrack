package io.gave.apigateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class InternalTokenService {

    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final Duration ttl;

    public InternalTokenService(JwtEncoder encoder,
                                @Value("${gave.jwt.issuer}") String issuer,
                                @Value("${gave.jwt.audience}") String audience,
                                @Value("${gave.jwt.key-id}") String keyId,
                                @Value("${gave.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
        this.ttl = ttl;
    }

    public String mint(AuthenticatedSession session) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(session.userId().toString())
                .claim("sid", session.sessionId())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}