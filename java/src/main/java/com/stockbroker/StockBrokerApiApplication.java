package com.stockbroker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.stockbroker.common.config.RateLimitProperties;

/**
 * Main Spring Boot application for the Stock Broker API.
 *
 * <p>Modular monolith structure with packages by capability:
 * <ul>
 *   <li>{@code auth} - Authentication and authorization</li>
 *   <li>{@code market} - Market data gateway (gRPC client, state, fan-out)</li>
 *   <li>{@code portfolio} - Portfolio management</li>
 *   <li>{@code trading} - Order management and execution</li>
 *   <li>{@code alerts} - Price and metric alerts</li>
 *   <li>{@code research} - Backtesting and strategy research</li>
 *   <li>{@code admin} - Administrative endpoints</li>
 *   <li>{@code common} - Shared utilities, configuration, error handling</li>
 * </ul>
 *
 * <p>Do not add an explicit {@code @ComponentScan} here: {@code @SpringBootApplication}
 * already scans {@code com.stockbroker} and registers {@code TypeExcludeFilter}, which
 * keeps {@code @TestConfiguration} classes on the test classpath from overriding
 * production beans (for example {@code MockMarketGrpcClient}).
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableAsync
@EnableConfigurationProperties(RateLimitProperties.class)
@EntityScan(basePackages = "com.stockbroker.*.domain")
public class StockBrokerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockBrokerApiApplication.class, args);
    }
}