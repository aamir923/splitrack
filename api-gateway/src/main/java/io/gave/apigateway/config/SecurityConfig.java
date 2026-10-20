package io.gave.apigateway.config;

import io.gave.apigateway.security.ApiAccessDeniedHandler;
import io.gave.apigateway.security.ApiAuthenticationEntryPoint;
import io.gave.apigateway.security.SessionAuthenticationFilter;
import io.gave.apigateway.security.SessionResolver;
import io.gave.apigateway.security.SpaCsrfTokenRequestHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SessionResolver sessions,
                                            ApiAuthenticationEntryPoint entryPoint,
                                            ApiAccessDeniedHandler accessDeniedHandler,
                                            @Value("${gave.csrf.cookie-secure}") boolean cookieSecure)
            throws Exception {

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(cookieSecure));

        // Bearer clients (mobile) send the credential themselves; a foreign page cannot, so no CSRF check
        RequestMatcher bearerRequests = request -> {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            return header != null && header.startsWith("Bearer ");
        };

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(bearerRequests))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new SessionAuthenticationFilter(sessions),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/oauth2/**", "/login/**", "/error").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}