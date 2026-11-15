package io.gave.group.client;

import io.gave.group.exception.UserDirectoryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class UserDirectoryClientTest {
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
    static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    static final String DESTINATION = "http://user-directory.test/internal/users/lookup";
    private MockRestServiceServer server;
    private UserDirectoryClient client;
    private MutableClock clock;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("http://user-directory.test");
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(NOW);
        client = new UserDirectoryClient(builder.build(), clock);
    }

    @AfterEach
    void verifyRequests() {
        try { server.verify(); }
        finally { MDC.clear(); }
    }

    @Test
    void activeUserRequiresExactBatchRequestAndRelaysOnlyApprovedCredentials() {
        MDC.put("requestId", "lookup-review.3");
        server.expect(requestTo(DESTINATION))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"ids\":[\"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa\"]}"))
                .andExpect(header("Authorization", "Bearer verified-fixture-token"))
                .andExpect(header("X-Request-ID", "lookup-review.3"))
                .andExpect(headerDoesNotExist("Cookie"))
                .andExpect(headerDoesNotExist("X-User-ID"))
                .andExpect(headerDoesNotExist("X-XSRF-TOKEN"))
                .andRespond(withSuccess(userBody("ACTIVE"), MediaType.APPLICATION_JSON));
        assertThat(client.isActive(USER, credential())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVITED", "DELETED"})
    void recognizedInactiveStatusesAreValidIneligibility(String status) {
        success(userBody(status));
        assertThat(client.isActive(USER, credential())).isFalse();
    }

    @Test
    void missingUserIsValidIneligibilityRatherThanDependencyFailure() {
        success("{\"users\":[],\"missing\":[\"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa\"]}");
        assertThat(client.isActive(USER, credential())).isFalse();
    }

    @Test
    void standaloneLookupDoesNotInventARequestIdOrReadArbitraryHeaders() {
        server.expect(requestTo(DESTINATION))
                .andExpect(headerDoesNotExist("X-Request-ID"))
                .andExpect(headerDoesNotExist("Cookie"))
                .andRespond(withSuccess(userBody("ACTIVE"), MediaType.APPLICATION_JSON));
        assertThat(client.isActive(USER, credential())).isTrue();
    }

    @Test
    void unsafeMdcValueIsNotForwarded() {
        MDC.put("requestId", "invalid value");
        server.expect(requestTo(DESTINATION))
                .andExpect(headerDoesNotExist("X-Request-ID"))
                .andRespond(withSuccess(userBody("ACTIVE"), MediaType.APPLICATION_JSON));
        assertThat(client.isActive(USER, credential())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{}", "broken JSON",
            "{\"users\":null,\"missing\":[]}", "{\"users\":[],\"missing\":null}",
            "{\"users\":[],\"missing\":[]}", "{\"users\":[null],\"missing\":[]}",
            "{\"users\":[],\"missing\":[null]}",
            "{\"users\":[{\"id\":null,\"status\":\"ACTIVE\"}],\"missing\":[]}",
            "{\"users\":[{\"id\":\"bad-uuid\",\"status\":\"ACTIVE\"}],\"missing\":[]}",
            "{\"users\":[{\"id\":\"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa\",\"status\":null}],\"missing\":[]}",
            "{\"users\":[{\"id\":\"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa\",\"status\":\"UNKNOWN\"}],\"missing\":[]}",
            "{\"users\":[{\"id\":\"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa\",\"status\":\"active\"}],\"missing\":[]}",
            "{\"users\":[{\"id\":\"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa\",\"status\":\"ACTIVE \"}],\"missing\":[]}",
            "{\"users\":[],\"missing\":[\"bad-uuid\"]}"
    })
    void malformedOrIncompleteSuccessCannotEstablishEligibility(String body) {
        success(body);
        unavailable();
    }

    @Test
    void duplicatesContradictionsAndUnexpectedIdsFailClosed() {
        for (String body : new String[]{
                "{\"users\":[],\"missing\":[\"" + USER + "\",\"" + USER + "\"]}",
                userBody("ACTIVE").replace("\"missing\":[]", "\"missing\":[\"" + USER + "\"]"),
                userBody("ACTIVE").replace(USER.toString(), "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"),
                "{\"users\":[],\"missing\":[\"bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb\"]}",
                "{\"users\":[" + userEntry("ACTIVE") + "," + userEntry("ACTIVE") + "],\"missing\":[]}"
        }) {
            server.reset();
            success(body);
            unavailable();
            server.verify();
        }
    }

    @Test
    void shortenedUuidRepresentationIsNotAcceptedAsAnId() {
        UUID requested = UUID.fromString("00000001-0001-0001-0001-000000000001");
        success("{\"users\":[{\"id\":\"1-1-1-1-1\",\"status\":\"ACTIVE\"}],\"missing\":[]}");
        assertFailure(() -> client.isActive(requested, credential()), "USER_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 301, 302, 400, 403, 404, 405, 429, 500, 503})
    void unexpectedStatusDoesNotMeanTheUserIsInactive(int status) {
        server.expect(requestTo(DESTINATION)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.TEXT_PLAIN).body("sensitive-upstream-body"));
        unavailable();
    }

    @Test
    void upstream401IsAnAuthenticationFailure() {
        server.expect(requestTo(DESTINATION)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("sensitive-upstream-body"));
        assertFailure(() -> client.isActive(USER, credential()), "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void transportFailureIsUnavailableWithoutExposingItsMessage() {
        server.expect(requestTo(DESTINATION)).andRespond(withException(new IOException("sensitive-upstream-body")));
        unavailable();
    }

    @Test
    void unsupportedResponseContentTypeIsUnavailable() {
        server.expect(requestTo(DESTINATION)).andRespond(withSuccess(userBody("ACTIVE"), MediaType.TEXT_PLAIN));
        unavailable();
    }

    @Test
    void expiredCredentialIsRejectedBeforeAnyHttpRequest() {
        Jwt expired = Jwt.withTokenValue("expired").header("alg", "RS256")
                .issuedAt(NOW.minusSeconds(120)).expiresAt(NOW.minusSeconds(60)).build();
        assertFailure(() -> client.isActive(USER, expired), "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void credentialExpiringDuringLookupCannotAuthorizeAnOperation() {
        server.expect(requestTo(DESTINATION)).andRespond(request -> {
            clock.set(NOW.plusSeconds(61));
            return withSuccess(userBody("ACTIVE"), MediaType.APPLICATION_JSON).createResponse(request);
        });
        assertFailure(() -> client.isActive(USER, credential()), "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 500, 503})
    void credentialExpiryTakesPrecedenceOverUnexpectedUpstreamStatus(int status) {
        server.expect(requestTo(DESTINATION)).andRespond(request -> {
            clock.set(NOW.plusSeconds(61));
            return withStatus(HttpStatusCode.valueOf(status)).createResponse(request);
        });
        assertFailure(() -> client.isActive(USER, credential()), "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void credentialExpiryTakesPrecedenceOverMalformedResponse() {
        server.expect(requestTo(DESTINATION)).andRespond(request -> {
            clock.set(NOW.plusSeconds(61));
            return withSuccess("broken JSON", MediaType.APPLICATION_JSON).createResponse(request);
        });
        assertFailure(() -> client.isActive(USER, credential()), "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void credentialExpiryTakesPrecedenceOverTransportFailure() {
        server.expect(requestTo(DESTINATION)).andRespond(request -> {
            clock.set(NOW.plusSeconds(61));
            throw new IOException("sensitive-upstream-body");
        });
        assertFailure(() -> client.isActive(USER, credential()), "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED);
    }

    private void success(String body) {
        server.expect(requestTo(DESTINATION)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void unavailable() {
        assertFailure(() -> client.isActive(USER, credential()), "USER_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }

    static void assertFailure(Runnable action, String code, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(UserDirectoryException.class, ex -> {
            assertThat(ex.code().name()).isEqualTo(code);
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.getMessage()).doesNotContain("sensitive-upstream-body", "verified-fixture-token");
            assertThat(ex.getCause()).isNull();
        });
    }

    static Jwt credential() {
        return Jwt.withTokenValue("verified-fixture-token").header("alg", "RS256").subject(USER.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
    }

    static String userEntry(String status) {
        return "{\"id\":\"" + USER + "\",\"displayName\":\"Fixture user\",\"avatarUrl\":null,\"status\":\"" + status + "\"}";
    }

    static String userBody(String status) { return "{\"users\":[" + userEntry(status) + "],\"missing\":[]}"; }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant now) { instant = new AtomicReference<>(now); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
