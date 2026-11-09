package io.gave.group.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GroupSecurityTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final String USER_ID = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa";
    private static final KeyPair GATEWAY_KEYS = keys();
    private static final KeyPair UNTRUSTED_KEYS = keys();
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static Path publicKey;

    @BeforeAll
    static void startSecurityBoundary() throws Exception {
        publicKey = Files.createTempFile("group-security-test-public-", ".pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(GATEWAY_KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "gave.jwt.public-key", publicKey.toUri().toString(),
                "gave.jwt.issuer", "gave-gateway",
                "gave.jwt.audience", "gave-services")));
        context.register(Fixture.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clearTestContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterAll
    static void stopSecurityBoundary() throws Exception {
        if (context != null) context.close();
        if (publicKey != null) Files.deleteIfExists(publicKey);
    }

    @Test
    void signedTokenSuppliesIdentityAndRetainsItsVerifiedCredential() throws Exception {
        mvc.perform(authenticated(get("/api/groups"))
                        .header("X-User-ID", UUID.randomUUID().toString())
                        .header("X-Request-ID", "review-security.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callerId").value(USER_ID))
                .andExpect(jsonPath("$.principalType").value("CurrentUser"))
                .andExpect(jsonPath("$.verifiedCredentialRetained").value(true))
                .andExpect(header().string("X-Request-ID", "review-security.1"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void validBearerWriteNeedsNoCsrfAndCreatesNoHttpSession() throws Exception {
        var result = mvc.perform(authenticated(post("/api/groups")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callerId").value(USER_ID))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void missingCredentialReturnsSafeProblemDetails() throws Exception {
        mvc.perform(get("/api/groups").header("X-Request-ID", "missing-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("X-Request-ID", "missing-auth"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("missing-auth"))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void cookiesQueryParametersAndFormFieldsCannotAuthenticate() throws Exception {
        String jwt = signed(claims -> {});
        for (String cookie : new String[]{"opaque-ticket", jwt}) {
            mvc.perform(get("/api/groups").cookie(new Cookie("SESSION", cookie)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/groups").param("access_token", jwt))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/groups").contentType("application/x-www-form-urlencoded").param("access_token", jwt))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/groups").header("Authorization", "Bearer opaque-ticket"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/groups").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preexistingHttpSessionCannotSupplyAuthentication() throws Exception {
        var session = new MockHttpSession();
        var storedContext = SecurityContextHolder.createEmptyContext();
        storedContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("forged", null, java.util.List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, storedContext);
        mvc.perform(get("/api/groups").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(authenticated(get("/api/groups")).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.callerId").value(USER_ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidClaims")
    void invalidClaimsAre401RatherThanConversionFailures(String reason, Consumer<Map<String, Object>> edit) throws Exception {
        mvc.perform(get("/api/groups").header("Authorization", "Bearer " + signed(edit)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    static Stream<Arguments> invalidClaims() {
        return Stream.of(
                invalid("wrong issuer", c -> c.put("iss", "untrusted")),
                invalid("missing issuer", c -> c.remove("iss")),
                invalid("non-string issuer", c -> c.put("iss", 123)),
                invalid("wrong audience", c -> c.put("aud", java.util.List.of("other-service"))),
                invalid("missing audience", c -> c.remove("aud")),
                invalid("non-string audience", c -> c.put("aud", 123)),
                invalid("missing subject", c -> c.remove("sub")),
                invalid("malformed UUID", c -> c.put("sub", "not-a-uuid")),
                invalid("shortened UUID", c -> c.put("sub", "1-1-1-1-1")),
                invalid("non-string subject", c -> c.put("sub", 123)),
                invalid("missing sid", c -> c.remove("sid")),
                invalid("blank sid", c -> c.put("sid", " \t")),
                invalid("numeric sid", c -> c.put("sid", 123)),
                invalid("array sid", c -> c.put("sid", java.util.List.of("session"))),
                invalid("missing issued-at", c -> c.remove("iat")),
                invalid("missing expiry", c -> c.remove("exp")),
                invalid("string issued-at", c -> c.put("iat", Long.toString(NOW.getEpochSecond()))),
                invalid("string expiry", c -> c.put("exp", Long.toString(NOW.plusSeconds(60).getEpochSecond()))),
                invalid("boolean issued-at", c -> c.put("iat", true)),
                invalid("array expiry", c -> c.put("exp", java.util.List.of(NOW.getEpochSecond()))),
                invalid("expired", c -> c.put("exp", NOW.minusSeconds(1).getEpochSecond())),
                invalid("expires exactly now", c -> c.put("exp", NOW.getEpochSecond())),
                invalid("expiry before issued-at", c -> { c.put("iat", NOW.plusSeconds(3).getEpochSecond()); c.put("exp", NOW.plusSeconds(2).getEpochSecond()); }),
                invalid("expiry equals issued-at", c -> { c.put("iat", NOW.plusSeconds(3).getEpochSecond()); c.put("exp", NOW.plusSeconds(3).getEpochSecond()); }),
                invalid("future issued-at", c -> { c.put("iat", NOW.plusSeconds(6).getEpochSecond()); c.put("exp", NOW.plusSeconds(60).getEpochSecond()); }),
                invalid("lifetime exceeds gateway contract", c -> c.put("exp", NOW.plusSeconds(61).getEpochSecond())),
                invalid("string not-before", c -> c.put("nbf", Long.toString(NOW.getEpochSecond()))),
                invalid("array not-before", c -> c.put("nbf", java.util.List.of(NOW.getEpochSecond()))),
                invalid("not-before in future", c -> c.put("nbf", NOW.plusSeconds(6).getEpochSecond())),
                invalid("not-before equals expiry", c -> { c.put("nbf", NOW.plusSeconds(2).getEpochSecond()); c.put("exp", NOW.plusSeconds(2).getEpochSecond()); }),
                invalid("not-before later than expiry", c -> { c.put("nbf", NOW.plusSeconds(4).getEpochSecond()); c.put("exp", NOW.plusSeconds(2).getEpochSecond()); })
        );
    }

    @Test
    void rejectsWrongKeyWrongRsaAlgorithmSymmetricAndUnsignedTokens() throws Exception {
        var claims = claims();
        String wrongKey = signed(claims, UNTRUSTED_KEYS, JWSAlgorithm.RS256);
        String wrongAlgorithm = signed(claims, GATEWAY_KEYS, JWSAlgorithm.RS512);
        var symmetric = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(claims));
        symmetric.sign(new MACSigner(new byte[32]));
        String unsigned = new com.nimbusds.jose.PlainObject(new Payload(claims)).serialize();
        for (String token : java.util.List.of(wrongKey, wrongAlgorithm, symmetric.serialize(), unsigned)) {
            mvc.perform(get("/api/groups").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
        }
    }

    @Test
    void permitsOnlyFiveSecondsOfFutureIssuedAtTolerance() throws Exception {
        String token = signed(c -> {
            c.put("iat", NOW.plusSeconds(5).getEpochSecond());
            c.put("exp", NOW.plusSeconds(65).getEpochSecond());
            c.put("nbf", NOW.plusSeconds(5).getEpochSecond());
        });
        mvc.perform(get("/api/groups").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void accessDeniedHasItsOwnSafe403AndRequestId() throws Exception {
        mvc.perform(authenticated(get("/api/forbidden")).header("X-Request-ID", "denied-request"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.requestId").value("denied-request"))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(header().string("X-Request-ID", "denied-request"));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidAndRepeatedRequestIdsAreReplacedByOneGeneratedId() throws Exception {
        for (String[] ids : new String[][]{{}, {""}, {"bad id"}, {"id", "second"}, {"one,two"}, {"a".repeat(65)}}) {
            var request = get("/api/groups");
            if (ids.length > 0) request.header("X-Request-ID", (Object[]) ids);
            var result = mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("X-Request-ID", matchesPattern("[a-f0-9-]{36}"))).andReturn();
            String id = result.getResponse().getHeader("X-Request-ID");
            assertThat(UUID.fromString(id)).isNotNull();
            assertThat(result.getResponse().getHeaders("X-Request-ID")).hasSize(1);
            assertThat(JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).get("requestId").asString()).isEqualTo(id);
        }
    }

    @Test
    void requestIdContextIsAvailableToControllerAndClearedOnFailure() throws Exception {
        mvc.perform(authenticated(get("/api/groups")).header("X-Request-ID", "context-visible"))
                .andExpect(jsonPath("$.requestId").value("context-visible"));
        assertThatThrownBy(() -> mvc.perform(authenticated(get("/api/failure")).header("X-Request-ID", "failing-request")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        mvc.perform(get("/api/groups")).andExpect(header().string("X-Request-ID", matchesPattern("[a-f0-9-]{36}")));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void noLoginCsrfOrManagementPathsArePublicAtThisMilestone() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isUnauthorized());
        for (String path : java.util.List.of("/oauth2/authorization/google", "/api/csrf", "/actuator/health")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void acceptsTheMaximumSafeRequestIdLength() throws Exception {
        mvc.perform(get("/api/groups").header("X-Request-ID", "a".repeat(64)))
                .andExpect(header().string("X-Request-ID", "a".repeat(64)))
                .andExpect(jsonPath("$.requestId").value("a".repeat(64)));
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + signed(c -> {}));
    }

    private static Arguments invalid(String name, Consumer<Map<String, Object>> edit) {
        return Arguments.of(name, edit);
    }

    private static Map<String, Object> claims() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", "gave-gateway");
        claims.put("aud", java.util.List.of("gave-services"));
        claims.put("sub", USER_ID);
        claims.put("sid", "a".repeat(64));
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(60).getEpochSecond());
        return claims;
    }

    private static String signed(Consumer<Map<String, Object>> edit) {
        var claims = claims();
        edit.accept(claims);
        return signed(claims, GATEWAY_KEYS, JWSAlgorithm.RS256);
    }

    private static String signed(Map<String, Object> claims, KeyPair key, JWSAlgorithm algorithm) {
        try {
            var jwt = new JWSObject(new JWSHeader.Builder(algorithm).keyID("gateway-1").build(), new Payload(claims));
            jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
            return jwt.serialize();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @TestConfiguration
    @org.springframework.context.annotation.Import({
            io.gave.group.config.JwtConfig.class,
            io.gave.group.config.SecurityConfig.class,
            GroupJwtAuthenticationConverter.class,
            ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class,
            ProblemResponseWriter.class})
    @EnableWebMvc
    @EnableWebSecurity
    static class Fixture {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    /** These routes exist only in tests; no group API is implemented in Milestone 2. */
    @TestComponent
    @RestController
    static class ProbeController {
        @RequestMapping(path = "/api/groups", method = {RequestMethod.GET, RequestMethod.POST})
        Map<String, Object> identity(Authentication authentication, HttpServletRequest request) {
            boolean retained = authentication != null && authentication.getCredentials() instanceof Jwt jwt
                    && ("Bearer " + jwt.getTokenValue()).equals(request.getHeader("Authorization"));
            return Map.of("callerId", authentication == null ? "anonymous" : authentication.getName(),
                    "principalType", authentication == null ? "none" : authentication.getPrincipal().getClass().getSimpleName(),
                    "verifiedCredentialRetained", retained,
                    "requestId", MDC.get("requestId") == null ? "absent" : MDC.get("requestId"));
        }
        @GetMapping("/api/forbidden")
        void forbidden() { throw new AccessDeniedException("Sensitive internal fixture detail"); }
        @GetMapping("/api/failure")
        void failure() { throw new IllegalStateException("Fixture failure"); }
    }
}
