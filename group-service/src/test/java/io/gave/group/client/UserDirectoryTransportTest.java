package io.gave.group.client;

import com.sun.net.httpserver.HttpServer;
import io.gave.group.config.UserServiceClientConfig;
import io.gave.group.config.UserServiceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static io.gave.group.client.UserDirectoryClientTest.*;

class UserDirectoryTransportTest {
    private HttpServer server;
    private ExecutorService executor;
    private HttpClient transport;
    private UserDirectoryClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private volatile long headerDelay;
    private volatile long bodyDelay;
    private volatile String redirect;

    @BeforeEach
    void startLocalDependency() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/internal/users/lookup", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            exchange.getRequestBody().readAllBytes();
            try {
                if (headerDelay > 0) Thread.sleep(headerDelay);
                byte[] bytes = userBody("ACTIVE").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (redirect != null) exchange.getResponseHeaders().set("Location", redirect);
                exchange.sendResponseHeaders(redirect == null ? 200 : 302, bytes.length);
                if (bodyDelay > 0) Thread.sleep(bodyDelay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Expected when the client times out and closes a fixture connection.
            } finally {
                exchange.close();
            }
        });
        server.start();
        var properties = new UserServiceProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofMillis(200), Duration.ofMillis(150));
        var config = new UserServiceClientConfig();
        transport = config.userDirectoryTransport(properties);
        client = new UserDirectoryClient(config.userDirectoryRestClient(RestClient.builder(), transport, properties),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stopLocalDependency() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (transport != null) transport.close();
        MDC.clear();
    }

    @Test
    void realTransportRelaysTokenAndCorrelationToTheConfiguredDestination() {
        MDC.put("requestId", "wire-review.3");
        assertThat(client.isActive(USER, credential())).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(authorization.get()).isEqualTo("Bearer verified-fixture-token");
        assertThat(requestId.get()).isEqualTo("wire-review.3");
    }

    @Test
    void delayedHeadersAreBoundedAndNotRetried() {
        headerDelay = 750;
        boundedUnavailable();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void stalledBodyAfterHeadersIsBoundedAndNotRetried() {
        bodyDelay = 750;
        boundedUnavailable();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refusedConnectionFailsClosed() {
        server.stop(0);
        boundedUnavailable();
        assertThat(calls.get()).isZero();
    }

    @Test
    void redirectCannotSendTheVerifiedJwtToAnotherDestination() throws Exception {
        var other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirectedCalls = new AtomicInteger();
        other.createContext("/", exchange -> {
            redirectedCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        other.start();
        try {
            redirect = "http://127.0.0.1:" + other.getAddress().getPort() + "/other";
            assertFailure(() -> client.isActive(USER, credential()), "USER_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(redirectedCalls.get()).isZero();
        } finally {
            other.stop(0);
        }
    }

    private void boundedUnavailable() {
        long start = System.nanoTime();
        assertFailure(() -> client.isActive(USER, credential()), "USER_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
}
