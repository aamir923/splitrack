package io.gave.group.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceClientConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UserServiceClientConfig.class)
            .withBean(RestClient.Builder.class, () -> RestClient.builder())
            .withPropertyValues("gave.services.user-service.url=http://localhost:8081",
                    "gave.services.user-service.connect-timeout=2s", "gave.services.user-service.read-timeout=3s");

    @Test
    void bindsPropertiesAndClosesTheManagedTransportWithTheContext() {
        var transport = new AtomicReference<HttpClient>();
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(UserServiceProperties.class).hasSingleBean(RestClient.class);
            var properties = context.getBean(UserServiceProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            transport.set(context.getBean(HttpClient.class));
            assertThat(transport.get().connectTimeout()).contains(Duration.ofSeconds(2));
            assertThat(transport.get().isTerminated()).isFalse();
        });
        assertThat(transport.get().isTerminated()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:/tmp/users", "ftp://host", "/relative", "http://user:password@host", "http://host/path",
            "http://host?query=x", "http://host#fragment", "http://localhost:0", "http://localhost:65536"})
    void rejectsDestinationsThatAreNotFixedHttpOrigins(String url) {
        runner.withPropertyValues("gave.services.user-service.url=" + url).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "500ns", "11s", "not-a-duration"})
    void rejectsUnboundedOrNonpositiveTimeouts(String timeout) {
        runner.withPropertyValues("gave.services.user-service.connect-timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("gave.services.user-service.read-timeout=" + timeout)
                .run(context -> assertThat(context).hasFailed());
    }
}
