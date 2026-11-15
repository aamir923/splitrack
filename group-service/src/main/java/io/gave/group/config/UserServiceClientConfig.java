package io.gave.group.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(UserServiceProperties.class)
public class UserServiceClientConfig {

    @Bean(destroyMethod = "close")
    public HttpClient userDirectoryTransport(UserServiceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public RestClient userDirectoryRestClient(RestClient.Builder builder,
                                              @Qualifier("userDirectoryTransport") HttpClient transport,
                                              UserServiceProperties properties) {
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone().baseUrl(properties.url().toString()).requestFactory(factory).build();
    }
}
