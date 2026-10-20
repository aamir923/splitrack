package io.gave.apigateway.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration
public class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(@Value("${gave.jwt.private-key}") Resource privateKeyLocation,
                          @Value("${gave.jwt.public-key}") Resource publicKeyLocation,
                          @Value("${gave.jwt.key-id}") String keyId) throws IOException {
        RSAPrivateKey privateKey;
        try (InputStream in = privateKeyLocation.getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(in);
        }
        RSAPublicKey publicKey;
        try (InputStream in = publicKeyLocation.getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(in);
        }
        RSAKey jwk = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }
}