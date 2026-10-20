package io.gave.user.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class GaveJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CurrentUser user = new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("sid"));
        return new UsernamePasswordAuthenticationToken(user, jwt, List.of());
    }
}