package io.gave.group.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("gave.services.user-service")
public record UserServiceProperties(@NotNull URI url, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

    @AssertTrue(message = "user-service URL must be an HTTP(S) origin without credentials, path, query or fragment")
    public boolean isValidDestination() {
        if (url == null) return false;
        int port = url.getPort();
        return ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                && url.getHost() != null && url.getUserInfo() == null
                && url.getQuery() == null && url.getFragment() == null
                && (url.getPath() == null || url.getPath().isEmpty() || "/".equals(url.getPath()))
                && (port == -1 || (port > 0 && port <= 65535));
    }

    @AssertTrue(message = "lookup timeouts must be between 1ms and 10s")
    public boolean isValidTimeouts() {
        return bounded(connectTimeout) && bounded(readTimeout);
    }

    private static boolean bounded(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofSeconds(10)) <= 0;
    }
}
