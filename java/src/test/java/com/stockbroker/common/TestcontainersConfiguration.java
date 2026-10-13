package com.stockbroker.common;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers configuration for integration tests.
 */
@Testcontainers
public class TestcontainersConfiguration {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.4-alpine"))
            .withDatabaseName("stockbroker")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/migration/V001__initial_schema.sql");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server --requirepass test --maxmemory 256mb --maxmemory-policy allkeys-lru");

    static {
        postgres.start();
        redis.start();

        // Set system properties for Spring Boot auto-configuration
        System.setProperty("SB_POSTGRES_HOST", postgres.getHost());
        System.setProperty("SB_POSTGRES_PORT", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("SB_POSTGRES_DB", "stockbroker");
        System.setProperty("SB_POSTGRES_USER", "test");
        System.setProperty("SB_POSTGRES_PASSWORD", "test");

        System.setProperty("SB_REDIS_HOST", redis.getHost());
        System.setProperty("SB_REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
        System.setProperty("SB_REDIS_PASSWORD", "test");
    }
}