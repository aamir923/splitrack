package io.gave.group.config;

import io.gave.group.security.InternalJwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.time.Clock;
import java.util.Date;
import java.util.Map;

@Configuration
public class JwtConfig {

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${gave.jwt.public-key}") Resource publicKey,
                          @Value("${gave.jwt.issuer}") String issuer,
                          @Value("${gave.jwt.audience}") String audience,
                          Clock clock) throws IOException {
        NimbusJwtDecoder decoder;
        try (var input = publicKey.getInputStream()) {
            decoder = NimbusJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(input))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }

        var standardClaims = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(claims -> {
            // Nimbus has already parsed NumericDate JSON numbers into Date values here.
            // Reject missing/wrongly typed required claims before Spring's coercion/conversion.
            if (!(claims.get("sub") instanceof String)
                    || !(claims.get("sid") instanceof String)
                    || !(claims.get("iat") instanceof Date)
                    || !(claims.get("exp") instanceof Date)) {
                throw new BadJwtException("Invalid internal credential claims");
            }
            return standardClaims.convert(claims);
        });
        decoder.setJwtValidator(new InternalJwtValidator(issuer, audience, clock));
        return decoder;
    }
}
