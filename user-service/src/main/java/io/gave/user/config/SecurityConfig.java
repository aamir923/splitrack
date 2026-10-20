package io.gave.user.config;

import io.gave.user.security.ApiAccessDeniedHandler;
import io.gave.user.security.ApiAuthenticationEntryPoint;
import io.gave.user.security.GaveJwtAuthenticationConverter;
import io.gave.user.security.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            OAuth2LoginSuccessHandler successHandler,
                                            JwtDecoder jwtDecoder,
                                            GaveJwtAuthenticationConverter jwtConverter,
                                            ApiAuthenticationEntryPoint apiEntryPoint,
                                            ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // no cookie-based authentication on this service, so nothing for CSRF to protect
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(apiEntryPoint,
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/error").permitAll()
                        // service-to-service endpoints: a valid JWT today (relayed from the user's request);
                        // this is the single place to require a service scope later
                        .requestMatchers("/internal/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth.successHandler(successHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(apiEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}