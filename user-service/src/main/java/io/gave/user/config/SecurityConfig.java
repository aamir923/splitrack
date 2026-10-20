package io.gave.user.config;

import io.gave.user.security.OAuth2LoginSuccessHandler;
import io.gave.user.security.SessionAuthenticationFilter;
import io.gave.user.security.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            OAuth2LoginSuccessHandler successHandler,
                                            SessionStore sessions) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/**"))
                .addFilterBefore(new SessionAuthenticationFilter(sessions),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth.successHandler(successHandler));
        return http.build();
    }
}