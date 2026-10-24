package com.stockbroker.common;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * Marker configuration for tests that run against developer-provided native
 * PostgreSQL and Redis services. Container orchestration is intentionally not
 * part of the test runtime.
 */
@TestConfiguration
public class LocalTestConfiguration {

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.client.RestTemplateCustomizer restTemplateCustomizer() {
        return restTemplate -> restTemplate.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }
}
