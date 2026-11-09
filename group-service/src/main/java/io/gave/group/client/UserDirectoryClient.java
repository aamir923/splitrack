package io.gave.group.client;

import io.gave.group.exception.UserDirectoryException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.gave.group.exception.UserDirectoryException.Code.AUTHENTICATION_REQUIRED;
import static io.gave.group.exception.UserDirectoryException.Code.USER_SERVICE_UNAVAILABLE;

@Component
public class UserDirectoryClient {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final RestClient client;
    private final Clock clock;

    public UserDirectoryClient(@Qualifier("userDirectoryRestClient") RestClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /** The credential must be the already-verified Jwt retained by the request authentication. */
    public boolean isActive(UUID userId, Jwt verifiedCredential) {
        Objects.requireNonNull(userId, "userId");
        requireUnexpired(verifiedCredential);
        try {
            UserLookupResponse response = client.post().uri("/internal/users/lookup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.setBearerAuth(verifiedCredential.getTokenValue());
                        String requestId = MDC.get("requestId");
                        if (requestId != null && SAFE_REQUEST_ID.matcher(requestId).matches()) {
                            headers.set("X-Request-ID", requestId);
                        }
                    })
                    .body(new LookupRequest(List.of(userId)))
                    .exchange((request, upstream) -> {
                        int status = upstream.getStatusCode().value();
                        if (status == 401) throw new UserDirectoryException(AUTHENTICATION_REQUIRED);
                        if (status != 200) throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
                        return upstream.bodyTo(UserLookupResponse.class);
                    });
            requireUnexpired(verifiedCredential);
            return validate(response, userId);
        } catch (RuntimeException ex) {
            requireUnexpired(verifiedCredential);
            if (ex instanceof UserDirectoryException failure) throw failure;
            // Transport/parser exceptions may include URLs, headers or upstream data; do not expose their messages/causes.
            throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        }
    }

    private void requireUnexpired(Jwt credential) {
        if (credential == null || credential.getExpiresAt() == null || !credential.getExpiresAt().isAfter(clock.instant())) {
            throw new UserDirectoryException(AUTHENTICATION_REQUIRED);
        }
    }

    private static boolean validate(UserLookupResponse response, UUID requested) {
        if (response == null || response.users() == null || response.missing() == null) {
            throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        }
        // Each request contains one ID. Exactly one matching record or missing entry must account for it.
        if (response.users().isEmpty() && response.missing().size() == 1) {
            requireRequestedId(response.missing().getFirst(), requested);
            return false;
        }
        if (response.users().size() != 1 || !response.missing().isEmpty()) {
            throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        }
        var user = response.users().getFirst();
        if (user == null || user.status() == null) throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        requireRequestedId(user.id(), requested);
        return switch (user.status()) {
            case "ACTIVE" -> true;
            case "INVITED", "DELETED" -> false;
            default -> throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        };
    }

    private static void requireRequestedId(String raw, UUID requested) {
        if (raw == null) throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        UUID id = UUID.fromString(raw);
        if (!id.toString().equalsIgnoreCase(raw) || !requested.equals(id)) {
            throw new UserDirectoryException(USER_SERVICE_UNAVAILABLE);
        }
    }

    private record LookupRequest(List<UUID> ids) { }
}
