package io.gave.apigateway.config;

import io.gave.apigateway.security.AuthenticatedSession;
import io.gave.apigateway.security.InternalTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.function.Function;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration
public class RouteConfig {

    // Google login: user-service needs the browser's own cookies (JSESSIONID handshake),
    // so these requests are forwarded as they are, minus any client-supplied Authorization header.
    @Bean
    RouterFunction<ServerResponse> loginRoutes(
            @Value("${gave.services.user-service.url}") String userServiceUrl) {

        RequestPredicate loginPaths = RequestPredicates.path("/oauth2/**")
                .or(RequestPredicates.path("/login/**"));

        return route("user-service-login")
                .route(loginPaths, http())
                .before(uri(userServiceUrl))
                .before(dropClientAuthorization())
                .build();
    }

    // API calls: services never see the client's ticket or cookies, only the internal JWT.
    @Bean
    RouterFunction<ServerResponse> userServiceApiRoutes(
            @Value("${gave.services.user-service.url}") String userServiceUrl,
            InternalTokenService tokens) {

        RequestPredicate apiPaths = RequestPredicates.path("/api/me/**")
                .or(RequestPredicates.path("/api/auth/**"))
                .or(RequestPredicates.path("/api/health"));

        return route("user-service-api")
                .route(apiPaths, http())
                .before(uri(userServiceUrl))
                .before(internalCredentials(tokens))
                .build();
    }

    private static Function<ServerRequest, ServerRequest> dropClientAuthorization() {
        return request -> ServerRequest.from(request)
                .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                .build();
    }

    private static Function<ServerRequest, ServerRequest> internalCredentials(InternalTokenService tokens) {
        return request -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return ServerRequest.from(request)
                    .headers(headers -> {
                        // never forward a client-supplied credential
                        headers.remove(HttpHeaders.AUTHORIZATION);
                        if (authentication != null
                                && authentication.getPrincipal() instanceof AuthenticatedSession session) {
                            headers.remove(HttpHeaders.COOKIE);
                            headers.setBearerAuth(tokens.mint(session));
                        }
                    })
                    .build();
        };
    }
}